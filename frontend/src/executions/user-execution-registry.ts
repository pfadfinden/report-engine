/**
 * Which user triggered which report execution - the only place that fact is recorded, since the
 * executor stores no owner (an executionId is just an unguessable UUID). It is what stops a
 * *different* logged-in user from viewing or downloading someone else's report.
 *
 * Keyed by the authenticated principal id, deliberately NOT by session: sessions are short-lived
 * and replaced on every login (session.regenerate), and a session timeout followed by an
 * immediate SSO re-login must not make the user's executions disappear.
 *
 * In-process like the session store (see express-app.ts); swap for a shared store before scaling
 * out. Growth is bounded three ways: entries expire after `retentionMs`, each user keeps at most
 * `maxPerUser`, and the whole registry holds at most `maxTotal` (oldest evicted first).
 */
export interface ExecutionEntry {
  // W3C trace context captured when the execution was triggered (see telemetry/trace-context.ts),
  // restored on status polls and the download so they stay in one connected trace.
  readonly traceContext: Record<string, string>;
  // What the user picked, so the auto-refreshing status page can show it and offer to re-run it
  // without re-fetching group/report metadata on every poll.
  readonly selection: ExecutionSelection;
}

export interface ExecutionSelection {
  readonly reportTitle: string;
  readonly items: ReadonlyArray<{ label: string; value: string }>;
  readonly regenerateUrl: string;
  // Descriptive filename for the downloaded file (report, group/person, datetime).
  readonly fileName?: string;
  // Where "start over" leads and what it is called; defaults to the report wizard.
  readonly newUrl?: string;
  readonly newLabel?: string;
}

export interface ExecutionRetention {
  readonly retentionMs: number;
  readonly maxPerUser: number;
  readonly maxTotal: number;
}

interface StoredEntry extends ExecutionEntry {
  readonly createdAt: number;
}

export class UserExecutionRegistry {
  // Map iteration order is insertion order, so the first key is always the oldest entry.
  private readonly byUser = new Map<string, Map<string, StoredEntry>>();
  private total = 0;

  constructor(
    private readonly limits: ExecutionRetention,
    private readonly now: () => number = Date.now,
  ) {}

  register(principalId: string, executionId: string, entry: ExecutionEntry): void {
    this.sweep();

    let own = this.byUser.get(principalId);
    if (!own) {
      own = new Map();
      this.byUser.set(principalId, own);
    }
    if (own.delete(executionId)) {
      this.total--;
    }
    own.set(executionId, { ...entry, createdAt: this.now() });
    this.total++;

    while (own.size > this.limits.maxPerUser) {
      this.removeOldest(principalId, own);
    }
    while (this.total > this.limits.maxTotal) {
      this.evictGloballyOldest();
    }
  }

  /** The entry, only if this principal owns it and it hasn't expired; undefined otherwise. */
  get(principalId: string, executionId: string): ExecutionEntry | undefined {
    const own = this.byUser.get(principalId);
    const entry = own?.get(executionId);
    if (!own || !entry) {
      return undefined;
    }
    if (this.isExpired(entry)) {
      this.remove(principalId, own, executionId);
      return undefined;
    }
    return entry;
  }

  /** Drops every expired entry. Run periodically; register() also calls it. */
  sweep(): void {
    for (const [principalId, own] of this.byUser) {
      for (const [executionId, entry] of own) {
        // Insertion order is age order, so the first live entry ends this user.
        if (!this.isExpired(entry)) {
          break;
        }
        this.remove(principalId, own, executionId);
      }
    }
  }

  /** Entries currently held - for tests and logging. */
  get size(): number {
    return this.total;
  }

  private isExpired(entry: StoredEntry): boolean {
    return this.now() - entry.createdAt >= this.limits.retentionMs;
  }

  private remove(principalId: string, own: Map<string, StoredEntry>, executionId: string): void {
    if (own.delete(executionId)) {
      this.total--;
    }
    if (own.size === 0) {
      this.byUser.delete(principalId);
    }
  }

  private removeOldest(principalId: string, own: Map<string, StoredEntry>): void {
    const oldest = own.keys().next().value;
    if (oldest !== undefined) {
      this.remove(principalId, own, oldest);
    }
  }

  private evictGloballyOldest(): void {
    let victim: { principalId: string; own: Map<string, StoredEntry>; createdAt: number } | undefined;
    for (const [principalId, own] of this.byUser) {
      const first = own.values().next().value;
      if (first && (!victim || first.createdAt < victim.createdAt)) {
        victim = { principalId, own, createdAt: first.createdAt };
      }
    }
    if (!victim) {
      this.total = 0;
      return;
    }
    this.removeOldest(victim.principalId, victim.own);
  }
}
