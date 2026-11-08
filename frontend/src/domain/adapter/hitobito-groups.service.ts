import { Group, GroupId } from '../model/group';
import { Principal, PrincipalId } from '../model/principal';
import { GroupsService } from '../port/groups.service';
import * as logger from '../../telemetry/logger';

// Permissions that grant access to a group's entire *layer* and everything
// below it, not just the specific group the role is attached to. See
// "permissions" in each of the principal's resolved roles.
const SUBTREE_PERMISSIONS = ['layer_and_below_read', 'layer_and_below_full'];

const GROUP_FIELDS = 'name,type,parent_id,layer_group_id';

interface HitobitoGroupResource {
  id: string;
  attributes: {
    name: string;
    type: string;
    parent_id: number | null;
    // The nearest layer group at or above this group (equals its own id for
    // a layer group itself). A role's groupId is often a non-layer group
    // (e.g. an office/committee) nested under a layer, so "layer and below"
    // access has to expand from here, not from the role's own groupId.
    layer_group_id: number | null;
  };
}

interface GroupWithLayer extends Group {
  layerGroupId: GroupId | null;
}

// layerGroupId is only needed internally, to resolve subtree roots — strip
// it before a group is added to a result the rest of the app consumes.
function toGroup({ id, name, type, parentId }: GroupWithLayer): Group {
  return { id, name, type, parentId };
}

interface HitobitoGroupsDocument {
  data: HitobitoGroupResource[];
  links?: {
    next?: string | { href: string } | null;
  };
  meta?: { stats?: { total?: { count?: number } } };
}

interface CacheEntry {
  readonly groups: ReadonlyArray<Group>;
  // Fresh until here; served as-is.
  readonly expiresAt: number;
  // Stale (but still served instantly, with a background refresh) until here.
  readonly staleUntil: number;
}

const DEFAULT_CACHE_TTL_MS = 5 * 60 * 1000;
const DEFAULT_MAX_STALE_MS = 60 * 60 * 1000;

// A hung Hitobito API would otherwise block every group lookup (and, via GroupsService, most of
// the app's authorization checks) forever.
// The API's default page size is small (Graphiti: 20), which turns a few thousand groups into
// hundreds of sequential round trips; ask for big pages, plus the total so the rest can be fetched concurrently.
const PAGE_SIZE = 1000;
const PAGE_FETCH_CONCURRENCY = 5;

const HITOBITO_REQUEST_TIMEOUT_MS = 15_000;

/**
 * Resolves a principal's groups against the Hitobito API (see <apiUrl>/api/openapi.yaml).
 *
 * Every group referenced by a role in the principal's "roles" claim is included, extended
 * with its Hitobito group type. Roles carrying "layer_and_below_read"/"layer_and_below_full"
 * additionally pull in that group's full subtree, since those permissions grant access to
 * the group and everything below it.
 *
 * Results are cached per principal (stale-while-revalidate): fresh for `cacheTtlMs`; for a
 * further `maxStaleMs` the previous result is still returned immediately while a single
 * background refresh runs. Only a principal with no entry (or one older than that) waits for
 * the - slow, multi-request - resolution. `findFor` can also be called without awaiting to
 * warm the cache (e.g. right after login).
 */
export class HitobitoGroupsService implements GroupsService {
  private readonly cache = new Map<PrincipalId, CacheEntry>();
  private readonly inFlight = new Map<PrincipalId, Promise<ReadonlyArray<Group>>>();

  constructor(
    private readonly apiUrl: string,
    private readonly apiToken: string,
    private readonly cacheTtlMs: number = DEFAULT_CACHE_TTL_MS,
    private readonly maxStaleMs: number = DEFAULT_MAX_STALE_MS,
  ) {}

  public async findFor(principal: Principal): Promise<ReadonlyArray<Group>> {
    const cached = this.cache.get(principal.id);
    const now = Date.now();

    if (cached && cached.expiresAt > now) {
      return cached.groups;
    }

    if (cached && cached.staleUntil > now) {
      // Serve the stale result now; a failed refresh keeps it (and is retried on the next call).
      this.refresh(principal).catch((err) =>
        logger.error('groups.refresh_failed', { 'principal.id': principal.id }, err),
      );
      return cached.groups;
    }

    return this.refresh(principal);
  }

