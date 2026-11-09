import { UserExecutionRegistry, ExecutionEntry } from './user-execution-registry';

const entry = (title = 'r'): ExecutionEntry => ({
  traceContext: { traceparent: 'x' },
  selection: { reportTitle: title, items: [], regenerateUrl: '/select' },
});

function make(overrides: Partial<{ retentionMs: number; maxPerUser: number; maxTotal: number }> = {}) {
  let now = 1_000;
  const registry = new UserExecutionRegistry(
    { retentionMs: 1000, maxPerUser: 3, maxTotal: 5, ...overrides },
    () => now,
  );
  return { registry, advance: (ms: number) => (now += ms) };
}

describe('UserExecutionRegistry', () => {
  it('returns an entry only to its owner', () => {
    const { registry } = make();
    registry.register('alice', 'e1', entry('A'));
    expect(registry.get('alice', 'e1')?.selection.reportTitle).toBe('A');
    expect(registry.get('bob', 'e1')).toBeUndefined();
    expect(registry.get('alice', 'unknown')).toBeUndefined();
  });

  it('expires entries after the retention window', () => {
    const { registry, advance } = make();
    registry.register('alice', 'e1', entry());
    advance(999);
    expect(registry.get('alice', 'e1')).toBeDefined();
    advance(1);
    expect(registry.get('alice', 'e1')).toBeUndefined();
    expect(registry.size).toBe(0);
  });

  it('sweep drops expired entries and keeps live ones', () => {
    const { registry, advance } = make();
    registry.register('alice', 'old', entry());
    advance(600);
    registry.register('alice', 'new', entry());
    advance(600);
    registry.sweep();
    expect(registry.size).toBe(1);
    expect(registry.get('alice', 'new')).toBeDefined();
  });

  it('keeps at most maxPerUser entries, evicting the oldest', () => {
    const { registry } = make();
    ['a', 'b', 'c', 'd'].forEach((id) => registry.register('alice', id, entry()));
    expect(registry.get('alice', 'a')).toBeUndefined();
    expect(registry.get('alice', 'd')).toBeDefined();
    expect(registry.size).toBe(3);
  });

  it('keeps at most maxTotal entries across users, evicting the globally oldest', () => {
    const { registry, advance } = make({ maxPerUser: 10, maxTotal: 3 });
    registry.register('alice', 'a1', entry());
    advance(1);
    registry.register('bob', 'b1', entry());
    advance(1);
    registry.register('alice', 'a2', entry());
    advance(1);
    registry.register('carol', 'c1', entry());
    expect(registry.size).toBe(3);
    expect(registry.get('alice', 'a1')).toBeUndefined();
    expect(registry.get('bob', 'b1')).toBeDefined();
  });

  it('is unaffected by session lifecycle: same principal sees its executions again', () => {
    const { registry } = make();
    registry.register('alice', 'e1', entry());
    // a re-login produces a new session but the same principal id
    expect(registry.get('alice', 'e1')).toBeDefined();
  });
});
