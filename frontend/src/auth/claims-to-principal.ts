import { IDToken } from "openid-client";
import { Principal, PrincipalRole } from "../domain/model/principal";

function toPrincipalRole(value: unknown): PrincipalRole | undefined {
  if (typeof value !== "object" || value === null) {
    return undefined;
  }

  const raw = value as Record<string, unknown>;
  const { group_id, group_name, role, role_class, role_name, permissions } =
    raw;

  if (
    (typeof group_id !== "string" && typeof group_id !== "number") ||
    typeof group_name !== "string" ||
    typeof role !== "string" ||
    typeof role_class !== "string" ||
    typeof role_name !== "string" ||
    !Array.isArray(permissions)
  ) {
    return undefined;
  }

  return {
    groupId: String(group_id),
    groupName: group_name,
    role,
    roleClass: role_class,
    roleName: role_name,
    permissions: permissions.filter(
      (permission): permission is string => typeof permission === "string",
    ),
  };
}

export function toPrincipalRoles(value: unknown): ReadonlyArray<PrincipalRole> {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .map(toPrincipalRole)
    .filter((role): role is PrincipalRole => role !== undefined);
}

// Roles come from a separate Hitobito userinfo call (see auth/hitobito-roles.ts),
// not from the ID token's own claims — Keycloak's built-in IdP mappers can't
// carry Hitobito's nested role/permission objects through a claim.
export function claimsToPrincipal(
  claims: IDToken,
  roles: ReadonlyArray<PrincipalRole>,
): Principal {
  return {
    id: claims.sub,
    name:
      (claims.name as string | undefined) ??
      (claims.preferred_username as string | undefined) ??
      claims.sub,
    roles,
  };
}
