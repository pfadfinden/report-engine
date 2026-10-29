import { Configuration, DPoPHandle, getDPoPHandle, randomDPoPKeyPair } from 'openid-client';
import * as logger from '../telemetry/logger';

/**
 * How hard this app insists on DPoP (RFC 9449) sender-constrained tokens.
 *
 * "auto" is the default because the README promises any OIDC-compliant provider works: an issuer
 * that says nothing about DPoP in its discovery document simply gets the plain bearer flow, and
 * one that advertises it gets sender-constrained tokens without a config change. "on" turns that
 * into a startup requirement for deployments that want the guarantee rather than the best effort.
 */
export type DpopMode = 'auto' | 'on' | 'off';

/**
 * Proof-signing algorithms this client is willing to use, best first. ES256 leads because it
 * produces the smallest proofs and is the one algorithm every DPoP implementation supports;
 * the RSA variants are only here for servers that advertise nothing better. Anything a server
 * advertises that isn't on this list is deliberately not used.
 */
const PROOF_ALGORITHMS = ['ES256', 'ES384', 'ES512', 'PS256', 'PS384', 'PS512', 'RS256'];

/**
 * Builds this app's one DPoP handle, generating its key pair once at startup rather than per
 * login: the private key never leaves this server process either way, so - unlike a browser SPA,
 * which is why openid-client's own example key-per-session note targets that case - a single
 * per-process key carries the same guarantee against a stolen access token being replayed
 * elsewhere, without a key having to survive the redirect between /auth/login and /auth/callback.
 *
 * Whether the AS actually ends up sender-constraining the token is still its own decision (RFC
 * 9449 section 5): what this app controls is only whether it *offers* a proof. That outcome shows
 * up in the token response's token_type ("DPoP" vs "Bearer"), which auth-router.ts logs on every
 * login.
 */
export async function createDpopHandle(
  configuration: Configuration,
  mode: DpopMode,
  backendHost: string | undefined,
): Promise<DPoPHandle | undefined> {
  if (mode === 'off') {
    return undefined;
  }

  if (backendHost) {
    // A DPoP proof's `htu` claim is bound to the literal request Host the AS actually receives
    // (RFC 9449 section 4.3) - not to the issuer identity, which is the only thing
    // OIDC_BACKEND_HOST is designed to leave untouched while it rewrites the network destination
    // (see backend-host.ts). The proof this app signs is computed from the issuer-facing token
    // endpoint URL, but the AS validates it against the backendHost address the request actually
    // arrived on, so the two can never agree and every proof gets rejected - this is exactly the
    // failure DPoP was found to cause in this local-dev setup (Keycloak logs it as "DPoP HTTP URL
    // mismatch"). Production never sets OIDC_BACKEND_HOST, so this only ever applies in dev.
    if (mode === 'on') {
      throw new Error(
        'OIDC_DPOP=on is incompatible with OIDC_BACKEND_HOST: a DPoP proof is bound to the literal ' +
          'request host the AS receives, which OIDC_BACKEND_HOST deliberately changes without also ' +
          'changing the issuer-facing URL the proof is computed from, so the AS will reject every proof.',
      );
    }
    logger.warn('auth.dpop.unavailable', { 'auth.dpop.reason': 'oidc_backend_host_incompatible' });
    return undefined;
  }

  const advertised = configuration.serverMetadata().dpop_signing_alg_values_supported ?? [];
  const alg = PROOF_ALGORITHMS.find((candidate) => advertised.includes(candidate));

  if (!alg) {
    if (mode === 'on') {
      throw new Error(
        'OIDC_DPOP=on, but the issuer advertises no dpop_signing_alg_values_supported algorithm this ' +
          `client can sign proofs with (advertised: ${advertised.join(', ') || '<none>'}; ` +
          `usable here: ${PROOF_ALGORITHMS.join(', ')})`,
      );
    }
    logger.warn('auth.dpop.unavailable', { 'auth.dpop.advertised': advertised.join(',') });
    return undefined;
  }

  logger.event('auth.dpop.enabled', { 'auth.dpop.alg': alg });
  const keyPair = await randomDPoPKeyPair(alg);
  return getDPoPHandle(configuration, keyPair);
}