  // One resolution per principal at a time: concurrent callers (login prefetch, the first page
  // load, background refreshes) share the same promise.
  private refresh(principal: Principal): Promise<ReadonlyArray<Group>> {
    const existing = this.inFlight.get(principal.id);
    if (existing) {
      return existing;
    }

    const promise = this.resolveFor(principal)
      .then((groups) => {
        const now = Date.now();
        this.cache.set(principal.id, {
          groups,
          expiresAt: now + this.cacheTtlMs,
          staleUntil: now + this.cacheTtlMs + this.maxStaleMs,
        });
        return groups;
      })
      .finally(() => this.inFlight.delete(principal.id));
    this.inFlight.set(principal.id, promise);
    return promise;
  }

  private async resolveFor(principal: Principal): Promise<ReadonlyArray<Group>> {
    const startedAt = performance.now();
    const result = await this.resolveForUntimed(principal);
    logger.event('groups.resolved', {
      'principal.id': principal.id,
      groups: result.length,
      'duration.ms': Math.round(performance.now() - startedAt),
    });
    return result;
  }

  private async resolveForUntimed(principal: Principal): Promise<ReadonlyArray<Group>> {
    const groupIds = [...new Set(principal.roles.map((role) => role.groupId))];
    if (groupIds.length === 0) {
      return [];
    }

    const directGroups = await this.fetchGroups({
      'filter[id][eq]': groupIds,
    });
    const layerGroupIdByGroupId = new Map(directGroups.map((group) => [group.id, group.layerGroupId]));

    const groups = new Map<GroupId, Group>();
    for (const group of directGroups) {
      groups.set(group.id, toGroup(group));
    }

    // Roles are commonly attached to a non-layer group (e.g. an office),
    // so the subtree for "layer and below" has to expand from that group's
    // enclosing layer, not from the role's own groupId.
    const layerRootIds = [
      ...new Set(
        principal.roles
          .filter((role) => role.permissions.some((permission) => SUBTREE_PERMISSIONS.includes(permission)))
          .map((role) => layerGroupIdByGroupId.get(role.groupId))
          .filter((id): id is GroupId => id != null),
      ),
    ];

    if (layerRootIds.length > 0) {
      // A role can be attached directly to a layer group, in which case
      // it's already in `groups` above — no need to re-fetch it. The remaining roots and the
      // subtree walk are independent, so they run concurrently.
      const unfetchedLayerRootIds = layerRootIds.filter((id) => !groups.has(id));
      const [layerRoots, subtree] = await Promise.all([
        unfetchedLayerRootIds.length > 0
          ? this.fetchGroups({ 'filter[id][eq]': unfetchedLayerRootIds })
          : Promise.resolve([] as GroupWithLayer[]),
        this.fetchSubtrees(layerRootIds),
      ]);
      for (const group of [...layerRoots, ...subtree]) {
        groups.set(group.id, toGroup(group));
      }
    }

    return [...groups.values()];
  }

  private async fetchSubtrees(rootGroupIds: ReadonlyArray<GroupId>): Promise<GroupWithLayer[]> {
    const startedAt = performance.now();
    let levels = 0;
    const subtree: GroupWithLayer[] = [];
    let frontier = [...new Set(rootGroupIds)];

    while (frontier.length > 0) {
      levels++;
      const children = await this.fetchGroups({
        'filter[parent_id][eq]': frontier,
      });
      subtree.push(...children);
      frontier = children.map((group) => group.id);
    }

    logger.event('groups.subtree', {
      roots: rootGroupIds.length,
      levels,
      groups: subtree.length,
      'duration.ms': Math.round(performance.now() - startedAt),
    });
    return subtree;
  }

