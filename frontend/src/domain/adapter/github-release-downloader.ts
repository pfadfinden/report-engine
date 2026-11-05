/**
 * Downloads a GitHub release asset given its browser-style URL
 * `https://github.com/<owner>/<repo>/releases/download/<tag>/<file>`.
 *
 * With a token (needed for private repos) that URL answers 404, so the asset is resolved via the
 * REST API instead: look up the release by tag, find the asset by file name, then download it from
 * the asset's API URL with `Accept: application/octet-stream`. Without a token, or for a URL that
 * isn't a GitHub release URL, the URL is fetched directly.
 *
 * fetch() drops the Authorization header when a redirect leaves the origin, so the token never
 * reaches the signed storage URL GitHub redirects to.
 */
const GITHUB_API = 'https://api.github.com';
const RELEASE_URL = /^https:\/\/github\.com\/([^/]+)\/([^/]+)\/releases\/download\/([^/]+)\/([^/]+)$/;

export class GithubReleaseDownloader {
  constructor(
    private readonly token?: string,
    private readonly apiBase: string = GITHUB_API,
  ) {}

  /** Returns the final response; callers check `res.ok`. */
  async download(url: string, signal?: AbortSignal): Promise<Response> {
    const m = this.token ? RELEASE_URL.exec(url) : null;
    if (!m) {
      return fetch(url, { headers: this.headers(), signal });
    }
    const [, owner, repo, tag, file] = m;

    const releaseRes = await fetch(`${this.apiBase}/repos/${owner}/${repo}/releases/tags/${tag}`, {
      headers: this.headers('application/vnd.github+json'),
      signal,
    });
    if (!releaseRes.ok) return releaseRes;

    const release = (await releaseRes.json()) as { assets?: { name: string; url: string }[] };
    const asset = release.assets?.find((a) => a.name === file);
    if (!asset) {
      return new Response(null, { status: 404, statusText: `no asset ${file} in release ${tag}` });
    }
    return fetch(asset.url, { headers: this.headers('application/octet-stream'), signal });
  }

  private headers(accept?: string): Record<string, string> {
    const h: Record<string, string> = {};
    if (this.token) {
      h.Authorization = `token ${this.token}`;
      h['X-GitHub-Api-Version'] = '2022-11-28';
    }
    if (accept) h.Accept = accept;
    return h;
  }
}
