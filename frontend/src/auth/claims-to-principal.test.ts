import { IDToken } from "openid-client";
import { claimsToPrincipal, toPrincipalRoles } from "./claims-to-principal";

function claims(overrides: Partial<IDToken>): IDToken {
  return {
    sub: "abc-123",
    iss: "https://idp.example.org",
    aud: "mv-reports-frontend",
    exp: 0,
    iat: 0,
    ...overrides,
  };
}

test("uses the name claim when present", () => {
  const principal = claimsToPrincipal(
    claims({ name: "Jane Doe", preferred_username: "jane.doe" }),
    [],
  );
  expect(principal).toEqual({ id: "abc-123", name: "Jane Doe", roles: [] });
});

test("falls back to preferred_username when name is missing", () => {
  const principal = claimsToPrincipal(
    claims({ preferred_username: "jane.doe" }),
    [],
  );
  expect(principal).toEqual({ id: "abc-123", name: "jane.doe", roles: [] });
});

test("falls back to sub when neither name nor preferred_username are present", () => {
  const principal = claimsToPrincipal(claims({}), []);
  expect(principal).toEqual({ id: "abc-123", name: "abc-123", roles: [] });
});

test("passes the given roles through unchanged", () => {
  const roles = [
    {
      groupId: "779",
      groupName: "Bundesamt",
      role: "Group::Bundesgeschaeftsstelle::Bundesgeschaeftsfuehrung",
      roleClass: "Group::Bundesgeschaeftsstelle::Bundesgeschaeftsfuehrung",
      roleName: "Bundesgeschäftsführer*in",
      permissions: ["layer_and_below_full", "admin"],
    },
  ];

  const principal = claimsToPrincipal(claims({}), roles);

  expect(principal.roles).toEqual(roles);
});

test("toPrincipalRoles converts field names to camelCase", () => {
  const roles = toPrincipalRoles([
    {
      group_id: 779,
      group_name: "Bundesamt",
      role: "Group::Bundesgeschaeftsstelle::Bundesgeschaeftsfuehrung",
      role_class: "Group::Bundesgeschaeftsstelle::Bundesgeschaeftsfuehrung",
      role_name: "Bundesgeschäftsführer*in",
      permissions: [
        "layer_and_below_full",
        "admin",
        "contact_data",
        "finance",
        "assign_restricted_fee_kinds",
        "create_membership_roles",
      ],
    },
  ]);

  expect(roles).toEqual([
    {
      groupId: "779",
      groupName: "Bundesamt",
      role: "Group::Bundesgeschaeftsstelle::Bundesgeschaeftsfuehrung",
      roleClass: "Group::Bundesgeschaeftsstelle::Bundesgeschaeftsfuehrung",
      roleName: "Bundesgeschäftsführer*in",
      permissions: [
        "layer_and_below_full",
        "admin",
        "contact_data",
        "finance",
        "assign_restricted_fee_kinds",
        "create_membership_roles",
      ],
    },
  ]);
});

test("toPrincipalRoles drops malformed role entries and entries missing required fields", () => {
  const roles = toPrincipalRoles([
    { group_id: 1, group_name: "Ok" }, // missing role/role_class/role_name/permissions
    "not-an-object",
    null,
    {
      group_id: 2,
      group_name: "Also Ok",
      role: "Group::Foo",
      role_class: "Group::Foo",
      role_name: "Foo",
      permissions: ["admin"],
    },
  ]);

  expect(roles).toEqual([
    {
      groupId: "2",
      groupName: "Also Ok",
      role: "Group::Foo",
      roleClass: "Group::Foo",
      roleName: "Foo",
      permissions: ["admin"],
    },
  ]);
});

test("toPrincipalRoles returns an empty array for non-array input", () => {
  expect(toPrincipalRoles(undefined)).toEqual([]);
  expect(toPrincipalRoles("not-an-array")).toEqual([]);
});
