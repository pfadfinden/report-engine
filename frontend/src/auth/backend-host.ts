/**
 * Rewrites only the network destination of a URL to `backendHost`, leaving
 * everything else (path, query, and critically the original host used
 * elsewhere for issuer/identity comparisons) untouched. Used where this
 * backend can't reach a URL's own host directly — e.g. in local dev, where
 * the OIDC issuer is deliberately "localhost" (what the browser needs) but
 * this container can only reach Keycloak via host.docker.internal.
 *
 * No-op when backendHost is undefined (production, where issuer and network
 * address are the same public host).
 */
export function withBackendHost(
  url: URL,
  backendHost: string | undefined,
): URL {
  if (!backendHost) {
    return url;
  }
  const target = new URL(url);
  target.host = backendHost;
  return target;
}
