import { PrincipalRole } from '../domain/model/principal';
import { toPrincipalRoles } from './claims-to-principal';
import { withBackendHost } from './backend-host';

interface BrokerTokenResponse {
  access_token?: string;
  error?: string;
  error_description?: string;
}

interface HitobitoUserinfoResponse {
  roles?: unknown;
}

/**
 * Fetches the principal's roles directly from Hitobito, bypassing Keycloak's
 * own token claims entirely: Keycloak's built-in identity-provider mappers
 * can only carry scalar/string claims into a session note, so they can't
 * faithfully deliver Hitobito's "roles" claim (a JSON array of role objects).
 *
 * Uses Keycloak's Identity Brokering API V2 (POST {issuer}/broker/{idp-alias}/token)
 * to retrieve Hitobito's own access token for the current session, then calls
 * Hitobito's userinfo endpoint directly with it — the same endpoint and claim
 * shape already used elsewhere, just fetched without Keycloak's mapper pipeline
 * in between. https://www.keycloak.org/docs/26.7.0/server_development/#_identity-brokering-apis
 */
export async function fetchHitobitoRoles(
  issuer: string,
  brokerIdpAlias: string,
  hitobitoApiUrl: string,
  clientId: string,
  clientSecret: string,
  keycloakAccessToken: string,
  backendHost: string | undefined,
): Promise<ReadonlyArray<PrincipalRole>> {
  const brokerTokenUrl = withBackendHost(new URL(`${issuer}/broker/${brokerIdpAlias}/token`), backendHost);

  const brokerRes = await fetch(brokerTokenUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      client_id: clientId,
      client_secret: clientSecret,
      token: keycloakAccessToken,
    }),
  });

  const brokerBody = (await brokerRes.json()) as BrokerTokenResponse;
  if (!brokerRes.ok || brokerBody.error || !brokerBody.access_token) {
    throw new Error(
      `Keycloak broker token request to ${brokerTokenUrl} failed: ${brokerRes.status} ` +
        `${brokerBody.error ?? ''} ${brokerBody.error_description ?? ''}`.trim(),
    );
  }

  const userinfoUrl = new URL('/oauth/userinfo', hitobitoApiUrl);
  const userinfoRes = await fetch(userinfoUrl, {
    headers: { Authorization: `Bearer ${brokerBody.access_token}` },
  });

  if (!userinfoRes.ok) {
    throw new Error(
      `Hitobito userinfo request to ${userinfoUrl} failed: ${userinfoRes.status} ${userinfoRes.statusText}`,
    );
  }

  const userinfo = (await userinfoRes.json()) as HitobitoUserinfoResponse;
  return toPrincipalRoles(userinfo.roles);
}
