import type { JWK } from 'jose';
import { ClientAuth, Configuration, DPoPHandle } from 'openid-client';
import { AppConfig } from './config';
import { GroupsService } from './domain/port/groups.service';
import { MetadataService } from './domain/port/metadata.service';
import { ReportExecutionService } from './domain/port/report-execution.service';
import { HitobitoGroupsService } from './domain/adapter/hitobito-groups.service';
import { LocalMetadataLoaderService } from './domain/adapter/local-metadata-loader.service';
import { RemoteMetadataLoaderService } from './domain/adapter/remote-metadata-loader.service';
import { HttpReportExecutionService } from './domain/adapter/http-report-execution.service';
import { assertIssuerCapabilities, createOidcClient } from './auth/oidc-client';
import { createClientJwtAuth } from './auth/client-jwt';
import { createDpopHandle } from './auth/dpop';
import { createIssuerLogoutTokenVerifier, VerifyLogoutToken } from './auth/logout-token';

export interface AppServices {
  readonly groupsService: GroupsService;
  readonly metadataService: MetadataService;
  readonly reportExecutionService: ReportExecutionService;
  readonly authClient: Configuration;
  // The private_key_jwt authentication (see auth/client-jwt.ts) createOidcClient built authClient
  // with, reused unchanged for the Hitobito broker call in auth/hitobito-roles.ts, which
  // authenticates through the identical mechanism.
  readonly clientAuthentication: ClientAuth;
  // This app's one DPoP key, built once at startup (see auth/dpop.ts) - undefined when DPoP is
  // switched off or the issuer doesn't support it, in which case login falls back to plain bearer
  // tokens.
  readonly dpopHandle: DPoPHandle | undefined;
  // The public half of this app's private_key_jwt signing key (see auth/client-jwt.ts), served at
  // GET /.well-known/jwks.json.
  readonly clientJwks: { keys: JWK[] };
  // Verifies back-channel logout tokens against the issuer's JWKS (fetched and cached on first
  // use, so building this costs nothing at startup).
  readonly verifyLogoutToken: VerifyLogoutToken;
}

export async function createServices(config: AppConfig): Promise<AppServices> {
  const groupsService: GroupsService = new HitobitoGroupsService(
    config.groups.hitobitoApiUrl,
    config.groups.hitobitoApiToken,
    config.groups.cacheTtlMs,
  );

  const metadataService = await (config.metadata.source === 'remote'
    ? new RemoteMetadataLoaderService(
        config.metadata.cacheDir ?? null,
        config.metadata.cacheTtlMs,
        config.metadata.authToken,
      ).load(config.metadata.remoteUrl!)
    : new LocalMetadataLoaderService().load(config.metadata.localPath));

  const reportExecutionService: ReportExecutionService = new HttpReportExecutionService(
    config.execution.apiUrl,
    config.execution.apiToken,
  );

  // Built before discovery(), unlike dpopHandle below: client authentication is chosen up
  // front and baked into the Configuration at construction time, whereas DPoP support is only
  // decided afterwards, from what that same discovery call reports.
  const clientJwt = await createClientJwtAuth(config.auth.clientKeyFile);
  const clientAuthentication = clientJwt.clientAuth;
  const authClient = await createOidcClient(config.auth, clientAuthentication);
  assertIssuerCapabilities(authClient, config.nodeEnv);
  const dpopHandle = await createDpopHandle(authClient, config.auth.dpop, config.auth.backendHost);
  const verifyLogoutToken = createIssuerLogoutTokenVerifier(authClient, config.auth);

  return {
    groupsService,
    metadataService,
    reportExecutionService,
    authClient,
    clientAuthentication,
    dpopHandle,
    clientJwks: clientJwt.jwks,
    verifyLogoutToken,
  };
}