  private async fetchGroups(filters: Record<string, ReadonlyArray<string>>): Promise<GroupWithLayer[]> {
    const firstUrl = this.buildGroupsUrl(filters);
    const firstDocument = await this.fetchGroupsDocument(firstUrl);
    const documents = [firstDocument];

    const remainingPages = this.remainingPageCount(firstDocument);
    if (remainingPages > 0) {
      // The API told us how many pages there are, so fetch them concurrently (bounded).
      const pageNumbers = Array.from({ length: remainingPages }, (_, i) => i + 2);
      for (let i = 0; i < pageNumbers.length; i += PAGE_FETCH_CONCURRENCY) {
        const batch = pageNumbers.slice(i, i + PAGE_FETCH_CONCURRENCY);
        documents.push(
          ...(await Promise.all(
            batch.map((page) => {
              const url = new URL(firstUrl);
              url.searchParams.set('page[number]', String(page));
              return this.fetchGroupsDocument(url);
            }),
          )),
        );
      }
    } else {
      // No usable total: follow the `next` links one page at a time.
      let current = firstDocument;
      let currentUrl = firstUrl;
      for (let next = this.nextPageUrl(current, currentUrl); next; next = this.nextPageUrl(current, currentUrl)) {
        currentUrl = next;
        current = await this.fetchGroupsDocument(next);
        documents.push(current);
      }
    }

    return documents.flatMap((document) =>
      document.data.map((resource) => ({
        id: resource.id,
        name: resource.attributes.name,
        type: resource.attributes.type,
        parentId: resource.attributes.parent_id == null ? null : String(resource.attributes.parent_id),
        layerGroupId: resource.attributes.layer_group_id == null ? null : String(resource.attributes.layer_group_id),
      })),
    );
  }

  // Pages after the first, derived from `meta.stats.total.count` (requested via stats[total]=count).
  // 0 when there is no next page or the total is not reported.
  private remainingPageCount(document: HitobitoGroupsDocument): number {
    const total = document.meta?.stats?.total?.count;
    if (!document.links?.next || typeof total !== 'number' || document.data.length === 0) {
      return 0;
    }
    return Math.max(0, Math.ceil(total / document.data.length) - 1);
  }

  private buildGroupsUrl(filters: Record<string, ReadonlyArray<string>>): URL {
    const url = new URL('/api/groups', this.apiUrl);
    url.searchParams.set('fields[groups]', GROUP_FIELDS);
    url.searchParams.set('page[size]', String(PAGE_SIZE));
    url.searchParams.set('stats[total]', 'count');
    for (const [param, values] of Object.entries(filters)) {
      url.searchParams.set(param, values.join(','));
    }
    return url;
  }

  private async fetchGroupsDocument(url: URL): Promise<HitobitoGroupsDocument> {
    const startedAt = performance.now();
    const document = await this.fetchGroupsDocumentUntimed(url);
    // Only the shape of the request is logged (filter name, page, URL length) - never the token or id lists.
    logger.debug('groups.request', {
      filter: [...url.searchParams.keys()].find((key) => key.startsWith('filter[')) ?? 'none',
      page: url.searchParams.get('page[number]') ?? '1',
      'url.length': url.href.length,
      items: document.data.length,
      total: document.meta?.stats?.total?.count ?? -1,
      'duration.ms': Math.round(performance.now() - startedAt),
    });
    return document;
  }

  private async fetchGroupsDocumentUntimed(url: URL): Promise<HitobitoGroupsDocument> {
    const res = await fetch(url, {
      headers: {
        Accept: 'application/vnd.api+json',
        'X-TOKEN': this.apiToken,
      },
      signal: AbortSignal.timeout(HITOBITO_REQUEST_TIMEOUT_MS),
    });

    if (!res.ok) {
      throw new Error(`Hitobito API request to ${url} failed: ${res.status} ${res.statusText}`);
    }

    return (await res.json()) as HitobitoGroupsDocument;
  }

  private nextPageUrl(document: HitobitoGroupsDocument, currentUrl: URL): URL | undefined {
    const next = document.links?.next;
    if (!next) {
      return undefined;
    }
    const href = typeof next === 'string' ? next : next.href;
    return new URL(href, currentUrl);
  }
}
