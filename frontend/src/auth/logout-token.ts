import { createRemoteJWKSet, customFetch, jwtVerify, type JWTVerifyGetKey, type JWTVerifyOptions } from 'jose';
import { Configuration } from 'openid-client';
import { AppConfig } from '../config';
import { withBackendHost } from './backend-host';

/** The one `events` member that makes a JWT a logout token (Back-Channel Logout 1.0, section 2.4). */
const BACKCHANNEL_LOGOUT_EVENT = 'http://schemas.openid.net/event/backchannel-logout';

/**
 * Widest gap we accept between a logout token being issued and it arriving here. The spec only
 * requires `iat` to be "recent"; every second beyond that widens the window in which a captured
 * logout token could be replayed to knock the user out of a session they've since started again.
 */
const MAX_TOKEN_AGE = '5 minutes';
const CLOCK_TOLERANCE = '30 seconds';

/** Who a validated logout token is about — at least one of the two is always present. */
export interface LogoutTokenClaims {
  readonly sub: string | undefined;
  readonly sid: string | undefined;
}

export type VerifyLogoutToken = (logoutToken: string) => Promise<LogoutTokenClaims>;

/**
 * The token itself is malformed/unacceptable, as opposed to us being unable to check it — the
 * caller answers the two cases with different status codes (see auth/backchannel-logout.ts).
 */
export class InvalidLogoutTokenError extends Error {}

export interface LogoutTokenVerifierOptions {
  /** Verification key, or a resolver such as a remote JWKS. */
  readonly key: Parameters<typeof jwtVerify>[1];
  readonly issuer: string;
  readonly audience: string;
  readonly algorithms?: JWTVerifyOptions['algorithms'];
}

export function createLogoutTokenVerifier(options: LogoutTokenVerifierOptions): VerifyLogoutToken {
  return async function verifyLogoutToken(logoutToken: string): Promise<LogoutTokenClaims> {
    const { payload } = await jwtVerify(logoutToken, options.key, {
      issuer: options.issuer,
      audience: options.audience,
      algorithms: options.algorithms,
      requiredClaims: ['iss', 'aud', 'iat', 'jti', 'events'],
      maxTokenAge: MAX_TOKEN_AGE,
      clockTolerance: CLOCK_TOLERANCE,
    });

    // OpenID Connect Back-Channel Logout 1.0, section 2.6 ("Logout Token Validation"). The
    // signature and the iss/aud/iat checks are jwtVerify's job above; what's left is everything
    // that distinguishes a *logout* token from any other JWT the same issuer signed for us —
    // without these an ID token replayed to this endpoint would happily log the user out.
    const events = payload.events;
    const declaresLogout =
      typeof events === 'object' && events !== null && !Array.isArray(events) && BACKCHANNEL_LOGOUT_EVENT in events;
    if (!declaresLogout) {
      throw new InvalidLogoutTokenError('logout token has no backchannel-logout member in its "events" claim');
    }

    // A nonce is what makes an ID token an ID token; its presence here means we were handed the
    // wrong kind of token.
    if ('nonce' in payload) {
      throw new InvalidLogoutTokenError('logout token must not contain a "nonce" claim');
    }

    const sub = typeof payload.sub === 'string' ? payload.sub : undefined;
    const sid = typeof payload.sid === 'string' ? payload.sid : undefined;
    if (!sub && !sid) {
      throw new InvalidLogoutTokenError('logout token contains neither a "sub" nor a "sid" claim');
    }

    return { sub, sid };
  };
}

/**
 * Only replay protection is left out: the spec makes `jti` tracking OPTIONAL, and logging a
 * session out is idempotent, so the worst a replayed token achieves is ending a session the user
 * started within {@link MAX_TOKEN_AGE} of the original logout.
 */
export function createIssuerLogoutTokenVerifier(
  configuration: Configuration,
  config: AppConfig['auth'],
): VerifyLogoutToken {
  const metadata = configuration.serverMetadata();

  if (!metadata.jwks_uri) {
    throw new Error(`OIDC issuer ${metadata.issuer} advertises no jwks_uri, so logout tokens cannot be verified`);
  }

  const jwks: JWTVerifyGetKey = createRemoteJWKSet(new URL(metadata.jwks_uri), {
    // Same reason as in auth/oidc-client.ts: in local dev the issuer's own hostname isn't one
    // this container can reach, and only the network destination gets rewritten.
    [customFetch]: (url, init) => fetch(withBackendHost(new URL(url), config.backendHost), init),
  });

  return createLogoutTokenVerifier({
    key: jwks,
    issuer: metadata.issuer,
    audience: config.clientId,
    // Back-Channel Logout 1.0 section 2.2 has logout tokens signed the same way ID tokens are, so
    // the issuer's advertised ID token algorithms are the right allow-list. Symmetric and
    // unsecured algorithms are dropped: a JWKS only ever yields asymmetric public keys, and
    // accepting an HMAC alg alongside them is the classic key-confusion foothold.
    algorithms: asymmetricAlgorithms(metadata.id_token_signing_alg_values_supported),
  });
}

function asymmetricAlgorithms(advertised: string[] | undefined): JWTVerifyOptions['algorithms'] {
  const usable = (advertised ?? []).filter((alg) => alg !== 'none' && !alg.startsWith('HS'));
  // Undefined rather than an empty list: an issuer that advertises nothing leaves jose to accept
  // whatever the resolved JWKS key itself supports, which is still asymmetric-only.
  return usable.length > 0 ? (usable as JWTVerifyOptions['algorithms']) : undefined;
}
