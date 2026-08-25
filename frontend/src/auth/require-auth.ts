import { Request, Response, NextFunction } from "express";

export function requireAuth(req: Request, res: Response, next: NextFunction) {
  if (req.session.principal) {
    req.principal = req.session.principal;
    next();
    return;
  }

  res.redirect(`/auth/login?returnTo=${encodeURIComponent(req.originalUrl)}`);
}
