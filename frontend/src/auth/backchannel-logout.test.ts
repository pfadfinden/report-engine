import { Request, Response } from 'express';
import { Store } from 'express-session';
import { errors } from 'jose';
import { createBackchannelLogoutHandler } from './backchannel-logout';
import { InvalidLogoutTokenError, LogoutTokenClaims, VerifyLogoutToken } from './logout-token';
import { SessionIndex } from './session-index';

const VALID_REQUEST_BODY = { logout_token: 'signed.logout.token' };

interface RecordedResponse {
  status: number;
  headers: Record<string, string>;
  body: unknown;
}

function recordingResponse(): { res: Response; recorded: RecordedResponse } {
  const recorded: RecordedResponse = { status: 200, headers: {}, body: undefined };
  const res = {
    setHeader(name: string, value: string) {
      recorded.headers[name.toLowerCase()] = value;
      return res;
    },
    status(code: number) {
      recorded.status = code;
      return res;
    },
    json(body: unknown) {
      recorded.body = body;
      return res;
    },
    end() {
      return res;
    },
  } as unknown as Response;

  return { res, recorded };
}

/** Records what it was asked to destroy; sessions listed in `failing` report an error instead. */
function fakeStore(failing: ReadonlySet<string> = new Set()): { store: Store; destroyed: string[] } {
  const destroyed: string[] = [];
  const store = {
    destroy(sessionId: string, callback?: (err?: unknown) => void) {
      if (failing.has(sessionId)) {
        callback?.(new Error(`store unavailable for ${sessionId}`));
        return;
      }
      destroyed.push(sessionId);
      callback?.();
    },
  } as unknown as Store;

  return { store, destroyed };
}

function accepting(claims: LogoutTokenClaims = { sub: 'user-1', sid: 'kc-a' }): VerifyLogoutToken {
  return () => Promise.resolve(claims);
}

function rejecting(err: unknown): VerifyLogoutToken {
  return () => Promise.reject(err);
}

async function post(
  verify: VerifyLogoutToken,
  store: Store,
  index: SessionIndex,
  body: unknown = VALID_REQUEST_BODY,
): Promise<RecordedResponse> {
  const { res, recorded } = recordingResponse();
  const handler = createBackchannelLogoutHandler(verify, store, index);
  await handler({ body } as Request, res, jest.fn());
  return recorded;
}

test('destroys the session the logout token names and answers 200', async () => {
  const index = new SessionIndex();
  index.register('express-a', { sub: 'user-1', sid: 'kc-a' });
  index.register('express-b', { sub: 'user-1', sid: 'kc-b' });
  const { store, destroyed } = fakeStore();

  const recorded = await post(accepting(), store, index);

  expect(recorded.status).toBe(200);
  expect(recorded.headers['cache-control']).toBe('no-store');
  expect(destroyed).toEqual(['express-a']);
  // Gone from the index too, so nothing later resolves to a session that no longer exists.
  expect(index.find({ sid: 'kc-a' })).toEqual([]);
  expect(index.find({ sid: 'kc-b' })).toEqual(['express-b']);
});

test('a logout token with only a sub reaches every session that user has open', async () => {
  const index = new SessionIndex();
  index.register('express-a', { sub: 'user-1', sid: 'kc-a' });
  index.register('express-b', { sub: 'user-1', sid: 'kc-b' });
  const { store, destroyed } = fakeStore();

  const recorded = await post(accepting({ sub: 'user-1', sid: undefined }), store, index);

  expect(recorded.status).toBe(200);
  expect(destroyed.sort()).toEqual(['express-a', 'express-b']);
  expect(index.size).toBe(0);
});

test('answers 200 when no local session matches - there is nothing for the OP to retry', async () => {
  const { store, destroyed } = fakeStore();

  const recorded = await post(accepting(), store, new SessionIndex());

  expect(recorded.status).toBe(200);
  expect(destroyed).toEqual([]);
});

test('rejects a request carrying no logout_token', async () => {
  const { store } = fakeStore();

  const recorded = await post(accepting(), store, new SessionIndex(), {});

  expect(recorded.status).toBe(400);
  expect(recorded.headers['cache-control']).toBe('no-store');
});

test('rejects a logout token the verifier turns down', async () => {
  const { store } = fakeStore();
  const verify = rejecting(new InvalidLogoutTokenError('no backchannel-logout event'));

  expect((await post(verify, store, new SessionIndex())).status).toBe(400);
});

test('rejects a token that fails signature verification', async () => {
  const { store } = fakeStore();
  const verify = rejecting(new errors.JWSSignatureVerificationFailed());

  expect((await post(verify, store, new SessionIndex())).status).toBe(400);
});

test('answers 503 when the issuer JWKS is unreachable, so the OP retries instead of giving up', async () => {
  const { store } = fakeStore();
  const verify = rejecting(new errors.JWKSTimeout());

  expect((await post(verify, store, new SessionIndex())).status).toBe(503);
});

test('answers 503 and keeps the session findable when the store fails to destroy it', async () => {
  const index = new SessionIndex();
  index.register('express-a', { sub: 'user-1', sid: 'kc-a' });
  const { store } = fakeStore(new Set(['express-a']));

  const recorded = await post(accepting(), store, index);

  expect(recorded.status).toBe(503);
  expect(index.find({ sid: 'kc-a' })).toEqual(['express-a']);
});
