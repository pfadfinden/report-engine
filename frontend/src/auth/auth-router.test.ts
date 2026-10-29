import type { AddressInfo } from 'net';
import type { Server } from 'http';
import { authorizationCodeGrant, Configuration, PrivateKeyJwt } from 'openid-client';
import { generateKeyPair } from 'jose';
import { createAuthRouter } from './auth-router';
import { fetchHitobitoRoles } from './hitobito-roles';
import { SessionIndex } from './session-index';
import { AppConfig } from '../config';

jest.mock('openid-client', () => ({
  ...jest.requireActual('openid-client'),
  authorizationCodeGrant: jest.fn(),
}));
jest.mock('./hitobito-roles', () => ({ fetchHitobitoRoles: jest.fn() }));
jest.mock('../telemetry/logger');

const express = require('express');

const ISSUER = 'https://issuer.test/realms/bdp-login';
const REDIRECT_URI = 'https://reports.test/auth/callback';

const AUTH_CONFIG: AppConfig['auth'] = {
  issuerUrl: ISSUER,
  backendHost: undefined,
  clientId: 'mv-reports',
  redirectUri: REDIRECT_URI,
  postLogoutRedirectUri: 'https://reports.test/',
  sessionSecret: 'session-secret',
  dpop: 'off',
  clientKeyFile: undefined,
  brokerIdpAlias: 'mv-oidc',
};

type FakeSession = Record<string, unknown> & {
  regenerate: (cb: (err?: unknown) => void) => void;
  save: (cb: (err?: unknown) => void) => void;
  destroy: (cb: (err?: unknown) => void) => void;
};

function newSession(): FakeSession {
  const session: FakeSession = {
    regenerate(cb) {
      for (const key of Object.keys(session)) {
        if (typeof session[key] !== 'function') delete session[key];
      }
      cb();
    },
    save: (cb) => cb(),
    destroy: (cb) => cb(),
  };
  return session;
}

let server: Server;
let session: FakeSession;
let baseUrl: string;
let clientAuth: Awaited<ReturnType<typeof PrivateKeyJwt>>;
const sessionIndex = { register: jest.fn(), unregister: jest.fn() } as unknown as SessionIndex;

