import { createPrivateKey, createPublicKey } from 'crypto';
import { readFile } from 'fs/promises';
import { calculateJwkThumbprint, exportJWK, generateKeyPair, importPKCS8, type JWK } from 'jose';
import { ClientAuth, PrivateKeyJwt } from 'openid-client';
import * as logger from '../telemetry/logger';

/**
 * Same choice as auth/dpop.ts and for the same reason: smallest assertion size, and the one
 * algorithm every private_key_jwt-capable server is guaranteed to accept.
 */
export const CLIENT_JWT_ALG = 'ES256';

export interface ClientJwtAuth {
  /** Passed as createOidcClient's clientAuthentication override. */
  readonly clientAuth: ClientAuth;
  /** Served at GET /.well-known/jwks.json (see express-app.ts) - the half the AS needs to fetch. */
  readonly jwks: { keys: JWK[] };
}

const PRIVATE_KEY_PEM = /-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g;

interface SigningKey {
  readonly key: CryptoKey;
  readonly publicJwk: JWK;
}

async function toSigningKey(privateKey: CryptoKey, publicJwk: JWK): Promise<SigningKey> {
  const kid = await calculateJwkThumbprint(publicJwk);
  return { key: privateKey, publicJwk: { ...publicJwk, kid, alg: CLIENT_JWT_ALG, use: 'sig' } };
}

/**
 * Reads every PEM private key (PKCS#8 or SEC1) from the file. The first signs, all are published -
 * see createClientJwtAuth. Only P-256 keys are accepted since the assertion algorithm is ES256.
 */
async function loadKeyFile(path: string): Promise<SigningKey[]> {
  const contents = await readFile(path, 'utf8').catch((err: Error) => {
    throw new Error(`OIDC_CLIENT_KEY_FILE ${path} cannot be read: ${err.message}`);
  });

  const blocks = contents.match(PRIVATE_KEY_PEM) ?? [];
  if (blocks.length === 0) {
    throw new Error(`OIDC_CLIENT_KEY_FILE ${path} contains no PEM private key`);
  }

  const keys: SigningKey[] = [];
  for (const [index, block] of blocks.entries()) {
    let nodeKey;
    try {
      nodeKey = createPrivateKey(block);
    } catch {
      // not echoing the library message: it can name the key's format, never its contents, but
      // there is nothing in it an operator needs beyond "unusable"
      throw new Error(
        `OIDC_CLIENT_KEY_FILE ${path}: key #${index + 1} cannot be parsed (encrypted keys are not supported)`,
      );
    }
    if (nodeKey.asymmetricKeyType !== 'ec' || nodeKey.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
      throw new Error(
        `OIDC_CLIENT_KEY_FILE ${path}: key #${index + 1} must be an EC P-256 key (prime256v1) for ${CLIENT_JWT_ALG}`,
      );
    }
    const pkcs8 = nodeKey.export({ type: 'pkcs8', format: 'pem' }) as string;
    const publicJwk = createPublicKey(nodeKey).export({ format: 'jwk' }) as JWK;
    keys.push(await toSigningKey(await importPKCS8(pkcs8, CLIENT_JWT_ALG), publicJwk));
  }

  const kids = keys.map((entry) => entry.publicJwk.kid);
  if (new Set(kids).size !== kids.length) {
    throw new Error(`OIDC_CLIENT_KEY_FILE ${path} contains the same key more than once`);
  }
  return keys;
}

/**
 * Builds `private_key_jwt` (RFC 7523 / OpenID Connect Core 1.0 section 9) client authentication: a
 * private key signs the `client_assertion` JWT on every token-endpoint request, its public half is
 * served as this app's own JWKS for the AS to fetch ("Use JWKS URL" on the Keycloak client).
 *
 * This is the only client authentication this app supports: there is no client secret anywhere
 * in the deployment, and the same assertion also authenticates the Identity Brokering API call in
 * auth/hitobito-roles.ts (Keycloak routes both through the same client authenticators).
 *
 * Where the key comes from is the one decision that matters for robustness:
 *
 * - `keyFile` (OIDC_CLIENT_KEY_FILE, required in production - see config.ts): one stable key,
 *   mounted as a secret and shared by every replica. The `kid` is the JWK thumbprint, so it is
 *   identical in every process and across restarts: Keycloak caches the key once and never sees
 *   an unknown `kid` again, and it does not matter which replica answers its JWKS fetch.
 *   Rotation: a file may hold several PEM keys - the FIRST signs, ALL are published, so a new key
 *   can be published everywhere before anything signs with it (README, "Key rotation").
 *
 * - No file (development only): a key pair generated at startup. Not robust beyond one process:
 *   after every restart the `kid` is new, and Keycloak re-fetches the JWKS only when it meets an
 *   unknown `kid` *and* the previous fetch is more than minTimeBetweenRequests (10 s default) old
 *   (InfinispanPublicKeyStorageProviderFactory, 26.7.0). A login inside that window, or any login
 *   against a second replica whose key the JWKS answer (from yet another replica) lacks, fails
 *   with invalid_client.
 *
 * The server only starts accepting requests after this resolved (see composition-root.ts and
 * bin/www), so no request can ever race the key's creation.
 */
export async function createClientJwtAuth(keyFile?: string): Promise<ClientJwtAuth> {
  let keys: SigningKey[];
  if (keyFile) {
    keys = await loadKeyFile(keyFile);
  } else {
    const { publicKey, privateKey } = await generateKeyPair(CLIENT_JWT_ALG);
    keys = [await toSigningKey(privateKey, await exportJWK(publicKey))];
  }

  const [signing] = keys;
  const kid = signing.publicJwk.kid!;

  logger.event('auth.client_jwt.enabled', {
    'auth.client_jwt.kid': kid,
    'auth.client_jwt.alg': CLIENT_JWT_ALG,
    'auth.client_jwt.source': keyFile ? 'file' : 'generated',
    'auth.client_jwt.published_kids': keys.map((entry) => entry.publicJwk.kid).join(','),
  });

  return {
    clientAuth: PrivateKeyJwt({ key: signing.key, kid }),
    jwks: { keys: keys.map((entry) => entry.publicJwk) },
  };
}
