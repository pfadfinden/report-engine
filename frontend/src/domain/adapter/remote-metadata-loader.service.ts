import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import crypto from 'crypto';
import { MetadataLoaderService } from '../port/metadata-loader.service';
import { MetadataService } from '../port/metadata.service';
import { SqliteMetadataService } from './sqlite-metadata.service';

export class RemoteMetadataLoaderService implements MetadataLoaderService {
  constructor(
    private cacheDir: string | null = null, // filename for cached DB
    private ttlMs: number = 12 * 60 * 60 * 1000, // default TTL: 12h,
    private authToken?: string,
  ) {}

  private async openRemoteDatabase(url: string): Promise<string> {
    const filehash = crypto.createHash('md5').update(url).digest('hex');
    const cacheDir = this.cacheDir ?? path.join(os.tmpdir(), 'remote-sqlite-cache');
    const cachePath = path.join(cacheDir, filehash);

    await fs.mkdir(cacheDir, { recursive: true });

    const shouldDownload = await this.hasNoUpToDateLocalCopy(cachePath);
    if (shouldDownload) {
      console.log(`Downloading SQLite DB from ${url}...`);
      console.log('has token', this.authToken != null);
      const res = await fetch(url, {
        headers: { Authorization: `Bearer ${this.authToken}` },
      });
      if (!res.ok) {
        throw new Error(`Failed to download DB: ${res.status} ${res.statusText}`);
      }

      const buffer = Buffer.from(await res.arrayBuffer());
      await fs.writeFile(cachePath, buffer);
    }

    return cachePath;
  }

  private async hasNoUpToDateLocalCopy(cachePath: string) {
    let shouldDownload = true;

    try {
      const stat = await fs.stat(cachePath);
      const age = Date.now() - stat.mtimeMs;

      if (age < this.ttlMs) {
        shouldDownload = false;
      }
    } catch {
      // file does not exist → must download
    }
    return shouldDownload;
  }

  public async load(url: string): Promise<MetadataService> {
    const tmpPath = await this.openRemoteDatabase(url);
    return new SqliteMetadataService(tmpPath);
  }
}
