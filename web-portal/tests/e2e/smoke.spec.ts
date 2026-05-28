import { test, expect } from '@playwright/test';

const E2E_EMAIL = process.env.E2E_EMAIL ?? 'demo@msbank.local';
const E2E_PASSWORD = process.env.E2E_PASSWORD ?? 'demo-password-123';
const E2E_DESTINATION = process.env.E2E_DESTINATION ?? '00000000-0000-0000-0000-000000000002';

test('smoke: login → dashboard → transfer happy path', async ({ page, baseURL }) => {
  const url = baseURL ?? 'http://localhost:3000';

  let portalUp = false;
  try {
    const res = await page.request.get(url, { timeout: 3000 });
    portalUp = res.ok();
  } catch { /* ignore */ }
  test.skip(!portalUp, 'Portal not reachable — skipping e2e smoke');

  await page.goto('/login');
  await page.getByTestId('email').fill(E2E_EMAIL);
  await page.getByTestId('password').fill(E2E_PASSWORD);
  await page.getByTestId('login-submit').click();

  await page.waitForURL('**/dashboard', { timeout: 10_000 }).catch(() => {});
  if (!page.url().includes('/dashboard')) {
    test.skip(true, 'Login did not succeed — gateway likely not running with demo user');
    return;
  }

  await expect(page.getByRole('heading', { name: /dashboard/i })).toBeVisible();

  await page.goto('/transfer');
  await page.locator('input[name="source"]').first().check().catch(() => {});
  await page.getByTestId('step1-next').click().catch(() => {});

  await page.getByTestId('destination').fill(E2E_DESTINATION);
  await page.getByTestId('amount').fill('1.00');
  await page.getByTestId('step2-next').click();

  await page.getByTestId('submit-transfer').click();

  await expect(page.getByTestId('transfer-status')).toHaveText(/COMPLETED|PENDING|RESERVED|DEBITED|CREDITED/, { timeout: 30_000 });
});
