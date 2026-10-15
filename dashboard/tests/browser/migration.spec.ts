import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mockApi, remoteSnapshot, capabilities } from './fixtures';
test('menu slots and registered actions save a complete definition', async ({ page }) => {
  const requests = await mockApi(page);
  await page.goto('/menus');
  await page.getByLabel('Server context', { exact: true }).selectOption('host');
  await page
    .getByRole('button', { name: 'main', exact: false })
    .filter({ hasText: 'Main menu' })
    .click();
  await page.getByRole('button', { name: 'Slot 0', exact: true }).click();
  await page.getByLabel('itemId', { exact: true }).fill('minecraft:diamond');
  await page.getByRole('button', { name: 'Save definition', exact: true }).click();
  await expect.poll(() => requests.filter((r) => r.path === '/api/menus/update').length).toBe(1);
  expect(requests.find((r) => r.path === '/api/menus/update')?.body).toMatchObject({
    id: 'main',
    definition: { slots: [{ slot: 0, item: { itemId: 'minecraft:diamond' } }] },
  });
});
test('custom commands expose registered menu actions and preserve nested conditional branches', async ({
  page,
}) => {
  const requests = await mockApi(page);
  await page.goto('/customCommands');
  await page.getByLabel('Server context', { exact: true }).selectOption('host');
  await page
    .getByRole('button', { name: 'hello', exact: false })
    .filter({ hasText: 'Say hello' })
    .click();
  await page.getByRole('button', { name: 'Add action', exact: true }).click();
  await page
    .getByRole('combobox', { name: 'Action type', exact: true })
    .selectOption('conditional');
  await page.getByRole('button', { name: 'Add action', exact: true }).first().click();
  await page
    .getByRole('combobox', { name: 'Action type', exact: true })
    .nth(1)
    .selectOption('open_menu');
  await page.getByLabel('Menu', { exact: true }).fill('main');
  await page.getByRole('button', { name: 'Save definition', exact: true }).click();
  await expect
    .poll(() => requests.filter((r) => r.path === '/api/custom-commands/update').length)
    .toBe(1);
  expect(requests.find((r) => r.path === '/api/custom-commands/update')?.body).toMatchObject({
    command: {
      actions: [
        { type: 'conditional', on_success: [{ type: 'open_menu', menu: 'main' }], on_failure: [] },
      ],
    },
  });
});
test('a pending save locks navigation and server selection', async ({ page }) => {
  await mockApi(page);
  let release: () => void = () => {};
  await page.route('**/api/remote-config/patch', async (route) => {
    await new Promise<void>((resolve) => (release = resolve));
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        ok: true,
        data: {
          saved: true,
          revision: 5,
          result: { accepted: ['motd.message'], rejected: [], warnings: [] },
        },
      }),
    });
  });
  await page.goto('/motd');
  await page.getByLabel('Server context', { exact: true }).selectOption('stoneblock');
  await page.getByLabel('Welcome message', { exact: true }).fill('Pending');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByLabel('Server context', { exact: true })).toBeDisabled();
  await page.getByRole('link', { name: 'Players', exact: true }).click();
  await expect(page.locator('main h1')).toHaveText('MOTD & welcome');
  release();
  await expect(page.getByLabel('Server context', { exact: true })).toBeEnabled();
});
test('offline saves are stored without claiming live application', async ({ page }) => {
  await mockApi(page);
  await page.route('**/api/remote-config/snapshot?**', (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        ok: true,
        data: { ...remoteSnapshot('offline'), online: false },
        warnings: [],
      }),
    }),
  );
  await page.goto('/motd');
  await page.getByLabel('Server context', { exact: true }).selectOption('offline');
  await expect(page.getByText('Offline · pending synchronization', { exact: true })).toBeVisible();
  await page.getByLabel('Welcome message', { exact: true }).fill('For next startup');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByText('Saved to shared storage.', { exact: false })).toBeVisible();
  await expect(page.getByText('Applied configuration', { exact: true })).toHaveCount(0);
});
test('view permissions never enable configuration mutations', async ({ page }) => {
  const requests = await mockApi(page);
  await page.route('**/api/auth/status', (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        ok: true,
        data: {
          authenticated: true,
          requireLogin: true,
          csrfToken: 'csrf',
          serverId: 'host',
          capabilities: {
            ...capabilities,
            config: { ...capabilities.config, motd: { view: true, edit: false } },
            mutations: { network: false },
          },
        },
      }),
    }),
  );
  await page.goto('/motd');
  await page.getByLabel('Server context', { exact: true }).selectOption('stoneblock');
  await expect(page.getByLabel('Welcome message', { exact: true })).toBeDisabled();
  expect(requests.some((r) => r.path === '/api/remote-config/patch')).toBe(false);
});
test('light, dark and mobile reference surfaces remain accessible', async ({ page }) => {
  await mockApi(page);
  await page.goto('/');
  await page.getByRole('heading', { name: 'Network overview' }).waitFor();
  await page.screenshot({ path: '/tmp/paradigm-dashboard-light.png', fullPage: true });
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.getByRole('button', { name: 'Switch to dark theme' }).click();
  await page.screenshot({ path: '/tmp/paradigm-dashboard-dark.png', fullPage: true });
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: '/tmp/paradigm-dashboard-mobile.png', fullPage: true });
  await page.getByRole('button', { name: 'Open navigation' }).click();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: 'Open navigation' })).toBeVisible();
});
