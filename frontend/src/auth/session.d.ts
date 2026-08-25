import "express-session";
import { Principal } from "../domain/model/principal";

declare module "express-session" {
  interface SessionData {
    principal?: Principal;
    idToken?: string;
    pendingAuth?: {
      state: string;
      nonce: string;
      codeVerifier: string;
      returnTo: string;
    };
  }
}
