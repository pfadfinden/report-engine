import { redactSensitiveQuery } from './url-redaction';

test('redacts the authorization response parameters but keeps iss and the path', () => {
  expect(redactSensitiveQuery('/auth/callback?code=abc&state=xyz&iss=https%3A%2F%2Fkc.test&session_state=s1')).toBe(
    '/auth/callback?code=REDACTED&state=REDACTED&iss=https%3A%2F%2Fkc.test&session_state=REDACTED',
  );
});

test('redacts token parameters regardless of name case or percent-encoded names', () => {
  expect(redactSensitiveQuery('/x?ID_TOKEN=a&%61ccess_token=b&keep=1')).toBe(
    '/x?ID_TOKEN=REDACTED&%61ccess_token=REDACTED&keep=1',
  );
});

test('leaves URLs without a query, and fragments, untouched', () => {
  expect(redactSensitiveQuery('/healthz')).toBe('/healthz');
  expect(redactSensitiveQuery('/x?a=1#code=zzz')).toBe('/x?a=1#code=zzz');
});

test('a malformed percent-encoding does not throw', () => {
  expect(redactSensitiveQuery('/x?%E0%A4%A=1&code=c')).toBe('/x?%E0%A4%A=1&code=REDACTED');
});
