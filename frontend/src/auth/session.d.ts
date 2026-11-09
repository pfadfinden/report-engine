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
    // Per-execution ownership, trace context and selections live in UserExecutionRegistry
    // (keyed by principal id), not here: the session is short-lived and replaced on login.
  }
}
