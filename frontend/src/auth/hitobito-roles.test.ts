import { ClientAuth, Configuration, customFetch, getDPoPHandle, PrivateKeyJwt, randomDPoPKeyPair } from 'openid-client';
import { decodeJwt, decodeProtectedHeader, generateKeyPair } from 'jose';
import { createHash } from 'crypto';
import { fetchHitobitoRoles } from './hitobito-roles';

function jsonResponse(body: unknown, ok = true, status = ok ? 200 : 500) {
  return {
    ok,
    status,
    statusText: ok ? 'OK' : 'Internal Server Error',
    json: () => Promise.resolve(body),
  };
}

const ISSUER = 'http://localhost:7080/realms/bdp-login';
const IDP_ALIAS = 'mv-oidc';
const HITOBITO_URL = 'http://host.docker.internal:3000';
const CLIENT_ID = 'mv-reports';
const KEYCLOAK_ACCESS_TOKEN = 'kc-access-token';

// What createOidcClient's main flow authenticates with (auth/client-jwt.ts) - the broker call must
// use the very same value, and there is no secret-based alternative.
let CLIENT_AUTH: ClientAuth;

beforeAll(async () => {
  const { privateKey } = await generateKeyPair('ES256');
  CLIENT_AUTH = PrivateKeyJwt({ key: privateKey, kid: 'k1' });
});

let fetchMock: jest.Mock;

beforeEach(() => {
  fetchMock = jest.fn();
  global.fetch = fetchMock as unknown as typeof fetch;
});

test('posts the broker token request and fetches userinfo with the returned access token', async () => {
  fetchMock.mockResolvedValueOnce(jsonResponse({ access_token: 'hitobito-token' })).mockResolvedValueOnce(
    jsonResponse({
      roles: [
        {
          group_id: 1,
          group_name: 'Bundesamt',
          role: 'Group::Foo',
          role_class: 'Group::Foo',
          role_name: 'Foo',
          permissions: ['admin'],
        },
      ],
    }),
  );

  const roles = await fetchHitobitoRoles(
    ISSUER,
    IDP_ALIAS,
    HITOBITO_URL,
    CLIENT_ID,
    CLIENT_AUTH,
    KEYCLOAK_ACCESS_TOKEN,
    undefined,
  );

  expect(roles).toEqual([
    {
      groupId: '1',
      groupName: 'Bundesamt',
      role: 'Group::Foo',
      roleClass: 'Group::Foo',
      roleName: 'Foo',
      permissions: ['admin'],
    },
  ]);

  expect(fetchMock).toHaveBeenCalledTimes(2);

  const [brokerUrl, brokerInit] = fetchMock.mock.calls[0];
  expect(String(brokerUrl)).toBe(`${ISSUER}/broker/${IDP_ALIAS}/token`);
  expect(brokerInit.method).toBe('POST');
  const brokerBody = new URLSearchParams(brokerInit.body as URLSearchParams);
  expect(brokerBody.get('client_id')).toBe(CLIENT_ID);
  expect(brokerBody.get('token')).toBe(KEYCLOAK_ACCESS_TOKEN);
  expect(brokerBody.get('client_assertion_type')).toBe('urn:ietf:params:oauth:client-assertion-type:jwt-bearer');
  expect(brokerBody.get('client_assertion')).toEqual(expect.any(String));
  expect(brokerBody.has('client_secret')).toBe(false);
  // a bearer token travels in the form only; an Authorization header would make Keycloak expect DPoP
  expect(new Headers(brokerInit.headers).has('authorization')).toBe(false);

  const [userinfoUrl, userinfoInit] = fetchMock.mock.calls[1];
  expect(String(userinfoUrl)).toBe(`${HITOBITO_URL}/oauth/userinfo`);
  expect(userinfoInit.headers.Authorization).toBe('Bearer hitobito-token');
});

// openid-client refuses a plain-http protected-resource request unless discovery allowed it, which is
// the TLS-only behaviour wanted outside local dev.
const TLS_ISSUER = 'https://kc.test/realms/bdp-login';

