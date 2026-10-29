import { assertSecureAuthUrls, loadConfig } from './config';

const BASE_ENV: NodeJS.ProcessEnv = {
  HITOBITO_API_URL: 'https://db.test',
  HITOBITO_API_TOKEN: 'token',
  REPORT_EXECUTION_API_URL: 'http://executor:8080',
  OIDC_ISSUER_URL: 'https://kc.test/realms/bdp-login',
  OIDC_CLIENT_ID: 'mv-reports',
  OIDC_BROKER_IDP_ALIAS: 'mv-oidc',
  AUTH_REDIRECT_URI: 'https://reports.test/auth/callback',
  AUTH_POST_LOGOUT_REDIRECT_URI: 'https://reports.test/',
  AUTH_SESSION_SECRET: 'session-secret',
};

const PRODUCTION_ENV: NodeJS.ProcessEnv = {
  ...BASE_ENV,
  NODE_ENV: 'production',
  OIDC_CLIENT_KEY_FILE: '/run/secrets/oidc_client_key',
};

const urls = (overrides: Partial<Parameters<typeof assertSecureAuthUrls>[1]> = {}) => ({
  issuerUrl: BASE_ENV.OIDC_ISSUER_URL!,
  redirectUri: BASE_ENV.AUTH_REDIRECT_URI!,
  postLogoutRedirectUri: BASE_ENV.AUTH_POST_LOGOUT_REDIRECT_URI!,
  ...overrides,
});

describe('assertSecureAuthUrls', () => {
  test('accepts an all-https production setup, including a relative post-logout URI', () => {
    expect(() => assertSecureAuthUrls('production', urls(), 'https://db.test')).not.toThrow();
    expect(() =>
      assertSecureAuthUrls('production', urls({ postLogoutRedirectUri: '/' }), 'https://db.test'),
    ).not.toThrow();
  });

  test.each([
    ['issuer', urls({ issuerUrl: 'http://kc.test/realms/x' }), 'https://db.test', /OIDC_ISSUER_URL must use https/],
    [
      'redirect',
      urls({ redirectUri: 'http://reports.test/auth/callback' }),
      'https://db.test',
      /AUTH_REDIRECT_URI must use https/,
    ],
    [
      'post-logout',
      urls({ postLogoutRedirectUri: 'http://reports.test/' }),
      'https://db.test',
      /POST_LOGOUT_REDIRECT_URI must use https/,
    ],
    ['hitobito', urls(), 'http://db.test', /HITOBITO_API_URL must use https/],
  ])('production rejects a plain-http %s URL', (_name, auth, hitobito, message) => {
    expect(() => assertSecureAuthUrls('production', auth, hitobito)).toThrow(message);
  });

  test('plain http stays possible for local development', () => {
    expect(() =>
      assertSecureAuthUrls(
        'development',
        urls({ issuerUrl: 'http://localhost:7080/realms/x', redirectUri: 'http://localhost:3000/auth/callback' }),
        'http://localhost:3001',
      ),
    ).not.toThrow();
  });

  test.each([
    ['a wildcard', 'https://reports.test/auth/*'],
    ['a fragment', 'https://reports.test/auth/callback#x'],
    ['a query string', 'https://reports.test/auth/callback?a=1'],
    ['a non-URL', '/auth/callback'],
    ['a non-web scheme', 'com.example.app:/callback'],
  ])('rejects a redirect URI that is %s, in every environment', (_name, redirectUri) => {
    expect(() => assertSecureAuthUrls('development', urls({ redirectUri }), 'https://db.test')).toThrow(
      /AUTH_REDIRECT_URI/,
    );
  });
});

test('loadConfig applies the URL checks', () => {
  expect(() => loadConfig({ ...PRODUCTION_ENV, OIDC_ISSUER_URL: 'http://kc.test/realms/x' })).toThrow(
    /OIDC_ISSUER_URL must use https/,
  );
  expect(loadConfig(PRODUCTION_ENV).auth.redirectUri).toBe(BASE_ENV.AUTH_REDIRECT_URI);
});

describe('client authentication', () => {
  test('a client secret is refused outright, not silently ignored', () => {
    expect(() => loadConfig({ ...BASE_ENV, OIDC_CLIENT_SECRET: 'leftover' })).toThrow(
      /OIDC_CLIENT_SECRET is no longer supported/,
    );
  });

  test('no client secret is needed, and the auth config has no secret or method field', () => {
    const { auth } = loadConfig(BASE_ENV);
    expect(auth).not.toHaveProperty('clientSecret');
    expect(auth).not.toHaveProperty('clientAuthMethod');
  });

  test('private_key_jwt may be named explicitly, anything else is refused', () => {
    expect(() => loadConfig({ ...BASE_ENV, OIDC_CLIENT_AUTH_METHOD: 'private_key_jwt' })).not.toThrow();
    expect(() => loadConfig({ ...BASE_ENV, OIDC_CLIENT_AUTH_METHOD: 'client_secret_basic' })).toThrow(
      /not supported: private_key_jwt is the only/,
    );
  });
});

describe('signing key', () => {
  test('production refuses to start without a key file', () => {
    expect(() => loadConfig({ ...BASE_ENV, NODE_ENV: 'production' })).toThrow(/OIDC_CLIENT_KEY_FILE is required/);
  });

  test('development may generate its own key; a file is passed through when set', () => {
    expect(loadConfig(BASE_ENV).auth.clientKeyFile).toBeUndefined();
    expect(loadConfig({ ...BASE_ENV, OIDC_CLIENT_KEY_FILE: '/k.pem' }).auth.clientKeyFile).toBe('/k.pem');
  });
});

describe('DPoP', () => {
  test('OIDC_DPOP=on cannot be combined with OIDC_BACKEND_HOST', () => {
    const env = { ...BASE_ENV, OIDC_BACKEND_HOST: 'host.docker.internal:7080' };
    expect(() => loadConfig({ ...env, OIDC_DPOP: 'on' })).toThrow(/incompatible with OIDC_BACKEND_HOST/);
    // auto simply turns DPoP off at runtime (auth/dpop.ts), off never uses it
    expect(() => loadConfig({ ...env, OIDC_DPOP: 'auto' })).not.toThrow();
    expect(() => loadConfig({ ...env, OIDC_DPOP: 'off' })).not.toThrow();
  });

  test('an unknown mode is refused', () => {
    expect(() => loadConfig({ ...BASE_ENV, OIDC_DPOP: 'maybe' })).toThrow(/OIDC_DPOP must be/);
  });
});