beforeAll(async () => {
  const configuration = new Configuration(
    {
      issuer: ISSUER,
      authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`,
      token_endpoint: `${ISSUER}/protocol/openid-connect/token`,
      end_session_endpoint: `${ISSUER}/protocol/openid-connect/logout`,
    },
    AUTH_CONFIG.clientId,
  );
  const { privateKey } = await generateKeyPair('ES256');
  clientAuth = PrivateKeyJwt({ key: privateKey, kid: 'k1' });

  const app = express();
  app.use((req: any, _res: unknown, next: () => void) => {
    req.session = session;
    req.sessionID = 'sid-1';
    next();
  });
  app.use(
    '/auth',
    createAuthRouter(configuration, AUTH_CONFIG, 'https://hitobito.test', sessionIndex, undefined, clientAuth),
  );
  app.use((err: Error, _req: unknown, res: any, _next: unknown) => res.status(500).send(err.message));

  await new Promise<void>((resolve) => {
    server = app.listen(0, '127.0.0.1', resolve);
  });
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterAll(() => new Promise<void>((resolve) => server.close(() => resolve())));

beforeEach(() => {
  session = newSession();
  jest.clearAllMocks();
});

const get = (path: string) => fetch(`${baseUrl}${path}`, { redirect: 'manual' });

test('/login sends an authorization-code request with S256 PKCE, state, nonce and the exact redirect_uri', async () => {
  const res = await get('/auth/login?returnTo=/reports');

  expect(res.status).toBe(302);
  const location = new URL(res.headers.get('location')!);
  expect(`${location.origin}${location.pathname}`).toBe(`${ISSUER}/protocol/openid-connect/auth`);
  expect(location.searchParams.get('response_type')).toBe('code');
  expect(location.searchParams.get('client_id')).toBe('mv-reports');
  expect(location.searchParams.get('redirect_uri')).toBe(REDIRECT_URI);
  expect(location.searchParams.get('code_challenge_method')).toBe('S256');
  expect(location.searchParams.get('scope')).toBe('openid profile email');

  const pending = session.pendingAuth as { state: string; nonce: string; codeVerifier: string; returnTo: string };
  expect(location.searchParams.get('state')).toBe(pending.state);
  expect(location.searchParams.get('nonce')).toBe(pending.nonce);
  // the challenge is derived from, and never equal to, the verifier that stays server-side
  expect(location.searchParams.get('code_challenge')).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(location.searchParams.get('code_challenge')).not.toBe(pending.codeVerifier);
  expect(pending.returnTo).toBe('/reports');
  expect(location.search).not.toContain(pending.codeVerifier);
});

test('/login refuses to redirect back to anything but a local path', async () => {
  await get('/auth/login?returnTo=//evil.test');
  expect((session.pendingAuth as { returnTo: string }).returnTo).toBe('/');
});

test('/callback without a login in flight starts over at /auth/login', async () => {
  const res = await get('/auth/callback?code=c&state=s');
  expect(res.headers.get('location')).toBe('/auth/login');
  expect(authorizationCodeGrant).not.toHaveBeenCalled();
});

test('/callback verifies state, nonce, PKCE verifier and the exact registered redirect_uri', async () => {
  session.pendingAuth = { state: 'st', nonce: 'no', codeVerifier: 'cv', returnTo: '/after' };
  (authorizationCodeGrant as jest.Mock).mockResolvedValue({
    access_token: 'at',
    id_token: 'idt',
    token_type: 'bearer',
    claims: () => ({ sub: 'u1', sid: 'kc-sid', preferred_username: 'u' }),
  });
  (fetchHitobitoRoles as jest.Mock).mockResolvedValue([]);

  const res = await get('/auth/callback?code=the-code&state=st&iss=' + encodeURIComponent(ISSUER));

  expect(res.status).toBe(302);
  expect(res.headers.get('location')).toBe('/after');
  const [, currentUrl, checks] = (authorizationCodeGrant as jest.Mock).mock.calls[0];
  expect(String(currentUrl)).toBe(`${REDIRECT_URI}?code=the-code&state=st&iss=${encodeURIComponent(ISSUER)}`);
  expect(checks).toEqual({ pkceCodeVerifier: 'cv', expectedState: 'st', expectedNonce: 'no' });
  // not DPoP-bound (token_type bearer), so no proof material is handed to the broker call
  expect((fetchHitobitoRoles as jest.Mock).mock.calls[0][7]).toBeUndefined();
  // only the ID token and principal are kept; the access token is used once and dropped
  expect(session.idToken).toBe('idt');
  expect(JSON.stringify(session)).not.toContain('"at"');
  expect(session.pendingAuth).toBeUndefined();
});

test('a failed /callback burns the pending login: replaying it starts over instead of retrying', async () => {
  session.pendingAuth = { state: 'st', nonce: 'no', codeVerifier: 'cv', returnTo: '/' };
  (authorizationCodeGrant as jest.Mock).mockRejectedValue(
    new Error('unexpected "iss" (issuer) response parameter value'),
  );

  const failed = await get('/auth/callback?code=c&state=st&iss=https%3A%2F%2Fevil.test');
  expect(failed.status).toBe(500);
  expect(session.pendingAuth).toBeUndefined();

  const replay = await get('/auth/callback?code=c&state=st');
  expect(replay.headers.get('location')).toBe('/auth/login');
  expect(authorizationCodeGrant).toHaveBeenCalledTimes(1);
});

test('/logout ends the IdP session with the ID token hint and the registered post-logout URI', async () => {
  session.idToken = 'idt';
  const res = await get('/auth/logout');
  const location = new URL(res.headers.get('location')!);
  expect(location.pathname).toBe('/realms/bdp-login/protocol/openid-connect/logout');
  expect(location.searchParams.get('id_token_hint')).toBe('idt');
  expect(location.searchParams.get('post_logout_redirect_uri')).toBe('https://reports.test/');
});
