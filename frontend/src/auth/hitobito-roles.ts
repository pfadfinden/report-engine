import { ClientAuth, Configuration, DPoPHandle, fetchProtectedResource } from 'openid-client';
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

/** Present when the Keycloak access token is DPoP-bound, see fetchHitobitoRoles. */
export interface BrokerDpop {
  readonly configuration: Configuration;
  readonly handle: DPoPHandle;
}

// A hung Keycloak/Hitobito call here would otherwise block login (this runs as part of the OIDC
// callback) forever.
const REQUEST_TIMEOUT_MS = 15_000;

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
 *
 * Client authentication: clientAuthentication is the exact same private_key_jwt value
 * auth/oidc-client.ts's createOidcClient uses for the main token endpoint. Keycloak's
 * IdentityBrokerService#getTokenV2 authenticates this call with AuthorizeClientUtil.authorizeClient,
 * the identical pluggable mechanism as its TokenEndpoint (26.7.0 source), so a signed
 * client_assertion in the form body works here unchanged - there is no client secret involved
 * anywhere. The client additionally needs "Allow retrieve external tokens" and the IdP alias in
 * "Allowed identity providers for external tokens" (see README, Keycloak client configuration).
 *
 * `dpop`, when given, is for a DPoP-bound Keycloak access token. The `token` form parameter is
 * still where the token travels, but the endpoint verifies it with DPoPUtil.withDPoPVerifier,
 * which rejects a DPoP-typed token unless the request ALSO carries `Authorization: DPoP <token>`
 * and a matching DPoP proof (htu = this URL, htm = POST, ath = hash of the token). Sending only
 * the form parameter - as this function used to - fails with "The access token type is DPoP but
 * Authorization Header is not DPoP". A bearer token must NOT be sent with an Authorization
 * header here, so that path is unchanged.
 */
export async function fetchHitobitoRoles(
  issuer: string,
  brokerIdpAlias: string,
  hitobitoApiUrl: string,
  clientId: string,
  clientAuthentication: ClientAuth,
  keycloakAccessToken: string,
  backendHost: string | undefined,
  dpop?: BrokerDpop,
): Promise<ReadonlyArray<PrincipalRole>> {
  const brokerTokenUrl = withBackendHost(new URL(`${issuer}/broker/${brokerIdpAlias}/token`), backendHost);

  const body = new URLSearchParams({ client_id: clientId, token: keycloakAccessToken });
  const headers = new Headers({ 'Content-Type': 'application/x-www-form-urlencoded' });
  await clientAuthentication({ issuer }, { client_id: clientId }, body, headers);

  const brokerRes = dpop
    ? await fetchProtectedResource(dpop.configuration, keycloakAccessToken, brokerTokenUrl, 'POST', body, headers, {
        DPoP: dpop.handle,
      })
    : await fetch(brokerTokenUrl, {
        method: 'POST',
        headers,
        body,
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
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
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });

  if (!userinfoRes.ok) {
    throw new Error(
      `Hitobito userinfo request to ${userinfoUrl} failed: ${userinfoRes.status} ${userinfoRes.statusText}`,
    );
  }

  const userinfo = (await userinfoRes.json()) as HitobitoUserinfoResponse;
  return toPrincipalRoles(userinfo.roles);
}
