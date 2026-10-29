# Report Engine: Frontend

Typescript-based web-application to select reports, provide parameters and download the final report.

## Authentication

All routes except `/healthz`, `/.well-known/jwks.json` and `/auth/*` require an authenticated session, backed by
OpenID Connect and written to the [OAuth 2.1 draft](https://datatracker.ietf.org/doc/draft-ietf-oauth-v2-1/) via
[`openid-client`](https://github.com/panva/node-openid-client): authorization code grant only, PKCE with `S256` on every
login, `state` + `nonce`, an exact (never derived, never wildcard) `redirect_uri`, the RFC 9207 `iss` check on the
authorization response, and `private_key_jwt` as the one and only client authentication — **there is no client secret
anywhere in this deployment**. The point-by-point audit, including what is deliberately left open, is in
[docs/oauth-2.1-audit.md](docs/oauth-2.1-audit.md). Keycloak 26.7 is the authorization server this was built and
checked against; the realm settings it needs are listed under [Keycloak client configuration](#keycloak-client-configuration).

Copy `.env.example` to `.env` and fill in `OIDC_ISSUER_URL`, `OIDC_CLIENT_ID`, `OIDC_BROKER_IDP_ALIAS`,
`AUTH_REDIRECT_URI` and `AUTH_SESSION_SECRET` (and `OIDC_CLIENT_KEY_FILE` in production, see below).

### Startup validation

`loadConfig` and the startup sequence refuse to run, with an error naming the variable, when:

| Condition | Why |
|---|---|
| `OIDC_CLIENT_SECRET` is set, or `OIDC_CLIENT_AUTH_METHOD` is anything but `private_key_jwt` | No secret-based client authentication exists any more; a leftover secret should be removed, not silently ignored. |
| `NODE_ENV=production` and `OIDC_CLIENT_KEY_FILE` is unset | A per-process signing key is not robust in production (see [Signing key](#signing-key)). |
| `NODE_ENV=production` and the issuer, `AUTH_REDIRECT_URI`, an absolute `AUTH_POST_LOGOUT_REDIRECT_URI` or `HITOBITO_API_URL` is not `https:` | TLS only outside local dev. |
| `AUTH_REDIRECT_URI` has a wildcard, query, fragment, or is not an absolute http(s) URL | It must be one exact string matching what is registered at the issuer. |
| `OIDC_DPOP=on` together with `OIDC_BACKEND_HOST` | DPoP proofs cannot work with a rewritten host, see below. |
| `OIDC_CLIENT_KEY_FILE` is unreadable, has no PEM private key, is encrypted, is not EC P-256, or lists a key twice | The key must be usable before the first login needs it. |
| The issuer's discovery document lists `token_endpoint_auth_methods_supported` without `private_key_jwt`, or `token_endpoint_auth_signing_alg_values_supported` without `ES256` | The configured authentication could never succeed. |
| `NODE_ENV=production` and the issuer does not advertise `authorization_response_iss_parameter_supported` | Without it the RFC 9207 `iss` response check is not mandatory (only a warning outside production). |

### Client authentication (`private_key_jwt`)

The token request carries a `client_assertion` ([RFC 7523](https://www.rfc-editor.org/rfc/rfc7523.html), OpenID Connect
Core 1.0 §9), an ES256 JWT with `iss` = `sub` = the client id and `aud` = the issuer, signed per request with a fresh
`jti`. The public key is served at `GET /.well-known/jwks.json`; Keycloak fetches it ("Use JWKS URL") — nothing is
exchanged out of band. The same assertion authenticates the Hitobito role lookup (below), because Keycloak's Identity
Brokering API v2 runs the call through the same client authenticators as the token endpoint
(`IdentityBrokerService#getTokenV2` → `AuthorizeClientUtil.authorizeClient`, Keycloak 26.7.0), so there is nothing
secret-based to keep for it.

#### Signing key

The private key still is a secret, but one that never leaves this app and that Keycloak never needs to know: only the
public half is published. **Chosen approach: one stable key from a mounted file, `OIDC_CLIENT_KEY_FILE`, required in
production.** The `kid` is the key's JWK thumbprint, so it is identical in every process and survives restarts.

```sh
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out oidc_client_key.pem   # PKCS#8, no passphrase
chmod 600 oidc_client_key.pem
```

Why not the alternative (a key generated per start, still what runs when the variable is unset in development):

* **Concurrent requests during startup** — not an issue either way: `bin/www` only starts listening after
  `createServices` has produced the key and the JWKS, so no request can see a half-initialised state.
* **Keycloak's JWKS cache after a restart** — Keycloak caches the client's keys for 24 h and re-fetches only when it
  meets an *unknown* `kid`, at most once per `minTimeBetweenRequests` (10 s by default;
  `InfinispanPublicKeyStorageProviderFactory`, 26.7.0). With a per-start key every restart is a new `kid`, so the first
  login after a restart usually triggers the refetch and succeeds — but a login inside 10 s of any earlier refetch
  (crash loops, rapid redeploys) fails with `invalid_client`. A stable key means Keycloak never sees an unknown `kid`.
* **More than one replica** — with per-process keys each replica signs with its own `kid`, while Keycloak's JWKS fetch
  is answered by *one* replica that does not have the others' keys: intermittent `invalid_client`. With the shared file
  every replica publishes the identical JWKS. (Replicas additionally need a shared session store and `SessionIndex`
  first — see [Back-Channel Logout](#back-channel-logout) — so this is groundwork rather than "scale out now".)

Mount the file as a secret (`docker-compose.prod.yml` does, via a Docker secret at `/run/secrets/oidc_client_key`).

#### Key rotation

A key file may contain several PEM keys: **the first signs, all are published** in the JWKS. Rotate without a window in
which Keycloak could meet an unpublished `kid`:

1. Generate the new key and deploy a file `old` + `new` *with the old key still first*. Everything now publishes both.
2. Once every replica runs that file, swap the order (`new` first) and deploy. Signing switches; the old key stays
   published, so assertions already in flight (60 s lifetime) still verify.
3. After a few minutes drop the old key from the file and deploy.

Keycloak picks up the new `kid` by itself in step 2 (an unknown `kid` against an already-published JWKS).

### DPoP (sender-constrained tokens)

Access tokens are requested as DPoP-bound tokens ([RFC 9449](https://www.rfc-editor.org/rfc/rfc9449.html)) whenever the
issuer supports it. This app generates one DPoP key pair at startup (independent of the client-assertion key above) and
signs the token request in `/auth/callback` with it, so a stolen access token is useless without also holding the
private key, which never leaves this process. DPoP and `private_key_jwt` coexist on that same token request: the proof
travels in the `DPoP` header, the assertion in the form body (`auth/dpop.test.ts` pins this).

`OIDC_DPOP` sets how insistent this is: `auto` (default) uses DPoP when the issuer advertises
`dpop_signing_alg_values_supported` and plain bearer tokens when it doesn't, `on` refuses to start without it, `off`
never uses it. Every `auth.login` log event records what was issued in `auth.token_type` (`dpop` vs `bearer`). On
Keycloak, once that shows `dpop`, turn on the client's **Require DPoP bound tokens** so tokens can no longer be issued
without a proof.

**Incompatible with `OIDC_BACKEND_HOST`.** A DPoP proof's `htu` claim is bound to the literal request host the issuer
actually receives, not to its issuer identity — the one thing `OIDC_BACKEND_HOST` is designed to leave untouched while
it rewrites the network destination (see [src/auth/backend-host.ts](src/auth/backend-host.ts)). With the rewrite in play
the proof and the request disagree about the host and Keycloak rejects every proof (`invalid_dpop_proof` / "DPoP HTTP URL
mismatch"). `auto` therefore disables DPoP when `OIDC_BACKEND_HOST` is set, and `OIDC_DPOP=on` + `OIDC_BACKEND_HOST`
fails at config load. Production never sets `OIDC_BACKEND_HOST`. `private_key_jwt` is *not* affected (its `aud` is the
issuer identity, which the rewrite leaves alone) — but Keycloak must be able to reach this app's JWKS URL.

**Hitobito role lookup** ([src/auth/hitobito-roles.ts](src/auth/hitobito-roles.ts)). With a DPoP-bound token, Keycloak's
broker endpoint (`DPoPUtil.withDPoPVerifier`) rejects the call unless the request carries `Authorization: DPoP <token>`
*and* a proof (`htu` = the broker URL, `ath` = hash of the token) in addition to the `token` form parameter. So when the
login produced a DPoP token, the call goes through `fetchProtectedResource` with this app's DPoP handle; a bearer token
is sent as form parameter only, with no `Authorization` header (an `Authorization: Bearer` header would not help).
Earlier versions sent neither and would have failed once DPoP binding was in effect.

### Back-Channel Logout

`POST /auth/backchannel-logout` implements
[OpenID Connect Back-Channel Logout 1.0](https://openid.net/specs/openid-connect-backchannel-1_0.html). The provider
posts a signed logout token whenever a session ends somewhere else — the user logged out of another app, an admin
ended the session, the SSO session expired — and the matching local session is destroyed there and then instead of
surviving until its own cookie does.

Register that URL as the client's **Backchannel logout URL**, and leave **Backchannel logout session required** on so
the logout token carries a `sid`: that scopes a logout to the one browser session it came from rather than to every
session the user has open.

The provider knows sessions by `sub`/`sid`, which no session store can be queried by, so the mapping to this app's own
session ids is kept in process ([src/auth/session-index.ts](src/auth/session-index.ts)) next to the in-memory session
store. The two need replacing together (e.g. with redis) before running more than one instance — otherwise a logout
token only reaches whichever instance the load balancer happened to pick, and only that instance's sessions end.

The endpoint answers `200` once the targeted sessions are gone (including when none matched), `400` for a logout token
that fails validation, and `503` when the issuer's JWKS can't be reached or the store refuses to destroy a session —
the two cases where the provider should retry rather than give up.

## Keycloak client configuration

What the realm has to contain for this app's client (`OIDC_CLIENT_ID`), for the Keycloak 26.7 Admin Console. `<reports-host>`
is the public host of this frontend (`AUTH_REDIRECT_URI`'s host). Apply it with whatever manages the realm; the
**Realm JSON attribute** column is the key under the client's `attributes` / top-level representation.

| Admin Console | Setting | Realm JSON |
|---|---|---|
| Settings → Capability config | **Client authentication: On** (confidential client) | `publicClient: false` |
| | Authorization: Off | `authorizationServicesEnabled: false` |
| | **Standard flow: On** — the only flow | `standardFlowEnabled: true` |
| | **Implicit flow: Off** | `implicitFlowEnabled: false` |
| | **Direct access grants: Off** | `directAccessGrantsEnabled: false` |
| | **Service accounts roles: Off** | `serviceAccountsEnabled: false` |
| | OAuth 2.0 Device Authorization Grant, CIBA, Standard token exchange: Off | `oauth2.device.authorization.grant.enabled: false`, `oidc.ciba.grant.enabled: false`, `standard.token.exchange.enabled: false` |
| | **Require DPoP bound tokens: On** (once `auth.token_type=dpop` is logged) | `dpop.bound.access.tokens: "true"` |
| Settings → Access settings | **Valid redirect URIs: exactly** `https://<reports-host>/auth/callback` (no `*`, no `+`) | `redirectUris: ["https://<reports-host>/auth/callback"]` |
| | **Valid post logout redirect URIs: exactly** the value of `AUTH_POST_LOGOUT_REDIRECT_URI`, e.g. `https://<reports-host>/` | `post.logout.redirect.uris: "https://<reports-host>/"` |
| | Web origins: empty; Root/Home URL optional | `webOrigins: []` |
| Settings → Logout settings | **Backchannel logout URL:** `https://<reports-host>/auth/backchannel-logout` | `backchannel.logout.url` |
| | Backchannel logout session required: **On**; revoke offline sessions: Off; Front channel logout: Off | `backchannel.logout.session.required: "true"`, `frontchannelLogout: false` |
| Credentials | **Client Authenticator: Signed JWT** (not "Signed JWT with Client Secret") | `clientAuthenticatorType: "client-jwt"` |
| | **Signature algorithm: ES256** | `token.endpoint.auth.signing.alg: "ES256"` |
| | Maximum expiration: leave at the default 60 s (assertions live exactly 60 s) | `token.endpoint.auth.signing.max.exp` |
| Keys | **Use JWKS URL: On**, **JWKS URL** `https://<reports-host>/.well-known/jwks.json` — must be reachable from Keycloak over TLS with a trusted certificate | `use.jwks.url: "true"`, `jwks.url: "https://<reports-host>/.well-known/jwks.json"` |
| Advanced → Advanced settings | **Proof Key for Code Exchange Code Challenge Method: S256** (makes PKCE mandatory — a request without it is rejected) | `pkce.code.challenge.method: "S256"` |
| Settings → Capability config (Identity Brokering API v2) | **Allow retrieve external tokens: On**; **Allowed identity providers for external tokens:** the `OIDC_BROKER_IDP_ALIAS` (e.g. `mv-oidc`) | `external.token.enabled: "true"`, `external.token.idp: "mv-oidc"` |
| Realm settings → Tokens | **Revoke Refresh Token: On**, Refresh Token Max Reuse: 0 — refresh-token rotation with reuse detection. This app never requests or uses refresh tokens, so this is purely defence in depth. | realm: `revokeRefreshToken: true`, `refreshTokenMaxReuse: 0` |

Server and identity-provider prerequisites:

* Start Keycloak with `--features=identity-brokering-api:v2` (disabled by default; the app's role lookup calls
  `POST /realms/<realm>/broker/<alias>/token`). The `dpop` feature must be enabled for DPoP.
* The Hitobito identity provider (`OIDC_BROKER_IDP_ALIAS`) must **store tokens**. The v2 API needs neither the `broker`
  client role `read-token` nor "stored tokens readable" (those are for the deprecated v1 `GET`).
* The access token must be issued to this client (`azp`) or list it in `aud` — the default.
* Local dev only: `http://localhost…` redirect and JWKS URLs work in a dev realm, but are exactly what the production
  settings above (and the client policy below) exclude.
