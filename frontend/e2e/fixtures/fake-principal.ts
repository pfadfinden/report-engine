import { Principal } from '../../src/domain/model/principal';

// Stands in for a real OIDC login (see server.ts's /__e2e-login route) - the e2e stack has no
// Keycloak/Hitobito to authenticate against, so this is seeded directly into the session instead
// of being produced by a real login callback.
export const E2E_PRINCIPAL: Principal = {
  id: 'e2e-user-1',
  name: 'E2E Test User',
  roles: [],
};
