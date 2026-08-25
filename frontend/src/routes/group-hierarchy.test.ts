import { Group } from "../domain/model/group";
import { sortGroupsHierarchically } from "./group-hierarchy";

function group(
  id: string,
  name: string,
  parentId: string | null,
  type = "Group::Foo",
): Group {
  return { id, name, type, parentId };
}

test("returns an empty list for no groups", () => {
  expect(sortGroupsHierarchically([])).toEqual([]);
});

test("orders parents before children, depth-first", () => {
  const groups = [
    group("2", "Region A", "1"),
    group("4", "Stamm X", "2"),
    group("1", "Bund", null),
    group("3", "Region B", "1"),
  ];

  const options = sortGroupsHierarchically(groups);

  expect(options.map((o) => o.group.id)).toEqual(["1", "2", "4", "3"]);
});

test("computes depth relative to ancestors present in the list", () => {
  const groups = [
    group("1", "Bund", null),
    group("2", "Region A", "1"),
    group("4", "Stamm X", "2"),
  ];

  const options = sortGroupsHierarchically(groups);

  expect(options.map((o) => o.depth)).toEqual([0, 1, 2]);
});

test("sorts siblings alphabetically, umlaut-aware", () => {
  const groups = [
    group("3", "Zebra", null),
    group("1", "Äpfel", null),
    group("2", "Banane", null),
  ];

  const options = sortGroupsHierarchically(groups);

  expect(options.map((o) => o.group.name)).toEqual([
    "Äpfel",
    "Banane",
    "Zebra",
  ]);
});

test("treats a group whose parent is missing from the list as a root", () => {
  // principal has access to "Stamm X" but not to its parent "Region A"
  const groups = [group("1", "Bund", null), group("4", "Stamm X", "2")];

  const options = sortGroupsHierarchically(groups);

  expect(options.map((o) => ({ id: o.group.id, depth: o.depth }))).toEqual([
    { id: "1", depth: 0 },
    { id: "4", depth: 0 },
  ]);
});

test("indents the label to match depth and marks nested entries", () => {
  const groups = [group("1", "Bund", null), group("2", "Region A", "1")];

  const options = sortGroupsHierarchically(groups);

  expect(options[0].label).toBe("Bund");
  expect(options[1].label).toBe("  – Region A");
});

test("places same-named groups from different branches directly under their own parent, not adjacent to each other", () => {
  const groups = [
    group("1", "Bund", null),
    group("2", "Bayern", "1"),
    group("3", "Hessen", "1"),
    group("4", "Vorstand", "2"),
    group("5", "Vorstand", "3"),
  ];

  const options = sortGroupsHierarchically(groups);

  // Both "Vorstand" entries render identically (same depth, same text) -
  // disambiguation comes from list position, immediately under their own
  // parent, not from the label text itself.
  expect(options.map((o) => o.group.id)).toEqual(["1", "2", "4", "3", "5"]);
  expect(options[2].label).toBe(options[4].label);
});
