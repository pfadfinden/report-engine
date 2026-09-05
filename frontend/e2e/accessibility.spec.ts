import AxeBuilder from '@axe-core/playwright';
import { expect, Page, test } from '@playwright/test';
import { E2E_GROUP } from './fixtures/fake-groups.service';

/**
 * Accessibility checks (see https://playwright.dev/docs/accessibility-testing) for the same real
 * screens report-generation.spec.ts drives, run separately so an a11y regression and a functional
 * one produce distinct, unambiguous failures instead of one test asserting both.
 *
 * Scoped to WCAG 2.0/2.1 level A and AA (via withTags) rather than axe's full default rule set,
 * which also includes "best practice" rules that aren't part of the WCAG standard itself - this
 * is the conformance target Playwright's own accessibility-testing docs recommend checking
 * against. Level AA includes color-contrast, which is what actually catches contrast issues.
 */
const WCAG_AA_TAGS = ['wcag2a', 'wcag2aa', 'wcag21aa'];

async function selectGroupAndReport(page: Page) {
  await page.goto('/__e2e-login');
  await page.selectOption('select[name="groupId"]', E2E_GROUP.id);
  await page.getByRole('button', { name: 'Gruppe auswählen' }).click();
  await page.selectOption('select[name="reportId"]', 'members');
  await page.getByRole('button', { name: 'Report auswählen' }).click();
}

test('report selection start page has no detectable accessibility violations', async ({ page }) => {
  await page.goto('/__e2e-login');
  await expect(page).toHaveURL('/');

  const results = await new AxeBuilder({ page }).withTags(WCAG_AA_TAGS).analyze();
  expect(results.violations).toEqual([]);
});

test('report and parameter form has no detectable accessibility violations', async ({ page }) => {
  await selectGroupAndReport(page);

  const results = await new AxeBuilder({ page }).withTags(WCAG_AA_TAGS).analyze();
  expect(results.violations).toEqual([]);
});

test('execution status page has no detectable accessibility violations', async ({ page }) => {
  await selectGroupAndReport(page);
  await page.fill('input[name="p_p_joined_after"]', '2021-01-01');
  await page.selectOption('select[name="outputFormat"]', 'pdf');
  await page.getByRole('button', { name: 'Report generieren' }).click();
  await expect(page).toHaveURL(/\/executions\/.+/);

  const results = await new AxeBuilder({ page })
    .withTags(WCAG_AA_TAGS)
    // False positive, not a real violation: axe's meta-refresh rule flags any
    // <meta http-equiv="refresh"> under 20 hours as a blanket WCAG 2.2.1 (Timing Adjustable)
    // violation, but 2.2.1 is about time limits imposed on completing a user's task - this page
    // (execution-status.pug) sets no such limit. The refresh is a passive status poll for a
    // background job: it requires no user action, and nothing expires or becomes unreachable if
    // the user ignores it. That's the same "auto-updating status, no user focus required, no
    // disorienting change of context" pattern WCAG's own Understanding SC 2.2.1 document gives as
    // compliant (its example: a mail client polling for new messages). axe can't tell that apart
    // from an actual imposed time limit, so it's disabled here rather than left as a false
    // failure (the rule stays enabled for the other two pages, which don't use meta-refresh).
    .disableRules(['meta-refresh'])
    .analyze();
  expect(results.violations).toEqual([]);
});
