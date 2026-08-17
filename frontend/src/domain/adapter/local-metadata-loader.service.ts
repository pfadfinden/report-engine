import { MetadataLoaderService } from "../port/metadata-loader.service";
import { MetadataService } from "../port/metadata.service";
import { SqliteMetadataService } from "./sqlite-metadata.service";

export class LocalMetadataLoaderService implements MetadataLoaderService {
    load(urlOrPath: string): Promise<MetadataService> {
        return Promise.resolve(new SqliteMetadataService(urlOrPath));
    }

}