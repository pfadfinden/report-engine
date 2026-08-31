import { Configuration } from 'openid-client';
import { AppConfig } from './config';
import { GroupsService } from './domain/port/groups.service';
import { MetadataService } from './domain/port/metadata.service';
import { ReportExecutionService } from './domain/port/report-execution.service';
import { HitobitoGroupsService } from './domain/adapter/hitobito-groups.service';
import { LocalMetadataLoaderService } from './domain/adapter/local-metadata-loader.service';
import { RemoteMetadataLoaderService } from './domain/adapter/remote-metadata-loader.service';
import { HttpReportExecutionService } from './domain/adapter/http-report-execution.service';
import { createOidcClient } from './auth/oidc-client';

export interface AppServices {
  readonly groupsService: GroupsService;
  readonly metadataService: MetadataService;
  readonly reportExecutionService: ReportExecutionService;
  readonly authClient: Configuration;
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

  const authClient = await createOidcClient(config.auth);

  return { groupsService, metadataService, reportExecutionService, authClient };
}
