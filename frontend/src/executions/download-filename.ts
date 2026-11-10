// Builds a descriptive download filename like "Mitgliederliste_Stamm-Beispiel_2026-11-10_1432.pdf".
// Umlauts are kept (the header carries an RFC 5987 filename* too); only characters that are
// unsafe in filenames are replaced.
function sanitize(part: string): string {
  return (
    part
      // eslint-disable-next-line no-control-regex -- stripping control characters is the point
      .replace(/[\\/:*?"<>|\u0000-\u001f]/g, '')
      .replace(/\s+/g, '-')
      .replace(/-+/g, '-')
      .replace(/^[-.]+|[-.]+$/g, '')
      .slice(0, 60)
  );
}

export function buildDownloadFileName(
  parts: ReadonlyArray<string>,
  outputFormat: string,
  now: Date = new Date(),
): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  const stamp = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}`;
  const base = [...parts.map(sanitize).filter(Boolean), stamp].join('_');
  const ext = sanitize(outputFormat).toLowerCase() || 'bin';
  return `${base}.${ext}`;
}

export function contentDisposition(fileName: string): string {
  const ascii = fileName.replace(/[^\x20-\x7e]/g, '_').replace(/["\\]/g, '_');
  return `attachment; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(fileName)}`;
}
