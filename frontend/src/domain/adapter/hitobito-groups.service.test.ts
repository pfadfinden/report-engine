import { HitobitoGroupsService } from "./hitobito-groups.service";
import { Principal, PrincipalRole } from "../model/principal";

function role(overrides: Partial<PrincipalRole>): PrincipalRole {
  return {
    groupId: "1",
    groupName: "Bundesamt",
    role: "Group::Bundesgeschaeftsstelle::Bundesgeschaeftsfuehrung",
    roleClass: "Group::Bundesgeschaeftsstelle::Bundesgeschaeftsfuehrung",
    roleName: "Bundesgeschäftsführer*in",
    permissions: [],
    ...overrides,
  };
}

function principal(roles: PrincipalRole[]): Principal {
  return { id: "abc-123", name: "Jane Doe", roles };
}

function groupResource(
  id: string,
  name: string,
  type: string,
  layerGroupId: string | null = id,
  parentId: string | null = null,
) {
  return {
    id,
    type: "groups",
    attributes: {
      name,
      type,
      parent_id: parentId,
      layer_group_id: layerGroupId,
    },
  };
}

function jsonResponse(body: unknown, ok = true) {
  return {
    ok,
    status: ok ? 200 : 500,
    statusText: ok ? "OK" : "Internal Server Error",
    json: () => Promise.resolve(body),
  };
}

const API_URL = "https://hitobito.example.org";
const API_TOKEN = "test-token";

let fetchMock: jest.Mock;

beforeEach(() => {
  fetchMock = jest.fn();
  global.fetch = fetchMock as unknown as typeof fetch;
});

test("returns no groups and makes no request when the principal has no roles", async () => {
  const sut = new HitobitoGroupsService(API_URL, API_TOKEN);

  const groups = await sut.findFor(principal([]));

  expect(groups).toEqual([]);
  expect(fetchMock).not.toHaveBeenCalled();
});

test("resolves the type for a group referenced by a role without subtree access", async () => {
  fetchMock.mockResolvedValueOnce(
    jsonResponse({
      data: [groupResource("1", "Bundesamt", "Group::Bundesamt")],
    }),
  );

  const sut = new HitobitoGroupsService(API_URL, API_TOKEN);
  const groups = await sut.findFor(
    principal([role({ groupId: "1", permissions: ["contact_data"] })]),
  );

  expect(groups).toEqual([
    { id: "1", name: "Bundesamt", type: "Group::Bundesamt", parentId: null },
  ]);
  expect(fetchMock).toHaveBeenCalledTimes(1);

  const requestedUrl = new URL(fetchMock.mock.calls[0][0] as string);
  expect(requestedUrl.searchParams.get("filter[id][eq]")).toBe("1");

  const headers = fetchMock.mock.calls[0][1].headers;
  expect(headers["X-TOKEN"]).toBe(API_TOKEN);
});

test("expands a role's own layer group into its full subtree when it grants layer_and_below access", async () => {
  // role's own group, which is itself a layer (layerGroupId defaults to its own id)
  fetchMock.mockResolvedValueOnce(
    jsonResponse({ data: [groupResource("1", "Bund", "Group::Bund")] }),
  );
  // first BFS level: direct children of group 1
  fetchMock.mockResolvedValueOnce(
    jsonResponse({
      data: [
        groupResource("2", "Region A", "Group::Region", "2", "1"),
        groupResource("3", "Region B", "Group::Region", "3", "1"),
      ],
    }),
  );
  // second BFS level: children of groups 2 and 3
  fetchMock.mockResolvedValueOnce(
    jsonResponse({
      data: [groupResource("4", "Stamm X", "Group::Stamm", "2", "2")],
    }),
  );
  // third BFS level: no more children -> terminates the loop
  fetchMock.mockResolvedValueOnce(jsonResponse({ data: [] }));

  const sut = new HitobitoGroupsService(API_URL, API_TOKEN);
  const groups = await sut.findFor(
    principal([role({ groupId: "1", permissions: ["layer_and_below_full"] })]),
  );

  // group 1 is only fetched once (as the role's own group) since it's
  // already known to be its own layer root — no redundant re-fetch.
  expect(fetchMock).toHaveBeenCalledTimes(4);
  expect(groups).toEqual([
    { id: "1", name: "Bund", type: "Group::Bund", parentId: null },
    { id: "2", name: "Region A", type: "Group::Region", parentId: "1" },
    { id: "3", name: "Region B", type: "Group::Region", parentId: "1" },
    { id: "4", name: "Stamm X", type: "Group::Stamm", parentId: "2" },
  ]);

  const firstLevelUrl = new URL(fetchMock.mock.calls[1][0] as string);
  expect(firstLevelUrl.searchParams.get("filter[parent_id][eq]")).toBe("1");

  const secondLevelUrl = new URL(fetchMock.mock.calls[2][0] as string);
  expect(secondLevelUrl.searchParams.get("filter[parent_id][eq]")).toBe("2,3");
});

