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
  }
}
