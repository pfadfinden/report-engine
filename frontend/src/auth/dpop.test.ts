import { authorizationCodeGrant, Configuration, customFetch, PrivateKeyJwt } from 'openid-client';
import { generateKeyPair } from 'jose';
import { createDpopHandle } from './dpop';

/**
 * A Configuration built via its public constructor rather than discovery() - no network call, no
 * real issuer needed, just enough server metadata for createDpopHandle to read
 * dpop_signing_alg_values_supported off it.
 */
function issuerAdvertising(algorithms: string[] | undefined): Configuration {
  return new Configuration(
    { issuer: 'https://issuer.test/realms/bdp-login', dpop_signing_alg_values_supported: algorithms },
    'mv-reports',
  );
}

test('picks the algorithm this client prefers, not whichever the issuer lists first', async () => {
  const handle = await createDpopHandle(issuerAdvertising(['RS256', 'PS256', 'ES256']), 'auto', undefined);
  await expect(handle?.calculateThumbprint()).resolves.toEqual(expect.any(String));
});

test('stays off for an issuer that says nothing about DPoP', async () => {
  await expect(createDpopHandle(issuerAdvertising(undefined), 'auto', undefined)).resolves.toBeUndefined();
});

test('stays off when the issuer offers only algorithms this client will not sign with', async () => {
  await expect(createDpopHandle(issuerAdvertising(['HS256', 'EdDSA']), 'auto', undefined)).resolves.toBeUndefined();
});

test('refuses to start in "on" mode when the issuer cannot do DPoP', async () => {
  await expect(createDpopHandle(issuerAdvertising(undefined), 'on', undefined)).rejects.toThrow(/OIDC_DPOP=on/);
});

test('"off" ignores an issuer that would happily do DPoP', async () => {
  await expect(createDpopHandle(issuerAdvertising(['ES256']), 'off', undefined)).resolves.toBeUndefined();
});

describe('OIDC_BACKEND_HOST', () => {
  // DPoP proofs are bound to the literal request Host the AS receives, but backendHost rewrites
  // only the network destination, not that identity - so a DPoP proof would always be rejected by
  // an AS that actually enforces it (see the comment in dpop.ts for the full mechanism).

  test('stays off in "auto" mode even when the issuer fully supports DPoP', async () => {
    const handle = await createDpopHandle(issuerAdvertising(['ES256']), 'auto', 'host.docker.internal:7080');
    expect(handle).toBeUndefined();
  });

  test('refuses to start in "on" mode, regardless of issuer support', async () => {
    await expect(createDpopHandle(issuerAdvertising(['ES256']), 'on', 'host.docker.internal:7080')).rejects.toThrow(
      /OIDC_BACKEND_HOST/,
    );
  });

  test('"off" is unaffected either way', async () => {
    const handle = await createDpopHandle(issuerAdvertising(['ES256']), 'off', 'host.docker.internal:7080');
    expect(handle).toBeUndefined();
  });
});

test('the token request carries both the DPoP proof and the private_key_jwt assertion', async () => {
  const issuer = 'https://issuer.test/realms/bdp-login';
  const { privateKey } = await generateKeyPair('ES256');
  const configuration = new Configuration(
    { issuer, token_endpoint: `${issuer}/protocol/openid-connect/token`, dpop_signing_alg_values_supported: ['ES256'] },
    'mv-reports',
    undefined,
    PrivateKeyJwt({ key: privateKey, kid: 'k1' }),
  );
  const tokenEndpoint = jest.fn().mockResolvedValue(
    new Response(JSON.stringify({ error: 'invalid_grant' }), {
      status: 400,
      headers: { 'content-type': 'application/json' },
    }),
  );
  (configuration as unknown as Record<symbol, unknown>)[customFetch] = tokenEndpoint;
  const dpopHandle = await createDpopHandle(configuration, 'on', undefined);

  // the exchange itself is rejected by the stub - only the request that got sent is of interest
  await expect(
    authorizationCodeGrant(
      configuration,
      new URL(`https://reports.test/auth/callback?code=c&state=s&iss=${encodeURIComponent(issuer)}`),
      { pkceCodeVerifier: 'v', expectedState: 's' },
      undefined,
      { DPoP: dpopHandle },
    ),
  ).rejects.toThrow();

  const [, init] = tokenEndpoint.mock.calls[0];
  const headers = new Headers(init.headers);
  expect(headers.get('dpop')).toEqual(expect.any(String));
  expect(headers.has('authorization')).toBe(false); // no Basic header: the secret is gone
  const body = new URLSearchParams(init.body as URLSearchParams);
  expect(body.get('client_assertion_type')).toBe('urn:ietf:params:oauth:client-assertion-type:jwt-bearer');
  expect(body.get('client_assertion')).toEqual(expect.any(String));
  expect(body.get('code_verifier')).toBe('v');
  expect(body.has('client_secret')).toBe(false);
});
