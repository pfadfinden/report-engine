import { Configuration } from 'openid-client';
import { assertIssuerCapabilities } from './oidc-client';

jest.mock('../telemetry/logger');

const ISSUER = 'https://issuer.test/realms/bdp-login';

describe('assertIssuerCapabilities', () => {
  const configurationWith = (iss: boolean | undefined, extra: Record<string, unknown> = {}) =>
    ({
      serverMetadata: () => ({ issuer: ISSUER, authorization_response_iss_parameter_supported: iss, ...extra }),
    }) as unknown as Configuration;

  test('refuses an issuer that advertises client authentication methods without private_key_jwt', () => {
    expect(() =>
      assertIssuerCapabilities(
        configurationWith(true, { token_endpoint_auth_methods_supported: ['client_secret_basic'] }),
        'development',
      ),
    ).toThrow(/private_key_jwt/);
  });

  test('refuses an issuer that advertises assertion algorithms without ES256', () => {
    expect(() =>
      assertIssuerCapabilities(
        configurationWith(true, { token_endpoint_auth_signing_alg_values_supported: ['RS256'] }),
        'development',
      ),
    ).toThrow(/ES256/);
  });

  test('accepts an issuer advertising private_key_jwt with ES256, or saying nothing about either', () => {
    expect(() =>
      assertIssuerCapabilities(
        configurationWith(true, {
          token_endpoint_auth_methods_supported: ['client_secret_basic', 'private_key_jwt'],
          token_endpoint_auth_signing_alg_values_supported: ['RS256', 'ES256'],
        }),
        'production',
      ),
    ).not.toThrow();
    expect(() => assertIssuerCapabilities(configurationWith(true), 'production')).not.toThrow();
  });

  test('passes when the issuer advertises RFC 9207 support', () => {
    expect(() => assertIssuerCapabilities(configurationWith(true), 'production')).not.toThrow();
  });

  test('refuses an issuer without it in production, only warns elsewhere', () => {
    expect(() => assertIssuerCapabilities(configurationWith(undefined), 'production')).toThrow(/RFC 9207/);
    expect(() => assertIssuerCapabilities(configurationWith(false), 'development')).not.toThrow();
  });
});
