import { Group, GroupId } from "../model/group";
import { Principal } from "../model/principal";
import { GroupsService } from "../port/groups.service";

// Permissions that grant access to a group's entire *layer* and everything
// below it, not just the specific group the role is attached to. See
// "permissions" in each of the principal's resolved roles.
const SUBTREE_PERMISSIONS = ["layer_and_below_read", "layer_and_below_full"];

const GROUP_FIELDS = "name,type,parent_id,layer_group_id";

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
}

/**
 * Resolves a principal's groups against the Hitobito API (see <apiUrl>/api/openapi.yaml).
 *
 * Every group referenced by a role in the principal's "roles" claim is included, extended
 * with its Hitobito group type. Roles carrying "layer_and_below_read"/"layer_and_below_full"
 * additionally pull in that group's full subtree, since those permissions grant access to
 * the group and everything below it.
 */
export class HitobitoGroupsService implements GroupsService {
  constructor(
    private readonly apiUrl: string,
    private readonly apiToken: string,
  ) {}

  public async findFor(principal: Principal): Promise<ReadonlyArray<Group>> {
    const groupIds = [...new Set(principal.roles.map((role) => role.groupId))];
    if (groupIds.length === 0) {
      return [];
    }

    const directGroups = await this.fetchGroups({
      "filter[id][eq]": groupIds,
    });
    const layerGroupIdByGroupId = new Map(
      directGroups.map((group) => [group.id, group.layerGroupId]),
    );

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
          .filter((role) =>
            role.permissions.some((permission) =>
              SUBTREE_PERMISSIONS.includes(permission),
            ),
          )
          .map((role) => layerGroupIdByGroupId.get(role.groupId))
          .filter((id): id is GroupId => id != null),
      ),
    ];

    if (layerRootIds.length > 0) {
      // A role can be attached directly to a layer group, in which case
      // it's already in `groups` above — no need to re-fetch it.
      const unfetchedLayerRootIds = layerRootIds.filter(
        (id) => !groups.has(id),
      );
      if (unfetchedLayerRootIds.length > 0) {
        for (const group of await this.fetchGroups({
          "filter[id][eq]": unfetchedLayerRootIds,
        })) {
          groups.set(group.id, toGroup(group));
        }
      }

      for (const group of await this.fetchSubtrees(layerRootIds)) {
        groups.set(group.id, toGroup(group));
      }
    }

    return [...groups.values()];
  }

  private async fetchSubtrees(
    rootGroupIds: ReadonlyArray<GroupId>,
  ): Promise<GroupWithLayer[]> {
    const subtree: GroupWithLayer[] = [];
    let frontier = [...new Set(rootGroupIds)];

    while (frontier.length > 0) {
      const children = await this.fetchGroups({
        "filter[parent_id][eq]": frontier,
      });
      subtree.push(...children);
      frontier = children.map((group) => group.id);
    }

    return subtree;
  }

  private async fetchGroups(
    filters: Record<string, ReadonlyArray<string>>,
  ): Promise<GroupWithLayer[]> {
    const groups: GroupWithLayer[] = [];

    let nextUrl: URL | undefined = this.buildGroupsUrl(filters);
    while (nextUrl) {
      const document = await this.fetchGroupsDocument(nextUrl);

      for (const resource of document.data) {
        groups.push({
          id: resource.id,
          name: resource.attributes.name,
          type: resource.attributes.type,
          parentId:
            resource.attributes.parent_id == null
              ? null
              : String(resource.attributes.parent_id),
          layerGroupId:
            resource.attributes.layer_group_id == null
              ? null
              : String(resource.attributes.layer_group_id),
        });
      }

      nextUrl = this.nextPageUrl(document, nextUrl);
    }

    return groups;
  }

  private buildGroupsUrl(filters: Record<string, ReadonlyArray<string>>): URL {
    const url = new URL("/api/groups", this.apiUrl);
    url.searchParams.set("fields[groups]", GROUP_FIELDS);
    for (const [param, values] of Object.entries(filters)) {
      url.searchParams.set(param, values.join(","));
    }
    return url;
  }

  private async fetchGroupsDocument(url: URL): Promise<HitobitoGroupsDocument> {
    const res = await fetch(url, {
      headers: {
        Accept: "application/vnd.api+json",
        "X-TOKEN": this.apiToken,
      },
    });

    if (!res.ok) {
      throw new Error(
        `Hitobito API request to ${url} failed: ${res.status} ${res.statusText}`,
      );
    }

    return (await res.json()) as HitobitoGroupsDocument;
  }

  private nextPageUrl(
    document: HitobitoGroupsDocument,
    currentUrl: URL,
  ): URL | undefined {
    const next = document.links?.next;
    if (!next) {
      return undefined;
    }
    const href = typeof next === "string" ? next : next.href;
    return new URL(href, currentUrl);
  }
}
