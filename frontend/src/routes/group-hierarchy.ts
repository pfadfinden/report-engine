import { Group, GroupId } from '../domain/model/group';

export interface GroupOption {
  readonly group: Group;
  /** How many ancestors of this group are also present in the input list. */
  readonly depth: number;
  /** Indented display label — parentId chain made visible for a flat <select>. */
  readonly label: string;
}

// Regular spaces collapse in rendered HTML (including <option> text), so a
// depth prefix needs actual non-breaking spaces to stay visible; the dash
// makes depth 1 read as intentional nesting rather than stray whitespace.
const INDENT = '  ';
const DEPTH_MARKER = '\u2013 '; // en dash

/**
 * Orders groups depth-first (parents before their children, alphabetically
 * within each level) and computes an indentation depth for each, so a flat
 * <select> can visually convey the tree instead of listing same-named
 * groups from different branches with no way to tell them apart.
 *
 * `groups` is what actually gets rendered as options; `allGroups` (defaults
 * to `groups`) is the fuller set used only to find each group's place in the
 * tree. They differ when the caller has hidden some groups from the list
 * (e.g. types with no report) - without `allGroups`, a hidden intermediate
 * group would sever the hierarchy for its own children, making them look
 * like unrelated roots instead of just skipping the hidden ancestor.
 *
 * A group with no ancestor left in either list (the principal has access to
 * the group but not any ancestor, or it's a genuine root) is treated as a
 * root.
 */
export function sortGroupsHierarchically(
  groups: ReadonlyArray<Group>,
  allGroups: ReadonlyArray<Group> = groups,
): ReadonlyArray<GroupOption> {
  const idsInList = new Set(groups.map((group) => group.id));
  const allById = new Map(allGroups.map((group) => [group.id, group]));

  // Walks up through allGroups (not just `groups`) so a hidden intermediate
  // ancestor is transparently skipped rather than orphaning its descendants.
  function nearestRenderedAncestorId(group: Group): GroupId | null {
    let current: Group | undefined = group;
    while (current && current.parentId !== null) {
      if (idsInList.has(current.parentId)) {
        return current.parentId;
      }
      current = allById.get(current.parentId);
    }
    return null;
  }

  const childrenByParentId = new Map<GroupId | null, Group[]>();
  for (const group of groups) {
    const parentId = nearestRenderedAncestorId(group);
    const siblings = childrenByParentId.get(parentId) ?? [];
    siblings.push(group);
    childrenByParentId.set(parentId, siblings);
  }
  for (const siblings of childrenByParentId.values()) {
    siblings.sort((a, b) => a.name.localeCompare(b.name, 'de'));
  }

  // Same-named groups from different branches (e.g. two Stämme both called
  // "Ahoi") are indistinguishable by name alone, which defeats a typeahead
  // search as much as it defeats reading the flat list. Suffix the parent's
  // name onto every group whose name isn't unique in the accessible set.
  const nameCounts = new Map<string, number>();
  for (const group of groups) {
    nameCounts.set(group.name, (nameCounts.get(group.name) ?? 0) + 1);
  }

  const options: GroupOption[] = [];
  function visit(parentId: GroupId | null, depth: number): void {
    for (const group of childrenByParentId.get(parentId) ?? []) {
      // The immediate parent's real name, even if that parent itself is hidden from the list.
      const parent = group.parentId !== null ? allById.get(group.parentId) : undefined;
      const disambiguation = (nameCounts.get(group.name) ?? 0) > 1 && parent ? ` (${parent.name})` : '';
      const label = INDENT.repeat(depth) + (depth > 0 ? DEPTH_MARKER : '') + group.name + disambiguation;
      options.push({ group, depth, label });
      visit(group.id, depth + 1);
    }
  }
  visit(null, 0);

  return options;
}
