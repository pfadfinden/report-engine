import 'express-session';
import { Principal } from '../domain/model/principal';

declare module 'express-session' {
  interface SessionData {
    principal?: Principal;
    idToken?: string;
    pendingAuth?: {
      state: string;
      nonce: string;
      codeVerifier: string;
      returnTo: string;
    };
    // executionIds this session itself triggered via POST /generate - an
    // executionId is otherwise just an unguessable UUID with no owner
    // recorded anywhere in the executor, so this is what actually stops a
    // *different* logged-in user's session from viewing/downloading it.
    ownedExecutionIds?: string[];
    // The W3C trace context (see telemetry/trace-context.ts) captured while triggering
    // each executionId, keyed the same way as ownedExecutionIds. The status-poll and download
    // requests for that execution are otherwise unrelated incoming HTTP requests with no
    // natural shared context of their own - restoring this before calling out to the executor
    // is what keeps trigger + every status check + the download in one connected trace.
    executionTraceContext?: Record<string, Record<string, string>>;
    // Per-session CSRF synchronizer token (see ../auth/csrf.ts) - generated once per session,
    // embedded as a hidden field in every state-changing form, and required back on submit.
    csrfToken?: string;
  }
}
