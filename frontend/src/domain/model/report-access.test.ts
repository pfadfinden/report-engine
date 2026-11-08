import { isAllowedForPrincipal } from './report-access';
import { Principal, PrincipalRole } from './principal';
import { Group } from './group';

const groups: Group[] = [
  { id: '1', name: 'Land', type: 'Group::Land', parentId: null },
  { id: '2', name: 'Region', type: 'Group::Region', parentId: '1' },
  { id: '3', name: 'Stamm', type: 'Group::Stamm', parentId: '2' },
  { id: '9', name: 'Other', type: 'Group::Stamm', parentId: null },
];
const [land, , stamm, other] = [groups[0], groups[1], groups[2], groups[3]];

const role = (roleClass: string, groupId: string, permissions: string[] = []): PrincipalRole => ({
  groupId,
  groupName: 'g',
  role: roleClass,
  roleClass,
  roleName: 'n',
  permissions,
});
const principal = (...roles: PrincipalRole[]): Principal => ({ id: 'p', name: 'n', roles });
const report = { onlyWithRole: ['Group::Vorstand'] };

test('unrestricted report is allowed for everyone', () => {
  expect(isAllowedForPrincipal({ onlyWithRole: [] }, principal(), stamm, groups)).toBe(true);
});

test('role on the exact group is allowed', () => {
  expect(isAllowedForPrincipal(report, principal(role('Group::Vorstand', '3')), stamm, groups)).toBe(true);
});

test('wrong role_class is denied', () => {
  expect(isAllowedForPrincipal(report, principal(role('Group::Other', '3')), stamm, groups)).toBe(false);
});

test('role on an ancestor with layer_and_below permission is allowed', () => {
  const p = principal(role('Group::Vorstand', '1', ['layer_and_below_full']));
  expect(isAllowedForPrincipal(report, p, stamm, groups)).toBe(true);
});

test('role on an ancestor without subtree permission is denied', () => {
  const p = principal(role('Group::Vorstand', '1', ['contact_data']));
  expect(isAllowedForPrincipal(report, p, stamm, groups)).toBe(false);
});

test('role on an unrelated group, a descendant or in a different branch is denied', () => {
  expect(isAllowedForPrincipal(report, principal(role('Group::Vorstand', '9', ['layer_and_below_full'])), stamm, groups)).toBe(false);
  expect(isAllowedForPrincipal(report, principal(role('Group::Vorstand', '3', ['layer_and_below_full'])), land, groups)).toBe(false);
  expect(isAllowedForPrincipal(report, principal(role('Group::Vorstand', '1', ['layer_and_below_full'])), other, groups)).toBe(false);
});
