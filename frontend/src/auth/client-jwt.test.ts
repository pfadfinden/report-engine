import { generateKeyPairSync } from 'crypto';
import { mkdtemp, rm, writeFile } from 'fs/promises';
import { tmpdir } from 'os';
import { join } from 'path';
import { calculateJwkThumbprint, decodeProtectedHeader, importJWK, jwtVerify } from 'jose';
import { createClientJwtAuth } from './client-jwt';

const ISSUER = 'https://issuer.test/realms/bdp-login';
const CLIENT_ID = 'mv-reports';
const CLIENT_ASSERTION_TYPE = 'urn:ietf:params:oauth:client-assertion-type:jwt-bearer';

test('the served JWKS holds exactly the public key the assertions are signed with', async () => {
  const { jwks } = await createClientJwtAuth();

  expect(jwks.keys).toHaveLength(1);
  const [jwk] = jwks.keys;
  expect(jwk.kid).toEqual(await calculateJwkThumbprint(jwk));
  expect(jwk.alg).toBe('ES256');
  expect(jwk.use).toBe('sig');
  // The private half must not have leaked into what gets served publicly.
  expect(jwk).not.toHaveProperty('d');
});

test('signs a client_assertion that verifies against the published JWKS and names this client', async () => {
  const { clientAuth, jwks } = await createClientJwtAuth();
  const [jwk] = jwks.keys;

  const body = new URLSearchParams();
  await clientAuth({ issuer: ISSUER }, { client_id: CLIENT_ID }, body, new Headers());

  expect(body.get('client_id')).toBe(CLIENT_ID);
  expect(body.get('client_assertion_type')).toBe(CLIENT_ASSERTION_TYPE);

  const publicKey = await importJWK(jwk, jwk.alg);
  const { protectedHeader, payload } = await jwtVerify(body.get('client_assertion')!, publicKey);

  expect(protectedHeader.kid).toBe(jwk.kid);
  expect(payload.iss).toBe(CLIENT_ID);
  expect(payload.sub).toBe(CLIENT_ID);
  expect(payload.aud).toBe(ISSUER);
});

test('without a key file each call generates its own key - two clients never end up sharing one', async () => {
  const [first, second] = await Promise.all([createClientJwtAuth(), createClientJwtAuth()]);

  expect(first.jwks.keys[0].kid).not.toBe(second.jwks.keys[0].kid);
});

describe('key from OIDC_CLIENT_KEY_FILE', () => {
  let dir: string;

  beforeAll(async () => {
    dir = await mkdtemp(join(tmpdir(), 'client-jwt-'));
  });
  afterAll(() => rm(dir, { recursive: true, force: true }));

  const ecPem = (format: 'pkcs8' | 'sec1' = 'pkcs8') =>
    generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).privateKey.export({
      type: format,
      format: 'pem',
    }) as string;

  async function write(name: string, contents: string): Promise<string> {
    const path = join(dir, name);
    await writeFile(path, contents);
    return path;
  }

  test('the same file yields the same kid in every process and restart - what lets replicas share a JWKS', async () => {
    const path = await write('stable.pem', ecPem());

    const first = await createClientJwtAuth(path);
    const second = await createClientJwtAuth(path);

    expect(first.jwks.keys[0].kid).toBe(second.jwks.keys[0].kid);
    expect(first.jwks.keys[0]).toEqual(second.jwks.keys[0]);
    expect(first.jwks.keys[0]).not.toHaveProperty('d');
  });

  test('assertions verify against the published key, and also work for a SEC1 ("EC PRIVATE KEY") file', async () => {
    const { clientAuth, jwks } = await createClientJwtAuth(await write('sec1.pem', ecPem('sec1')));

    const body = new URLSearchParams();
    await clientAuth({ issuer: ISSUER }, { client_id: CLIENT_ID }, body, new Headers());

    const [jwk] = jwks.keys;
    const { protectedHeader } = await jwtVerify(body.get('client_assertion')!, await importJWK(jwk, jwk.alg));
    expect(protectedHeader.kid).toBe(jwk.kid);
  });

  test('with several keys the first signs and all are published (rotation)', async () => {
    const { clientAuth, jwks } = await createClientJwtAuth(await write('rotation.pem', ecPem() + '\n' + ecPem()));

    expect(jwks.keys).toHaveLength(2);
    const body = new URLSearchParams();
    await clientAuth({ issuer: ISSUER }, { client_id: CLIENT_ID }, body, new Headers());
    expect(decodeProtectedHeader(body.get('client_assertion')!).kid).toBe(jwks.keys[0].kid);
  });

  test.each([
    ['missing', async () => join(dir, 'does-not-exist.pem'), /cannot be read/],
    ['empty', async () => write('empty.pem', ''), /contains no PEM private key/],
    [
      'garbage',
      async () => write('garbage.pem', '-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----'),
      /cannot be parsed/,
    ],
    [
      'not P-256',
      async () =>
        write(
          'p384.pem',
          generateKeyPairSync('ec', { namedCurve: 'secp384r1' }).privateKey.export({
            type: 'pkcs8',
            format: 'pem',
          }) as string,
        ),
      /EC P-256/,
    ],
    [
      'RSA',
      async () =>
        write(
          'rsa.pem',
          generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey.export({
            type: 'pkcs8',
            format: 'pem',
          }) as string,
        ),
      /EC P-256/,
    ],
    [
      'duplicated',
      async () => {
        const pem = ecPem();
        return write('dup.pem', pem + pem);
      },
      /more than once/,
    ],
  ])('refuses a %s key file with a clear error', async (_name, makePath, message) => {
    await expect(createClientJwtAuth(await makePath())).rejects.toThrow(message);
  });
});
