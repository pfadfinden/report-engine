import { SessionIndex } from './session-index';

let index: SessionIndex;

beforeEach(() => {
  index = new SessionIndex();
});

test('a sid names exactly one session, even when the same user has several', () => {
  index.register('express-a', { sub: 'user-1', sid: 'kc-a' });
  index.register('express-b', { sub: 'user-1', sid: 'kc-b' });

  expect(index.find({ sub: 'user-1', sid: 'kc-b' })).toEqual(['express-b']);
});

test('a sub alone reaches every session that user has open', () => {
  index.register('express-a', { sub: 'user-1', sid: 'kc-a' });
  index.register('express-b', { sub: 'user-1', sid: 'kc-b' });
  index.register('express-c', { sub: 'user-2', sid: 'kc-c' });

  expect(index.find({ sub: 'user-1' }).sort()).toEqual(['express-a', 'express-b']);
});

test('finds sessions of issuers that send no sid', () => {
  index.register('express-a', { sub: 'user-1', sid: undefined });

  expect(index.find({ sub: 'user-1' })).toEqual(['express-a']);
});

test('unregistering removes a session from both lookups', () => {
  index.register('express-a', { sub: 'user-1', sid: 'kc-a' });
  index.unregister('express-a');

  expect(index.find({ sid: 'kc-a' })).toEqual([]);
  expect(index.find({ sub: 'user-1' })).toEqual([]);
  expect(index.size).toBe(0);
});

test('re-registering a session id drops the previous owner, so a stale sub cannot reach it', () => {
  index.register('express-a', { sub: 'user-1', sid: 'kc-a' });
  index.register('express-a', { sub: 'user-2', sid: 'kc-b' });

  expect(index.find({ sub: 'user-1' })).toEqual([]);
  expect(index.find({ sid: 'kc-a' })).toEqual([]);
  expect(index.find({ sub: 'user-2' })).toEqual(['express-a']);
  expect(index.size).toBe(1);
});

test('unknown targets and empty targets find nothing rather than throwing', () => {
  index.register('express-a', { sub: 'user-1', sid: 'kc-a' });

  expect(index.find({ sid: 'kc-unknown' })).toEqual([]);
  expect(index.find({ sub: 'user-unknown' })).toEqual([]);
  expect(index.find({})).toEqual([]);
});
