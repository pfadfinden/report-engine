import fs from 'fs';
import { expect, test } from '@playwright/test';
import { E2E_GROUP } from './fixtures/fake-groups.service';

/**
 * The e2e test (as distinct from the module-to-datasource integration tests elsewhere in this
 * repo): drives the real rendered UI through a real browser, from group/report selection through
 * to downloading a real generated PDF - meaning the frontend actually talks to a real running
 * executor over HTTP, which actually fills the shared "members" report fixture against a real
 * Postgres. See playwright.config.ts for how both processes are started, and server.ts for what's
 * faked (only GroupsService and login) versus real.
 */

// Measured directly: 4 real runs of this exact report+parameter combination against this exact
// fixture all produced PDFs of precisely this size, byte for byte identical in length even though
// their checksums differed (JasperReports' PDF export embeds a fixed-width creation timestamp, so
// the *value* changes but the *length* doesn't). That rules out an exact-size or checksum
// comparison as flaky - it isn't, for this content - but a tolerance is still kept deliberately
// wide so a minor JasperReports/library version bump doesn't start failing this on byte noise;
// what it's actually meant to catch is a gross regression (missing rows, empty output, a
// corrupted/truncated file), which would move the size far more than 15%.
const REFERENCE_PDF_SIZE_BYTES = 1983;
const SIZE_TOLERANCE = 0.15;

test('selecting a group and report, filling its parameter, and generating a report downloads a real PDF', async ({
  page,
}) => {
  await page.goto('/__e2e-login');
  await expect(page).toHaveURL('/');

  await page.selectOption('select[name="groupId"]', E2E_GROUP.id);
  await page.getByRole('button', { name: 'Gruppe auswählen' }).click();

  await page.selectOption('select[name="reportId"]', 'members');
  await page.getByRole('button', { name: 'Report auswählen' }).click();

  // The report's one parameter, p_joined_after (see test-fixtures/reports/members/metadata.yaml)
  // - the HTML field name is prefixed a second time by index.pug, matching how real reports'
  // parameters (e.g. p_gruppe_id) are already named with a "p_" prefix in metadata.yaml.
  await page.fill('input[name="p_p_joined_after"]', '2021-01-01');
  await page.selectOption('select[name="outputFormat"]', 'pdf');
  await page.getByRole('button', { name: 'Report generieren' }).click();

  await expect(page).toHaveURL(/\/executions\/.+/);

  const downloadLink = page.getByRole('link', { name: 'Bericht herunterladen' });
  const failedAlert = page.locator('.alert--error');
  await expect(downloadLink.or(failedAlert)).toBeVisible({ timeout: 30_000 });
  await expect(failedAlert, 'report generation should not have failed').toBeHidden();

  const downloadPromise = page.waitForEvent('download');
  await downloadLink.click();
  const download = await downloadPromise;

  const filePath = await download.path();
  expect(filePath).not.toBeNull();
  const bytes = fs.readFileSync(filePath as string);
  expect(bytes.subarray(0, 5).toString('latin1')).toBe('%PDF-');
  expect(bytes.length).toBeGreaterThanOrEqual(REFERENCE_PDF_SIZE_BYTES * (1 - SIZE_TOLERANCE));
  expect(bytes.length).toBeLessThanOrEqual(REFERENCE_PDF_SIZE_BYTES * (1 + SIZE_TOLERANCE));
});