test('sends a DPoP-bound access token with Authorization: DPoP and a proof bound to the broker URL', async () => {
  const configuration = new Configuration(
    { issuer: TLS_ISSUER, token_endpoint: `${TLS_ISSUER}/protocol/openid-connect/token` },
    CLIENT_ID,
  );
  const handle = getDPoPHandle(configuration, await randomDPoPKeyPair('ES256'));
  const brokerFetch = jest.fn().mockResolvedValue(
    new Response(JSON.stringify({ access_token: 'hitobito-token' }), {
      headers: { 'content-type': 'application/json' },
    }),
  );
  (configuration as unknown as Record<symbol, unknown>)[customFetch] = brokerFetch;
  fetchMock.mockResolvedValueOnce(jsonResponse({ roles: [] }));

  await fetchHitobitoRoles(
    TLS_ISSUER,
    IDP_ALIAS,
    HITOBITO_URL,
    CLIENT_ID,
    CLIENT_AUTH,
    KEYCLOAK_ACCESS_TOKEN,
    undefined,
    {
      configuration,
      handle,
    },
  );

  // the broker request went through the DPoP-aware path, not the plain global fetch
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(String(fetchMock.mock.calls[0][0])).toBe(`${HITOBITO_URL}/oauth/userinfo`);
  expect(brokerFetch).toHaveBeenCalledTimes(1);
  const [url, init] = brokerFetch.mock.calls[0];
  expect(String(url)).toBe(`${TLS_ISSUER}/broker/${IDP_ALIAS}/token`);
  const headers = new Headers(init.headers);
  expect(headers.get('authorization')).toBe(`DPoP ${KEYCLOAK_ACCESS_TOKEN}`);

  const proof = headers.get('dpop')!;
  expect(decodeProtectedHeader(proof).typ).toBe('dpop+jwt');
  expect(decodeJwt(proof)).toMatchObject({
    htm: 'POST',
    htu: `${TLS_ISSUER}/broker/${IDP_ALIAS}/token`,
    ath: createHash('sha256').update(KEYCLOAK_ACCESS_TOKEN).digest('base64url'),
  });

  const body = new URLSearchParams(init.body as URLSearchParams);
  expect(body.get('token')).toBe(KEYCLOAK_ACCESS_TOKEN);
  expect(body.get('client_assertion')).toEqual(expect.any(String));
  expect(body.has('client_secret')).toBe(false);
});

test('rewrites the broker token request to backendHost, not the userinfo request', async () => {
  fetchMock
    .mockResolvedValueOnce(jsonResponse({ access_token: 'hitobito-token' }))
    .mockResolvedValueOnce(jsonResponse({ roles: [] }));

  await fetchHitobitoRoles(
    ISSUER,
    IDP_ALIAS,
    HITOBITO_URL,
    CLIENT_ID,
    CLIENT_AUTH,
    KEYCLOAK_ACCESS_TOKEN,
    'host.docker.internal:7080',
  );

  const [brokerUrl] = fetchMock.mock.calls[0];
  expect(String(brokerUrl)).toBe('http://host.docker.internal:7080/realms/bdp-login/broker/mv-oidc/token');

  const [userinfoUrl] = fetchMock.mock.calls[1];
  expect(String(userinfoUrl)).toBe(`${HITOBITO_URL}/oauth/userinfo`);
});

test('throws when the broker token endpoint returns an error', async () => {
  fetchMock.mockResolvedValueOnce(
    jsonResponse({ error: 'invalid_client', error_description: 'no access' }, false, 403),
  );

  await expect(
    fetchHitobitoRoles(ISSUER, IDP_ALIAS, HITOBITO_URL, CLIENT_ID, CLIENT_AUTH, KEYCLOAK_ACCESS_TOKEN, undefined),
  ).rejects.toThrow(/invalid_client/);

  expect(fetchMock).toHaveBeenCalledTimes(1);
});

test('throws when the broker response is ok but has no access_token', async () => {
  fetchMock.mockResolvedValueOnce(jsonResponse({}));

  await expect(
    fetchHitobitoRoles(ISSUER, IDP_ALIAS, HITOBITO_URL, CLIENT_ID, CLIENT_AUTH, KEYCLOAK_ACCESS_TOKEN, undefined),
  ).rejects.toThrow();
});

test('throws when the Hitobito userinfo request fails', async () => {
  fetchMock
    .mockResolvedValueOnce(jsonResponse({ access_token: 'hitobito-token' }))
    .mockResolvedValueOnce(jsonResponse({}, false, 500));

  await expect(
    fetchHitobitoRoles(ISSUER, IDP_ALIAS, HITOBITO_URL, CLIENT_ID, CLIENT_AUTH, KEYCLOAK_ACCESS_TOKEN, undefined),
  ).rejects.toThrow(/userinfo/);
});
