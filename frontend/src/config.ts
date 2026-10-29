import type { DpopMode } from './auth/dpop';

export type MetadataSource = 'local' | 'remote';
export type ReportDownloadMode = 'proxy' | 'direct';

export interface AppConfig {
  readonly nodeEnv: string;
  readonly port: number;
  readonly metadata: {
    readonly source: MetadataSource;
    readonly localPath: string;
    readonly remoteUrl: string | undefined;
    readonly authToken: string | undefined;
    readonly cacheDir: string | undefined;
    readonly cacheTtlMs: number;
  };
  readonly groups: {
    readonly hitobitoApiUrl: string;
    readonly hitobitoApiToken: string;
    readonly cacheTtlMs: number;
  };
  readonly execution: {
    readonly apiUrl: string;
    readonly apiToken: string | undefined;
    // "proxy" (default): the frontend fetches the finished report itself and
    // streams it to the browser - the executor is never exposed to the
    // browser/network, and this is the only place a real per-user
    // authorization check happens (a signed URL only proves a request to
    // /download happened, not who it was for). "direct": the browser is
    // handed the executor's signed URL and fetches it itself - less backend
    // bandwidth, but requires the executor to be reachable from the browser,
    // which then also needs to trust the browser with a URL to raw report
    // data. Only relax to "direct" if you understand and accept that.
    readonly downloadMode: ReportDownloadMode;
  };
  readonly auth: {
    readonly issuerUrl: string;
    readonly backendHost: string | undefined;
    readonly clientId: string;
    readonly redirectUri: string;
    readonly postLogoutRedirectUri: string;
    readonly sessionSecret: string;
    // How hard to insist on DPoP (RFC 9449) sender-constrained tokens - see auth/dpop.ts.
    readonly dpop: DpopMode;
    // PEM file holding the EC P-256 key that signs client assertions (auth/client-jwt.ts). Required
    // in production so every replica and restart uses the same key; unset only generates one.
    readonly clientKeyFile: string | undefined;
    // Alias of the Keycloak identity provider brokering Hitobito login, used
    // to fetch Hitobito's own token via Keycloak's Identity Brokering API V2
    // (see auth/hitobito-roles.ts) — Keycloak's built-in IdP mappers can't
    // carry Hitobito's nested role/permission objects through a claim.
    readonly brokerIdpAlias: string;
  };
  readonly support: {
    readonly helpEmail: string;
  };
}

function optionalEnvFrom(env: NodeJS.ProcessEnv, name: string): string | undefined {
  const value = env[name];
  return value === undefined || value === '' ? undefined : value;
}

