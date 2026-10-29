/**
 * Reverse index from the OP's notion of a session — the ID token's `sub` and `sid` — to this
 * app's own express-session ids.
 *
 * A back-channel logout arrives as a bare server-to-server POST: no cookie, no browser, nothing
 * that identifies a local session. Only the logout token's `sub`/`sid` do, and neither is
 * something an express-session store can be queried by, so the mapping has to be kept here.
 *
 * In-process, like the MemoryStore it indexes (see express-app.ts). Both carry the same caveat
 * and should be replaced together by something shared (e.g. redis) before scaling out: with more
 * than one instance, a logout token only reaches the instance the load balancer picked, and only
 * that instance's sessions get destroyed.
 */
export interface SessionOwner {
  readonly sub: string;
  readonly sid: string | undefined;
}

export class SessionIndex {
  private readonly owners = new Map<string, SessionOwner>();
  private readonly bySub = new Map<string, Set<string>>();
  private readonly bySid = new Map<string, Set<string>>();

  register(sessionId: string, owner: SessionOwner): void {
    // Re-registering a session id replaces whatever it pointed at, so a second login on the same
    // id can't leave the previous user's `sub` still resolving to it.
    this.unregister(sessionId);

    this.owners.set(sessionId, owner);
    addTo(this.bySub, owner.sub, sessionId);
    if (owner.sid) {
      addTo(this.bySid, owner.sid, sessionId);
    }
  }

  unregister(sessionId: string): void {
    const owner = this.owners.get(sessionId);
    if (!owner) {
      return;
    }

    this.owners.delete(sessionId);
    removeFrom(this.bySub, owner.sub, sessionId);
    if (owner.sid) {
      removeFrom(this.bySid, owner.sid, sessionId);
    }
  }

  /**
   * The sessions a logout token targets. Per Back-Channel Logout 1.0 section 2.6 a `sid` names
   * one specific OP session, so it wins whenever it's present (Keycloak sends both); `sub` on its
   * own means every session this app is holding for that user.
   */
  find(target: { readonly sub?: string; readonly sid?: string }): string[] {
    if (target.sid) {
      return [...(this.bySid.get(target.sid) ?? [])];
    }
    if (target.sub) {
      return [...(this.bySub.get(target.sub) ?? [])];
    }
    return [];
  }

  /** Entries currently held — exposed for tests and for logging index size alongside store size. */
  get size(): number {
    return this.owners.size;
  }
}

function addTo(index: Map<string, Set<string>>, key: string, sessionId: string): void {
  const existing = index.get(key);
  if (existing) {
    existing.add(sessionId);
    return;
  }
  index.set(key, new Set([sessionId]));
}

function removeFrom(index: Map<string, Set<string>>, key: string, sessionId: string): void {
  const existing = index.get(key);
  if (!existing) {
    return;
  }
  existing.delete(sessionId);
  if (existing.size === 0) {
    index.delete(key);
  }
}
