import {
  allowInsecureRequests,
  ClientSecretBasic,
  Configuration,
  customFetch,
  discovery,
  type DiscoveryRequestOptions,
  None,
} from "openid-client";
import { AppConfig } from "../config";
import { withBackendHost } from "./backend-host";

export async function createOidcClient(
  config: AppConfig["auth"],
): Promise<Configuration> {
  const issuerUrl = new URL(config.issuerUrl);

  const options: DiscoveryRequestOptions = {};

  // Plain-HTTP issuers only occur in local dev (see .env.local), where no
  // trusted TLS cert is available for the docker-internal Keycloak host.
  if (issuerUrl.protocol === "http:") {
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

  return discovery(
    issuerUrl,
    config.clientId,
    config.clientSecret,
    config.clientSecret ? ClientSecretBasic(config.clientSecret) : None(),
    options,
  );
}