function requiredEnvFrom(env: NodeJS.ProcessEnv, name: string): string {
  const value = optionalEnvFrom(env, name);
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

function intEnvFrom(env: NodeJS.ProcessEnv, name: string, fallback: number): number {
  const raw = optionalEnvFrom(env, name);
  if (raw === undefined) {
    return fallback;
  }
  const parsed = Number.parseInt(raw, 10);
  if (Number.isNaN(parsed)) {
    throw new Error(`Environment variable ${name} must be an integer, got "${raw}"`);
  }
  return parsed;
}

function parseUrl(name: string, value: string): URL {
  try {
    return new URL(value);
  } catch {
    throw new Error(`${name} must be an absolute URL, got "${value}"`);
  }
}

/**
 * Every URL a login passes through must be TLS-only outside local development (OAuth 2.1 draft,
 * sections 1.5 and 7.1: the authorization response and all token traffic travel over TLS), and
 * the redirect URI must be one exact, wildcard-free value because that is what has to be
 * registered at the authorization server (section 7.5.1 - exact string matching).
 * NODE_ENV=production is the line between the two - nothing else in this app is "local dev".
 */
export function assertSecureAuthUrls(
  nodeEnv: string,
  auth: Pick<AppConfig['auth'], 'issuerUrl' | 'redirectUri' | 'postLogoutRedirectUri'>,
  hitobitoApiUrl: string,
): void {
  const production = nodeEnv === 'production';
  const requireTls = (name: string, url: URL) => {
    if (production && url.protocol !== 'https:') {
      throw new Error(`${name} must use https when NODE_ENV=production, got "${url.origin}"`);
    }
  };
  const requireWebScheme = (name: string, url: URL) => {
    if (url.protocol !== 'https:' && url.protocol !== 'http:') {
      throw new Error(`${name} must be an http(s) URL, got "${url.protocol}"`);
    }
  };

  const issuer = parseUrl('OIDC_ISSUER_URL', auth.issuerUrl);
  requireWebScheme('OIDC_ISSUER_URL', issuer);
  requireTls('OIDC_ISSUER_URL', issuer);

  const redirect = parseUrl('AUTH_REDIRECT_URI', auth.redirectUri);
  requireWebScheme('AUTH_REDIRECT_URI', redirect);
  requireTls('AUTH_REDIRECT_URI', redirect);
  if (redirect.hash || auth.redirectUri.includes('#')) {
    throw new Error('AUTH_REDIRECT_URI must not contain a fragment');
  }
  if (redirect.search || auth.redirectUri.includes('*')) {
    // a query or wildcard can never be an exact match for what is registered at the AS
    throw new Error('AUTH_REDIRECT_URI must be one exact URL without wildcards or a query string');
  }

  // A relative value ("/", the default) is resolved against this app's own origin by the browser.
  if (/^[a-z][a-z0-9+.-]*:/i.test(auth.postLogoutRedirectUri)) {
    const postLogout = parseUrl('AUTH_POST_LOGOUT_REDIRECT_URI', auth.postLogoutRedirectUri);
    requireWebScheme('AUTH_POST_LOGOUT_REDIRECT_URI', postLogout);
    requireTls('AUTH_POST_LOGOUT_REDIRECT_URI', postLogout);
  }

  // The broker flow sends a Hitobito access token to this host.
  requireTls('HITOBITO_API_URL', parseUrl('HITOBITO_API_URL', hitobitoApiUrl));
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const optionalEnv = (name: string) => optionalEnvFrom(env, name);
  const requiredEnv = (name: string) => requiredEnvFrom(env, name);
  const intEnv = (name: string, fallback: number) => intEnvFrom(env, name, fallback);

  const source = (env.REPORTS_DB_SOURCE ?? 'local') as MetadataSource;
  if (source !== 'local' && source !== 'remote') {
    throw new Error(`REPORTS_DB_SOURCE must be "local" or "remote", got "${source}"`);
  }

  if (source === 'remote') {
    // fail fast at startup rather than on the first incoming request
    requiredEnv('REPORTS_RELEASE_URL');
  }

  const releaseUrl = optionalEnv('REPORTS_RELEASE_URL')?.replace(/\/+$/, '');

  const downloadMode = (env.REPORT_DOWNLOAD_MODE ?? 'proxy') as ReportDownloadMode;
  if (downloadMode !== 'proxy' && downloadMode !== 'direct') {
    throw new Error(`REPORT_DOWNLOAD_MODE must be "proxy" or "direct", got "${downloadMode}"`);
  }

  const dpop = (env.OIDC_DPOP ?? 'auto') as DpopMode;
  if (dpop !== 'auto' && dpop !== 'on' && dpop !== 'off') {
    throw new Error(`OIDC_DPOP must be "auto", "on" or "off", got "${dpop}"`);
  }

  // private_key_jwt (auth/client-jwt.ts) is the only way this app authenticates to the issuer. The
  // two settings that used to select something else are refused rather than ignored, so a
  // leftover secret in a deployment's environment is an error to clean up, not a silent no-op.
  if (optionalEnv('OIDC_CLIENT_SECRET')) {
    throw new Error(
      'OIDC_CLIENT_SECRET is no longer supported: this app authenticates with private_key_jwt only. ' +
        'Remove it from the environment and configure the Keycloak client with the "Signed JWT" authenticator.',
    );
  }
  const clientAuthMethod = optionalEnv('OIDC_CLIENT_AUTH_METHOD');
  if (clientAuthMethod !== undefined && clientAuthMethod !== 'private_key_jwt') {
    throw new Error(
      `OIDC_CLIENT_AUTH_METHOD "${clientAuthMethod}" is not supported: private_key_jwt is the only client ` +
        'authentication method (leave the variable unset).',
    );
  }

  const config: AppConfig = {
    nodeEnv: env.NODE_ENV ?? 'development',
    port: intEnv('PORT', 3000),
    metadata: {
      source,
      localPath: env.REPORTS_DB_LOCAL_PATH ?? '../dist/preprocessed/reports.db',
      // Same release, token and TTL as local-report-executor's reports.zip, so the two never
      // serve metadata and report definitions from different versions.
      remoteUrl: releaseUrl ? `${releaseUrl}/reports.db` : undefined,
      authToken: optionalEnv('REPORTS_TOKEN'),
      cacheDir: optionalEnv('REPORTS_DB_CACHE_DIR'),
      cacheTtlMs: intEnv('REPORTS_CACHE_TTL_MINUTES', 60) * 60 * 1000,
    },
    groups: {
      hitobitoApiUrl: requiredEnv('HITOBITO_API_URL'),
      hitobitoApiToken: requiredEnv('HITOBITO_API_TOKEN'),
      cacheTtlMs: intEnv('HITOBITO_GROUPS_CACHE_TTL_MS', 5 * 60 * 1000),
    },
    execution: {
      apiUrl: requiredEnv('REPORT_EXECUTION_API_URL'),
      apiToken: optionalEnv('REPORT_EXECUTION_API_TOKEN'),
      downloadMode,
    },
    auth: {
      issuerUrl: requiredEnv('OIDC_ISSUER_URL'),
      backendHost: optionalEnv('OIDC_BACKEND_HOST'),
      clientId: requiredEnv('OIDC_CLIENT_ID'),
      redirectUri: requiredEnv('AUTH_REDIRECT_URI'),
      postLogoutRedirectUri: optionalEnv('AUTH_POST_LOGOUT_REDIRECT_URI') ?? '/',
      sessionSecret: requiredEnv('AUTH_SESSION_SECRET'),
      dpop,
      clientKeyFile: optionalEnv('OIDC_CLIENT_KEY_FILE'),
      brokerIdpAlias: requiredEnv('OIDC_BROKER_IDP_ALIAS'),
    },
    support: {
      helpEmail: env.SUPPORT_HELP_EMAIL ?? 'mitglied@pfadfinden.de',
    },
  };

  // Also enforced where the proof is built (auth/dpop.ts), but only after discovery has already
  // gone out over the network - this fails the misconfiguration before anything is contacted.
  if (config.auth.dpop === 'on' && config.auth.backendHost) {
    throw new Error(
      'OIDC_DPOP=on is incompatible with OIDC_BACKEND_HOST: a DPoP proof is bound to the host the issuer ' +
        'actually receives, which OIDC_BACKEND_HOST rewrites. Unset OIDC_BACKEND_HOST, or use OIDC_DPOP=auto/off.',
    );
  }
  if (config.nodeEnv === 'production' && !config.auth.clientKeyFile) {
    throw new Error(
      'OIDC_CLIENT_KEY_FILE is required when NODE_ENV=production: a key generated per process would ' +
        'change the JWKS key id on every restart and differ between replicas, so Keycloak would reject ' +
        'client assertions it has not fetched the key for yet.',
    );
  }
  assertSecureAuthUrls(config.nodeEnv, config.auth, config.groups.hitobitoApiUrl);
  return config;
}
