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
 * A group whose parent isn't itself in the input list (the principal has
 * access to the group but not its parent, or it's a genuine root) is
 * treated as a root.
 */
export function sortGroupsHierarchically(groups: ReadonlyArray<Group>): ReadonlyArray<GroupOption> {
  const idsInList = new Set(groups.map((group) => group.id));
  const childrenByParentId = new Map<GroupId | null, Group[]>();
  for (const group of groups) {
    const parentId = group.parentId !== null && idsInList.has(group.parentId) ? group.parentId : null;
    const siblings = childrenByParentId.get(parentId) ?? [];
    siblings.push(group);
    childrenByParentId.set(parentId, siblings);
  }
  for (const siblings of childrenByParentId.values()) {
    siblings.sort((a, b) => a.name.localeCompare(b.name, 'de'));
  }

  const options: GroupOption[] = [];
  function visit(parentId: GroupId | null, depth: number): void {
    for (const group of childrenByParentId.get(parentId) ?? []) {
      const label = INDENT.repeat(depth) + (depth > 0 ? DEPTH_MARKER : '') + group.name;
      options.push({ group, depth, label });
      visit(group.id, depth + 1);
    }
  }
  visit(null, 0);

  return options;
}
