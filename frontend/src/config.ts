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
    readonly clientSecret: string | undefined;
    readonly redirectUri: string;
    readonly postLogoutRedirectUri: string;
    readonly sessionSecret: string;
    // Alias of the Keycloak identity provider brokering Hitobito login, used
    // to fetch Hitobito's own token via Keycloak's Identity Brokering API V2
    // (see auth/hitobito-roles.ts) — Keycloak's built-in IdP mappers can't
    // carry Hitobito's nested role/permission objects through a claim.
    readonly brokerIdpAlias: string;
  };
}

function optionalEnv(name: string): string | undefined {
  const value = process.env[name];
  return value === undefined || value === '' ? undefined : value;
}

function requiredEnv(name: string): string {
  const value = optionalEnv(name);
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

function intEnv(name: string, fallback: number): number {
  const raw = optionalEnv(name);
  if (raw === undefined) {
    return fallback;
  }
  const parsed = Number.parseInt(raw, 10);
  if (Number.isNaN(parsed)) {
    throw new Error(`Environment variable ${name} must be an integer, got "${raw}"`);
  }
  return parsed;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const source = (env.REPORTS_DB_SOURCE ?? 'local') as MetadataSource;
  if (source !== 'local' && source !== 'remote') {
    throw new Error(`REPORTS_DB_SOURCE must be "local" or "remote", got "${source}"`);
  }

  if (source === 'remote') {
    // fail fast at startup rather than on the first incoming request
    requiredEnv('REPORTS_DB_REMOTE_URL');
  }

  const downloadMode = (env.REPORT_DOWNLOAD_MODE ?? 'proxy') as ReportDownloadMode;
  if (downloadMode !== 'proxy' && downloadMode !== 'direct') {
    throw new Error(`REPORT_DOWNLOAD_MODE must be "proxy" or "direct", got "${downloadMode}"`);
  }

  return {
    nodeEnv: env.NODE_ENV ?? 'development',
    port: intEnv('PORT', 3000),
    metadata: {
      source,
      localPath: env.REPORTS_DB_LOCAL_PATH ?? '../dist/preprocessed/reports.db',
      remoteUrl: optionalEnv('REPORTS_DB_REMOTE_URL'),
      authToken: optionalEnv('REPORTS_DB_AUTH_TOKEN'),
      cacheDir: optionalEnv('REPORTS_DB_CACHE_DIR'),
      cacheTtlMs: intEnv('REPORTS_DB_CACHE_TTL_MS', 30 * 60 * 1000),
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
      clientSecret: optionalEnv('OIDC_CLIENT_SECRET'),
      redirectUri: requiredEnv('AUTH_REDIRECT_URI'),
      postLogoutRedirectUri: optionalEnv('AUTH_POST_LOGOUT_REDIRECT_URI') ?? '/',
      sessionSecret: requiredEnv('AUTH_SESSION_SECRET'),
      brokerIdpAlias: requiredEnv('OIDC_BROKER_IDP_ALIAS'),
    },
  };
}
