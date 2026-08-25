import { Principal } from "../domain/model/principal";

declare global {
  namespace Express {
    interface Request {
      principal?: Principal;
    }
  }
}

export {};
