import { MetadataService } from "./metadata.service";

export interface MetadataLoaderService {
  load(urlOrPath: string): Promise<MetadataService>;
}