test("expands to the role's enclosing layer (not the role's own group) when the role is on a non-layer group", async () => {
  // role's own group: a non-layer office group, nested under layer "1"
  fetchMock.mockResolvedValueOnce(
    jsonResponse({
      data: [
        groupResource(
          "779",
          "Bundesamt",
          "Group::Bundesgeschaeftsstelle",
          "1",
          "1",
        ),
      ],
    }),
  );
  // the enclosing layer itself, fetched separately since it wasn't already known
  fetchMock.mockResolvedValueOnce(
    jsonResponse({ data: [groupResource("1", "Bund", "Group::Bund")] }),
  );
  // BFS from the layer (1), not from 779, which has no children of its own
  fetchMock.mockResolvedValueOnce(
    jsonResponse({
      data: [groupResource("2", "Region A", "Group::Region", "2", "1")],
    }),
  );
  fetchMock.mockResolvedValueOnce(jsonResponse({ data: [] }));

  const sut = new HitobitoGroupsService(API_URL, API_TOKEN);
  const groups = await sut.findFor(
    principal([
      role({ groupId: "779", permissions: ["layer_and_below_full"] }),
    ]),
  );

  expect(groups).toEqual([
    {
      id: "779",
      name: "Bundesamt",
      type: "Group::Bundesgeschaeftsstelle",
      parentId: "1",
    },
    { id: "1", name: "Bund", type: "Group::Bund", parentId: null },
    { id: "2", name: "Region A", type: "Group::Region", parentId: "1" },
  ]);

  const layerFetchUrl = new URL(fetchMock.mock.calls[1][0] as string);
  expect(layerFetchUrl.searchParams.get("filter[id][eq]")).toBe("1");

  const subtreeUrl = new URL(fetchMock.mock.calls[2][0] as string);
  expect(subtreeUrl.searchParams.get("filter[parent_id][eq]")).toBe("1");
});

test("follows pagination links and deduplicates groups shared by multiple roles", async () => {
  fetchMock.mockResolvedValueOnce(
    jsonResponse({
      data: [groupResource("1", "Group One", "Group::Foo")],
      links: { next: `${API_URL}/api/groups?page=2` },
    }),
  );
  fetchMock.mockResolvedValueOnce(
    jsonResponse({ data: [groupResource("2", "Group Two", "Group::Foo")] }),
  );

  const sut = new HitobitoGroupsService(API_URL, API_TOKEN);
  const groups = await sut.findFor(
    principal([
      role({ groupId: "1", permissions: ["contact_data"] }),
      role({ groupId: "2", permissions: ["contact_data"] }),
    ]),
  );

  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(groups).toEqual([
    { id: "1", name: "Group One", type: "Group::Foo", parentId: null },
    { id: "2", name: "Group Two", type: "Group::Foo", parentId: null },
  ]);
});

test("throws when the Hitobito API responds with an error status", async () => {
  fetchMock.mockResolvedValueOnce(jsonResponse({}, false));

  const sut = new HitobitoGroupsService(API_URL, API_TOKEN);

  await expect(
    sut.findFor(principal([role({ groupId: "1" })])),
  ).rejects.toThrow(/500/);
});
