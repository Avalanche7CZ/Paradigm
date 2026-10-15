import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mockApi, remoteSnapshot } from './fixtures';
test('network selection updates MOTD and saves the captured revision and target', async ({
  page,
}) => {
  const requests = await mockApi(page);
  await page.goto('/');
  await page.getByRole('heading', { name: 'Network overview' }).waitFor();
  await page.getByLabel('Server context', { exact: true }).selectOption('stoneblock');
  await page.getByRole('link', { name: 'MOTD & welcome', exact: true }).click();
  await page.getByLabel('Welcome message', { exact: true }).fill('Welcome StoneBlock');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByText('Configuration saved.', { exact: true })).toBeVisible();
  expect(requests.filter((r) => r.path === '/api/remote-config/patch').at(-1)?.body).toMatchObject({
    serverId: 'stoneblock',
    scope: 'SERVER',
    expectedRevision: 4,
    section: 'motd',
    operations: [{ key: 'motd.message', value: 'Welcome StoneBlock' }],
  });
});
test('unsaved changes guard server switches, navigation and discard', async ({ page }) => {
  await mockApi(page);
  await page.goto('/#motd');
  await page.getByLabel('Server context', { exact: true }).selectOption('stoneblock');
  await page.getByLabel('Welcome message', { exact: true }).fill('Draft');
  await page.getByLabel('Server context', { exact: true }).selectOption('host');
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(page.getByLabel('Server context', { exact: true })).toHaveValue('stoneblock');
  await expect(page.getByLabel('Welcome message', { exact: true })).toHaveValue('Draft');
  await page.getByRole('button', { name: 'Discard', exact: true }).click();
  await expect(page.getByLabel('Welcome message', { exact: true })).toHaveValue('Hello');
  await page.getByRole('link', { name: 'Players', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Players', exact: true })).toBeVisible();
});
test('network-wide saves require review and use the network revision', async ({ page }) => {
  const requests = await mockApi(page);
  await page.goto('/#motd');
  await page.getByLabel('Welcome message', { exact: true }).fill('New default');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('Entire network');
  expect(requests.some((r) => r.path === '/api/remote-config/patch')).toBe(false);
  await page.getByRole('button', { name: 'Save reviewed changes', exact: true }).click();
  await expect
    .poll(() => requests.filter((r) => r.path === '/api/remote-config/patch').length)
    .toBe(1);
  expect(requests.find((r) => r.path === '/api/remote-config/patch')?.body).toMatchObject({
    scope: 'NETWORK',
    expectedRevision: 2,
  });
});
test('schema incompatibility prevents editing and remote live operations require explicit host selection', async ({
  page,
}) => {
  await mockApi(page);
  await page.route('**/api/remote-config/snapshot?**', (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        ok: true,
        data: { ...remoteSnapshot(), schemaCompatible: false },
        warnings: [],
      }),
    }),
  );
  await page.goto('/#motd');
  await page.getByLabel('Server context', { exact: true }).selectOption('stoneblock');
  await expect(page.getByLabel('Welcome message', { exact: true })).toBeDisabled();
  await page.getByRole('link', { name: 'Menus', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'This operation requires the dashboard host' }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Select dashboard host' }).click();
  await expect(page.getByRole('button', { name: 'New menu', exact: true })).toBeVisible();
});
test('API failures preserve the draft and display safe error text', async ({ page }) => {
  await mockApi(page);
  await page.route('**/api/remote-config/patch', (route) =>
    route.fulfill({
      status: 409,
      contentType: 'application/json',
      body: JSON.stringify({
        ok: false,
        data: { accepted: [], rejected: [{ key: '<revision>', reason: 'Configuration changed' }] },
        error: {
          code: 'stale_revision',
          message: '<img src=x onerror=alert(1)> revision conflict',
        },
      }),
    }),
  );
  await page.goto('/#motd');
  await page.getByLabel('Server context', { exact: true }).selectOption('stoneblock');
  await page.getByLabel('Welcome message', { exact: true }).fill('Retained draft');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'revision conflict' })).toBeVisible();
  await expect(page.getByLabel('Welcome message', { exact: true })).toHaveValue('Retained draft');
  await expect(page.locator('img')).toHaveCount(0);
});
test('all routes render, light/dark preference persists and responsive layout is accessible', async ({
  page,
}) => {
  await mockApi(page);
  await page.goto('/');
  await page.getByLabel('Server context', { exact: true }).selectOption('host');
  for (const name of [
    'Players',
    'Modules & configuration',
    'MOTD & welcome',
    'Chat',
    'Announcements',
    'Teleports',
    'Tablist',
    'Restart schedule',
    'Menus',
    'Holograms',
    'Custom commands',
    'Command settings',
    'Cooldowns & warmups',
    'Permissions',
    'Moderation',
    'Support tickets',
    'Storage',
    'Storage configuration',
    'Audit log',
    'Discord integration',
    'Dashboard settings',
  ]) {
    await page.getByRole('link', { name, exact: true }).click();
    await expect(page.locator('main h1')).toHaveText(name);
    await expect(page.getByText('Could not load this view', { exact: true })).toHaveCount(0);
  }
  await page.getByRole('button', { name: 'Switch to dark theme' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.getByRole('link', { name: 'Overview', exact: true }).click();
  const accessibility = await new AxeBuilder({ page }).analyze();
  expect(accessibility.violations).toEqual([]);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('button', { name: 'Open navigation' }).click();
  await expect(page.getByRole('link', { name: 'Players', exact: true })).toBeVisible();
});
test('one-time login token leaves the URL after a successful exchange', async ({ page }) => {
  const requests = await mockApi(page);
  await page.goto('/?token=single-use');
  await page.getByRole('heading', { name: 'Network overview' }).waitFor();
  expect(page.url()).not.toContain('token=');
  expect(requests.find((r) => r.path === '/api/auth/login')?.body).toEqual({ token: 'single-use' });
});
test('session expiry retains drafts for reauthentication', async ({ page }) => {
  await mockApi(page);
  await page.route('**/api/remote-config/patch', (route) =>
    route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        ok: false,
        error: { code: 'unauthorized', message: 'Session expired' },
      }),
    }),
  );
  await page.goto('/#motd');
  await page.getByLabel('Server context', { exact: true }).selectOption('stoneblock');
  await page.getByLabel('Welcome message', { exact: true }).fill('Keep me');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('Your drafts remain');
  await page.getByLabel('One-time login code', { exact: true }).fill('new-token');
  await page.getByRole('button', { name: 'Sign in again' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByLabel('Welcome message', { exact: true })).toHaveValue('Keep me');
});
