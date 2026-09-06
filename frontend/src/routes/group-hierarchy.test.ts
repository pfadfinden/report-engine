import { Group } from '../domain/model/group';
import { sortGroupsHierarchically } from './group-hierarchy';

function group(id: string, name: string, parentId: string | null, type = 'Group::Foo'): Group {
  return { id, name, type, parentId };
}

test('returns an empty list for no groups', () => {
  expect(sortGroupsHierarchically([])).toEqual([]);
});

test('orders parents before children, depth-first', () => {
  const groups = [
    group('2', 'Region A', '1'),
    group('4', 'Stamm X', '2'),
    group('1', 'Bund', null),
    group('3', 'Region B', '1'),
  ];

  const options = sortGroupsHierarchically(groups);

  expect(options.map((o) => o.group.id)).toEqual(['1', '2', '4', '3']);
});

test('computes depth relative to ancestors present in the list', () => {
  const groups = [group('1', 'Bund', null), group('2', 'Region A', '1'), group('4', 'Stamm X', '2')];

  const options = sortGroupsHierarchically(groups);

  expect(options.map((o) => o.depth)).toEqual([0, 1, 2]);
});

test('sorts siblings alphabetically, umlaut-aware', () => {
  const groups = [group('3', 'Zebra', null), group('1', 'Äpfel', null), group('2', 'Banane', null)];

  const options = sortGroupsHierarchically(groups);

  expect(options.map((o) => o.group.name)).toEqual(['Äpfel', 'Banane', 'Zebra']);
});

test('treats a group whose parent is missing from the list as a root', () => {
  // principal has access to "Stamm X" but not to its parent "Region A"
  const groups = [group('1', 'Bund', null), group('4', 'Stamm X', '2')];

  const options = sortGroupsHierarchically(groups);

  expect(options.map((o) => ({ id: o.group.id, depth: o.depth }))).toEqual([
    { id: '1', depth: 0 },
    { id: '4', depth: 0 },
  ]);
});

test('indents the label to match depth and marks nested entries', () => {
  const groups = [group('1', 'Bund', null), group('2', 'Region A', '1')];

  const options = sortGroupsHierarchically(groups);

  expect(options[0].label).toBe('Bund');
  expect(options[1].label).toBe('  – Region A');
});

test('places same-named groups from different branches directly under their own parent, not adjacent to each other', () => {
  const groups = [
    group('1', 'Bund', null),
    group('2', 'Bayern', '1'),
    group('3', 'Hessen', '1'),
    group('4', 'Vorstand', '2'),
    group('5', 'Vorstand', '3'),
  ];

  const options = sortGroupsHierarchically(groups);

  expect(options.map((o) => o.group.id)).toEqual(['1', '2', '4', '3', '5']);
});

test('disambiguates same-named groups with their parent name, so a filtered/searched list can still tell them apart', () => {
  const groups = [
    group('1', 'Bund', null),
    group('2', 'Bayern', '1'),
    group('3', 'Hessen', '1'),
    group('4', 'Vorstand', '2'),
    group('5', 'Vorstand', '3'),
  ];

  const options = sortGroupsHierarchically(groups);

  expect(options[2].label).toBe('  – Vorstand (Bayern)');
  expect(options[4].label).toBe('  – Vorstand (Hessen)');
});

test('does not disambiguate group names that are already unique', () => {
  const groups = [group('1', 'Bund', null), group('2', 'Region A', '1')];

  const options = sortGroupsHierarchically(groups);

  expect(options[1].label).toBe('  – Region A');
});

test('skips a hidden intermediate ancestor (e.g. a group type filtered out of the list) instead of orphaning its descendants at depth 0', () => {
  // "Region A" (id 2) is a real ancestor but was filtered out of `groups` -
  // e.g. its type has no report - while it and its descendants are still
  // both present in the unfiltered `allGroups`.
  const allGroups = [group('1', 'Bund', null), group('2', 'Region A', '1'), group('4', 'Stamm X', '2')];
  const groups = [allGroups[0], allGroups[2]];

  const options = sortGroupsHierarchically(groups, allGroups);

  expect(options.map((o) => ({ id: o.group.id, depth: o.depth }))).toEqual([
    { id: '1', depth: 0 },
    { id: '4', depth: 1 },
  ]);
});

test('groups siblings under the same hidden ancestor together, sorted, rather than treating them as unrelated roots', () => {
  const allGroups = [
    group('1', 'Bund', null),
    group('2', 'Region A', '1'),
    group('5', 'Stamm Z', '2'),
    group('4', 'Stamm A', '2'),
  ];
  const groups = [allGroups[0], allGroups[2], allGroups[3]];

  const options = sortGroupsHierarchically(groups, allGroups);

  expect(options.map((o) => ({ id: o.group.id, depth: o.depth }))).toEqual([
    { id: '1', depth: 0 },
    { id: '4', depth: 1 },
    { id: '5', depth: 1 },
  ]);
});

test('disambiguates using the real immediate-parent name even when that parent is itself hidden from the list', () => {
  const allGroups = [
    group('1', 'Bund', null),
    group('2', 'Bezirk Nord', '1'),
    group('3', 'Bezirk Süd', '1'),
    group('4', 'Vorstand', '2'),
    group('5', 'Vorstand', '3'),
  ];
  const groups = [allGroups[0], allGroups[3], allGroups[4]];

  const options = sortGroupsHierarchically(groups, allGroups);

  expect(options.find((o) => o.group.id === '4')?.label).toBe('  – Vorstand (Bezirk Nord)');
  expect(options.find((o) => o.group.id === '5')?.label).toBe('  – Vorstand (Bezirk Süd)');
});
