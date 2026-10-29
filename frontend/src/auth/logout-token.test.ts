import { generateKeyPair, SignJWT } from 'jose';
import { createLogoutTokenVerifier, InvalidLogoutTokenError, VerifyLogoutToken } from './logout-token';

const ISSUER = 'https://issuer.test/realms/bdp-login';
const CLIENT_ID = 'mv-reports';
const BACKCHANNEL_LOGOUT_EVENT = 'http://schemas.openid.net/event/backchannel-logout';
const SOME_OTHER_EVENT = 'http://schemas.openid.net/event/something-else';

type KeyPair = Awaited<ReturnType<typeof generateKeyPair>>;

let keyPair: KeyPair;
let otherKeyPair: KeyPair;
let verify: VerifyLogoutToken;

beforeAll(async () => {
  keyPair = await generateKeyPair('RS256');
  otherKeyPair = await generateKeyPair('RS256');
  verify = createLogoutTokenVerifier({
    key: keyPair.publicKey,
    issuer: ISSUER,
    audience: CLIENT_ID,
    algorithms: ['RS256'],
  });
});

/** A logout token exactly as Keycloak sends one, with `overrides` applied on top. */
function sign(
  overrides: Record<string, unknown> = {},
  options: { issuer?: string; audience?: string; issuedAt?: number; privateKey?: KeyPair['privateKey'] } = {},
): Promise<string> {
  return new SignJWT({
    jti: 'logout-token-1',
    events: { [BACKCHANNEL_LOGOUT_EVENT]: {} },
    sub: 'user-sub',
    sid: 'kc-session-id',
    ...overrides,
  })
    .setProtectedHeader({ alg: 'RS256', typ: 'logout+jwt' })
    .setIssuer(options.issuer ?? ISSUER)
    .setAudience(options.audience ?? CLIENT_ID)
    .setIssuedAt(options.issuedAt)
    .sign(options.privateKey ?? keyPair.privateKey);
}

test('accepts a well-formed logout token and returns who it is about', async () => {
  await expect(verify(await sign())).resolves.toEqual({ sub: 'user-sub', sid: 'kc-session-id' });
});

test('accepts a logout token carrying only a sid', async () => {
  await expect(verify(await sign({ sub: undefined }))).resolves.toEqual({ sub: undefined, sid: 'kc-session-id' });
});

test('accepts a logout token carrying only a sub', async () => {
  await expect(verify(await sign({ sid: undefined }))).resolves.toEqual({ sub: 'user-sub', sid: undefined });
});

test('rejects a token whose events claim does not declare a backchannel logout', async () => {
  await expect(verify(await sign({ events: { [SOME_OTHER_EVENT]: {} } }))).rejects.toThrow(InvalidLogoutTokenError);
});

test('rejects an ID token replayed here - it carries a nonce', async () => {
  await expect(verify(await sign({ nonce: 'n-0S6_WzA2Mj' }))).rejects.toThrow(InvalidLogoutTokenError);
});

test('rejects a token that identifies neither a session nor a user', async () => {
  await expect(verify(await sign({ sub: undefined, sid: undefined }))).rejects.toThrow(InvalidLogoutTokenError);
});

test('rejects a token signed by a key that does not belong to the issuer', async () => {
  await expect(verify(await sign({}, { privateKey: otherKeyPair.privateKey }))).rejects.toThrow();
});

test('rejects a token from a different issuer', async () => {
  await expect(verify(await sign({}, { issuer: 'https://evil.test/realms/bdp-login' }))).rejects.toThrow();
});

test('rejects a token issued for a different client', async () => {
  await expect(verify(await sign({}, { audience: 'some-other-client' }))).rejects.toThrow();
});

test('rejects a token without a jti', async () => {
  await expect(verify(await sign({ jti: undefined }))).rejects.toThrow();
});

test('rejects a token whose iat is too old to still be in flight', async () => {
  const tenMinutesAgo = Math.floor(Date.now() / 1000) - 600;

  await expect(verify(await sign({}, { issuedAt: tenMinutesAgo }))).rejects.toThrow();
});

test('rejects an unsecured token, however well-formed its claims are', async () => {
  const unsecured = [
    Buffer.from(JSON.stringify({ alg: 'none' })).toString('base64url'),
    Buffer.from(
      JSON.stringify({
        iss: ISSUER,
        aud: CLIENT_ID,
        iat: Math.floor(Date.now() / 1000),
        jti: 'logout-token-1',
        events: { [BACKCHANNEL_LOGOUT_EVENT]: {} },
        sub: 'user-sub',
      }),
    ).toString('base64url'),
    '',
  ].join('.');

  await expect(verify(unsecured)).rejects.toThrow();
});
