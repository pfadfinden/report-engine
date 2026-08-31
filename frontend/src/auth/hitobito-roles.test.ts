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
const CLIENT_SECRET = 'test-secret';
const KEYCLOAK_ACCESS_TOKEN = 'kc-access-token';

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
    CLIENT_SECRET,
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
  expect(String(brokerInit.body)).toBe(
    new URLSearchParams({
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      token: KEYCLOAK_ACCESS_TOKEN,
    }).toString(),
  );

  const [userinfoUrl, userinfoInit] = fetchMock.mock.calls[1];
  expect(String(userinfoUrl)).toBe(`${HITOBITO_URL}/oauth/userinfo`);
  expect(userinfoInit.headers.Authorization).toBe('Bearer hitobito-token');
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
    CLIENT_SECRET,
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
    fetchHitobitoRoles(ISSUER, IDP_ALIAS, HITOBITO_URL, CLIENT_ID, CLIENT_SECRET, KEYCLOAK_ACCESS_TOKEN, undefined),
  ).rejects.toThrow(/invalid_client/);

  expect(fetchMock).toHaveBeenCalledTimes(1);
});

test('throws when the broker response is ok but has no access_token', async () => {
  fetchMock.mockResolvedValueOnce(jsonResponse({}));

  await expect(
    fetchHitobitoRoles(ISSUER, IDP_ALIAS, HITOBITO_URL, CLIENT_ID, CLIENT_SECRET, KEYCLOAK_ACCESS_TOKEN, undefined),
  ).rejects.toThrow();
});

test('throws when the Hitobito userinfo request fails', async () => {
  fetchMock
    .mockResolvedValueOnce(jsonResponse({ access_token: 'hitobito-token' }))
    .mockResolvedValueOnce(jsonResponse({}, false, 500));

  await expect(
    fetchHitobitoRoles(ISSUER, IDP_ALIAS, HITOBITO_URL, CLIENT_ID, CLIENT_SECRET, KEYCLOAK_ACCESS_TOKEN, undefined),
  ).rejects.toThrow(/userinfo/);
});
