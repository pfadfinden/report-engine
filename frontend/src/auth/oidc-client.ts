import {
  allowInsecureRequests,
  ClientAuth,
  Configuration,
  customFetch,
  discovery,
  type DiscoveryRequestOptions,
} from 'openid-client';
import { AppConfig } from '../config';
import { CLIENT_JWT_ALG } from './client-jwt';
import { withBackendHost } from './backend-host';
import * as logger from '../telemetry/logger';

export async function createOidcClient(
  config: AppConfig['auth'],
  clientAuthentication: ClientAuth,
): Promise<Configuration> {
  const issuerUrl = new URL(config.issuerUrl);

  const options: DiscoveryRequestOptions = {};

  // Plain-HTTP issuers only occur in local dev (see .env.local), where no
  // trusted TLS cert is available for the docker-internal Keycloak host.
  if (issuerUrl.protocol === 'http:') {
    options.execute = [allowInsecureRequests];
  }

  // The OIDC issuer identity is one fixed value per realm — it's baked into
  // every token's iss claim, so Keycloak can't present a different hostname
  // to this backend than it does to the browser. In local dev the issuer is
  // "localhost" (what the browser needs for the login redirect), which this
  // container can't reach as itself. When set, OIDC_BACKEND_HOST rewrites
  // only the network destination of outgoing requests to somewhere this
  // container can actually reach — the URLs used for discovery/issuer
  // validation are untouched, so they still match what Keycloak reports.
  if (config.backendHost) {
    const backendHost = config.backendHost;
    options[customFetch] = (url, init) => {
      const target = withBackendHost(new URL(url), backendHost);
      // CustomFetchOptions.body (FetchBody) isn't structurally identical to
      // DOM's BodyInit (e.g. Uint8Array vs ArrayBufferView), though the
      // runtime fetch accepts it fine — openid-client's own examples cast
      // through this same gap.
      return fetch(target, init as RequestInit);
    };
  }

  return discovery(issuerUrl, config.clientId, undefined, clientAuthentication, options);
}

/**
 * Startup checks on what the issuer's discovery document says about client authentication and
 * issuer identification.
 *
 * RFC 9207 (authorization server issuer identification) is what protects against mix-up
 * attacks: openid-client only *requires* `iss` on the authorization response when discovery says
 * `authorization_response_iss_parameter_supported` (it checks a present `iss` either way). An AS
 * that doesn't advertise it is therefore silently accepted without that check, so production
 * refuses to run against one; Keycloak advertises it unconditionally.
 */
export function assertIssuerCapabilities(configuration: Configuration, nodeEnv: string): void {
  const metadata = configuration.serverMetadata();

  // Both lists are optional in discovery (RFC 8414), so only an explicit mismatch is an error.
  const authMethods = metadata.token_endpoint_auth_methods_supported;
  if (authMethods && !authMethods.includes('private_key_jwt')) {
    throw new Error(
      `The issuer does not support private_key_jwt client authentication (token_endpoint_auth_methods_supported: ${authMethods.join(', ')})`,
    );
  }
  const authAlgs = metadata.token_endpoint_auth_signing_alg_values_supported;
  if (authAlgs && !authAlgs.includes(CLIENT_JWT_ALG)) {
    throw new Error(
      `The issuer does not accept ${CLIENT_JWT_ALG} client assertions (token_endpoint_auth_signing_alg_values_supported: ${authAlgs.join(', ')})`,
    );
  }

  if (metadata.authorization_response_iss_parameter_supported !== true) {
    const message =
      'The issuer does not advertise authorization_response_iss_parameter_supported (RFC 9207), so ' +
      'the "iss" parameter of the authorization response would not be required.';
    if (nodeEnv === 'production') {
      throw new Error(message);
    }
    logger.warn('auth.oidc.iss_parameter_unsupported', { 'auth.issuer': metadata.issuer });
  }
}
