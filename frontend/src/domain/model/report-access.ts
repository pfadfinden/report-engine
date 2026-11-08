import { Group, GroupId } from './group';
import { Principal } from './principal';
import { Report } from './report';

// Permissions that extend a role from its own group to everything below it
// (same set HitobitoGroupsService expands subtrees for).
const SUBTREE_PERMISSIONS = ['layer_and_below_read', 'layer_and_below_full'];

/**
 * Enforces a report's `onlyWithRole` for the selected group: the principal must
 * hold a role whose `role_class` is listed, and that role must sit
 *  - on the selected group itself, or
 *  - on one of its ancestors AND carry a layer_and_below_* permission (i.e. the
 *    role actually reaches down to the selected group).
 * A report without `onlyWithRole` is open to everyone.
 *
 * `groups` must contain the selected group's ancestor chain (the principal's
 * available groups do, since subtrees are resolved from the layer root down).
 */
export function isAllowedForPrincipal(
  report: Pick<Report, 'onlyWithRole'>,
  principal: Principal,
  group: Group,
  groups: ReadonlyArray<Group>,
): boolean {
  if (report.onlyWithRole.length === 0) {
    return true;
  }

  const byId = new Map(groups.map((g) => [g.id, g]));
  const self = group.id;
  const ancestors = new Set<GroupId>();
  for (let current = byId.get(group.parentId ?? ''); current && !ancestors.has(current.id); ) {
    ancestors.add(current.id);
    current = current.parentId === null ? undefined : byId.get(current.parentId);
  }

  return principal.roles.some(
    (role) =>
      report.onlyWithRole.includes(role.roleClass) &&
      (role.groupId === self ||
        (ancestors.has(role.groupId) && role.permissions.some((p) => SUBTREE_PERMISSIONS.includes(p)))),
  );
}
