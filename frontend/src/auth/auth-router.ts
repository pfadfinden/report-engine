import { Request, Response, NextFunction, Router } from "express";
import {
  authorizationCodeGrant,
  buildAuthorizationUrl,
  buildEndSessionUrl,
  calculatePKCECodeChallenge,
  Configuration,
  randomNonce,
  randomPKCECodeVerifier,
  randomState,
} from "openid-client";
import { AppConfig } from "../config";
import { claimsToPrincipal } from "./claims-to-principal";
import { fetchHitobitoRoles } from "./hitobito-roles";

var express = require("express");

function safeReturnTo(value: unknown): string {
  // only ever redirect back into this app - reject absolute/protocol-relative URLs to avoid an open redirect
  return typeof value === "string" &&
    value.startsWith("/") &&
    !value.startsWith("//")
    ? value
    : "/";
}

export function createAuthRouter(
  configuration: Configuration,
  config: AppConfig["auth"],
  hitobitoApiUrl: string,
): Router {
  const router = express.Router();

  router.get("/login", async function (req: Request, res: Response) {
    const state = randomState();
    const nonce = randomNonce();
    const codeVerifier = randomPKCECodeVerifier();

    req.session.pendingAuth = {
      state,
      nonce,
      codeVerifier,
      returnTo: safeReturnTo(req.query.returnTo),
    };

    const authorizationUrl = buildAuthorizationUrl(configuration, {
      scope: "openid profile email",
      redirect_uri: config.redirectUri,
      state,
      nonce,
      code_challenge: await calculatePKCECodeChallenge(codeVerifier),
      code_challenge_method: "S256",
    });

    res.redirect(authorizationUrl.href);
  });

  router.get(
    "/callback",
    async function (req: Request, res: Response, next: NextFunction) {
      const pendingAuth = req.session.pendingAuth;

      if (!pendingAuth) {
        res.redirect("/auth/login");
        return;
      }

      try {
        // config.redirectUri as the base gives authorizationCodeGrant the exact registered
        // redirect_uri (it derives one from this URL by stripping the query string)
        const currentUrl = new URL(req.originalUrl, config.redirectUri);
        const tokens = await authorizationCodeGrant(configuration, currentUrl, {
          pkceCodeVerifier: pendingAuth.codeVerifier,
          expectedState: pendingAuth.state,
          expectedNonce: pendingAuth.nonce,
        });

        const claims = tokens.claims();
        if (!claims) {
          throw new Error("OIDC token response did not include an ID token");
        }
        if (!config.clientSecret) {
          // Identity Brokering API V2 requires a confidential client — this
          // should never happen for mv-reports, which always has a secret.
          throw new Error(
            "OIDC_CLIENT_SECRET is required to fetch Hitobito roles via the broker token endpoint",
          );
        }

        const roles = await fetchHitobitoRoles(
          configuration.serverMetadata().issuer,
          config.brokerIdpAlias,
          hitobitoApiUrl,
          config.clientId,
          config.clientSecret,
          tokens.access_token,
          config.backendHost,
        );

        const principal = claimsToPrincipal(claims, roles);
        const returnTo = pendingAuth.returnTo;

        console.log(
          `[auth] login callback: sub=${claims.sub}, resolved principal roles: ${JSON.stringify(principal.roles)}`,
        );

        // regenerate the session on login to prevent session fixation
        req.session.regenerate(function (regenerateErr) {
          if (regenerateErr) {
            next(regenerateErr);
            return;
          }

          req.session.principal = principal;
          req.session.idToken = tokens.id_token;

          req.session.save(function (saveErr) {
            if (saveErr) {
              next(saveErr);
              return;
            }
            res.redirect(returnTo);
          });
        });
      } catch (err) {
        next(err);
      }
    },
  );

  router.get(
    "/logout",
    function (req: Request, res: Response, next: NextFunction) {
      const idToken = req.session.idToken;

      req.session.destroy(function (err) {
        if (err) {
          next(err);
          return;
        }

        const endSessionEndpoint =
          configuration.serverMetadata().end_session_endpoint;

        const redirectTo = endSessionEndpoint
          ? buildEndSessionUrl(configuration, {
              ...(idToken ? { id_token_hint: idToken } : {}),
              post_logout_redirect_uri: config.postLogoutRedirectUri,
            }).href
          : config.postLogoutRedirectUri;

        res.redirect(redirectTo);
      });
    },
  );

  return router;
}
