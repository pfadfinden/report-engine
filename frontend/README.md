# Report Engine: Frontend

Typescript-based web-application to select reports, provide parameters and download the final report.

## Authentication

All routes except `/healthz` and `/auth/*` require an authenticated session, backed by standard OpenID Connect
(OAuth 2.0 Authorization Code + PKCE) via [`openid-client`](https://github.com/panva/node-openid-client). Any
OIDC-compliant identity provider works — point `OIDC_ISSUER_URL` at one that serves a
`/.well-known/openid-configuration` document. See [.env.example](.env.example) for the required `OIDC_*` and
`AUTH_*` variables.

This repo doesn't bundle an identity provider — point it at your own Keycloak (or other OIDC-compliant
provider) instance. Copy `.env.example` to `.env` and fill in `OIDC_ISSUER_URL`, `OIDC_CLIENT_ID`
(and `OIDC_CLIENT_SECRET` for a confidential client) and `AUTH_SESSION_SECRET`.


