/**
 * Query parameters that carry a credential or a one-time value tied to a login in flight. The
 * authorization response (`/auth/callback?code=...&state=...`) is the main case: the code is
 * single-use and PKCE-bound, but it still has no business ending up in access logs or telemetry
 * (OAuth 2.1 draft, section 7.5 "credentials in logs").
 */
const SENSITIVE_PARAMETERS = new Set([
  'code',
  'state',
  'session_state',
  'id_token',
  'access_token',
  'refresh_token',
  'token',
  'id_token_hint',
  'logout_token',
  'client_assertion',
]);

const REDACTED = 'REDACTED';

/** Replaces the value of every sensitive query parameter in a request URL (path or absolute). */
export function redactSensitiveQuery(url: string): string {
  const queryStart = url.indexOf('?');
  if (queryStart === -1) {
    return url;
  }

  const fragmentStart = url.indexOf('#', queryStart);
  const queryEnd = fragmentStart === -1 ? url.length : fragmentStart;
  const redacted = url
    .slice(queryStart + 1, queryEnd)
    .split('&')
    .map((pair) => {
      const separator = pair.indexOf('=');
      const name = separator === -1 ? pair : pair.slice(0, separator);
      let decodedName = name;
      try {
        decodedName = decodeURIComponent(name);
      } catch {
        // malformed percent-encoding: compare the raw name, which simply won't match
      }
      return separator !== -1 && SENSITIVE_PARAMETERS.has(decodedName.toLowerCase()) ? `${name}=${REDACTED}` : pair;
    })
    .join('&');

  return url.slice(0, queryStart + 1) + redacted + url.slice(queryEnd);
}
