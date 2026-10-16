import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mockApi, host, remote } from './fixtures';

const uuid = '12345678-1234-4234-8234-123456789abc';
const longGroup = 'moderators_' + 'a'.repeat(53);
async function fixture(page: Page) {
  const requests = await mockApi(page);
  await page.route('**/api/players?**', (route) =>
    route.fulfill({
      json: {
        ok: true,
        data: {
          players: Array.from({ length: 25 }, (_, i) => ({
            name: 'Player_' + i,
            uuid: uuid.slice(0, -2) + String(i).padStart(2, '0'),
            online: i % 2 === 0,
            lastSeenMs: Date.now(),
            playtimeMs: 7200000,
          })),
          matchTotal: 75,
        },
      },
    }),
  );
  await page.route('**/api/permissions/**', (route) => {
    const url = new URL(route.request().url());
    const group = {
      name: longGroup,
      description: 'Moderators with contextual assignments',
      permissionCount: 1,
      parents: [],
      assignments: [
        {
          id: 'one',
          node: 'paradigm.' + 'permission'.repeat(20),
          denied: false,
          contexts: { server: 'host' },
          expiresAtMs: null,
        },
      ],
    };
    let data: unknown = {};
    if (url.pathname.endsWith('/summary'))
      data = { internalEnabled: true, sharedStorage: true, contextKeys: ['server'] };
    if (url.pathname.endsWith('/groups'))
      data = {
        groups: Array.from({ length: 50 }, (_, i) => ({
          ...group,
          name: i === 0 ? longGroup : 'group_' + i,
        })),
      };
    if (url.pathname.endsWith('/group')) data = { group };
    if (url.pathname.endsWith('/users'))
      data = {
        users: Array.from({ length: 50 }, (_, i) => ({
          uuid: uuid.slice(0, -2) + String(i).padStart(2, '0'),
          name: 'Player_' + i,
        })),
        total: 125,
      };
    if (url.pathname.endsWith('/tracks')) data = { tracks: [] };
    return route.fulfill({ json: { ok: true, data } });
  });
  await page.route('**/api/storage/status', (route) =>
    route.fulfill({
      json: {
        ok: true,
        data: {
          selectedProvider: 'mysql',
          activeProvider: 'json',
          configuredDataProvider: 'MySQL / MariaDB',
          activeDataProvider: 'JSON',
          repositoriesAvailable: true,
          dataLocation: '/server/' + 'a-long-runtime-directory/'.repeat(6) + 'data',
          serverIdentity: { serverId: 'host', serverName: 'Paradigm Hub', networkId: 'network' },
          migrationVersion: 18,
          fallbackActive: true,
          fallbackReason: 'SQL connection failed.',
          fallbackWarning: 'Fallback data exists.',
          migrationRecommendation: 'Review data before changing providers.',
          dependencyMode: 'runtime',
          runtimeLibraryCachePath: '/server/' + 'libs/'.repeat(20),
          sqliteDriverState: 'not needed',
          mysqlDriverState: 'load failed',
          sql: { host: 'database.internal', database: 'paradigm', passwordSet: true },
        },
      },
    }),
  );
  return requests;
}
async function fits(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  for (const selector of [
    '.main-shell',
    '.page-content',
    '.permission-master',
    '.permission-editor',
    '.topbar',
  ]) {
    for (const element of await page.locator(selector).all())
      expect(await element.evaluate((e) => e.scrollWidth <= e.clientWidth + 1), selector).toBe(
        true,
      );
  }
}
for (const width of [1440, 1024, 768, 390, 320]) {
  test(`directories and Storage stay usable at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await fixture(page);
    await page.goto('/players');
    await page.getByRole('region', { name: 'Player directory', exact: true }).waitFor();
    await fits(page);
    const directory = page.getByRole('region', { name: 'Player directory', exact: true });
    if (width < 760) {
      expect(await directory.evaluate((e) => e.scrollWidth > e.clientWidth)).toBe(true);
      await directory.focus();
      await page.keyboard.press('ArrowRight');
      await expect.poll(() => directory.evaluate((e) => e.scrollLeft)).toBeGreaterThan(0);
    }
    await directory.getByRole('button', { name: 'Open', exact: true }).last().click();
    await expect(page.getByRole('heading', { name: 'Player_24', exact: true })).toBeVisible();
    await page.screenshot({ path: `/tmp/paradigm-usability-players-${width}.png`, fullPage: true });

    await page.goto('/permissions');
    await page.getByRole('button', { name: longGroup, exact: false }).click();
    await page.getByRole('heading', { name: longGroup, exact: true }).waitFor();
    await fits(page);
    const targets = page.locator('.permission-target-list');
    expect(await targets.evaluate((e) => e.scrollHeight > e.clientHeight)).toBe(true);
    await targets.getByRole('button', { name: 'group_49 ', exact: false }).scrollIntoViewIfNeeded();
    expect(await targets.evaluate((e) => e.scrollTop)).toBeGreaterThan(0);
    await targets.getByRole('button', { name: longGroup, exact: false }).scrollIntoViewIfNeeded();
    await page.screenshot({
      path: `/tmp/paradigm-usability-permissions-${width}.png`,
      fullPage: true,
    });
    await page.getByRole('button', { name: 'Users', exact: true }).click();
    await targets.getByRole('button', { name: 'Player_0 ', exact: false }).waitFor();
    await fits(page);
    await page.getByRole('button', { name: 'Next', exact: true }).click();
    await expect(page.getByText('125 results · Page 2 of 3', { exact: true })).toBeVisible();
    await fits(page);

    await page.goto('/storage');
    await page.getByLabel('Server context', { exact: true }).selectOption('host');
    await page.getByRole('heading', { name: 'Storage status', exact: true }).waitFor();
    await fits(page);
    await expect(page.getByText('Fallback storage is active.', { exact: false })).toBeVisible();
    await expect(page.getByText('database.internal', { exact: true })).toBeHidden();
    await expect(page.getByRole('button', { name: 'Preview migration', exact: true })).toBeHidden();
    await page.screenshot({ path: `/tmp/paradigm-usability-storage-${width}.png`, fullPage: true });
    await page.getByText('Technical diagnostics', { exact: true }).click();
    await expect(page.getByText('database.internal', { exact: true })).toBeVisible();
    await page.getByText('Migration preview', { exact: true }).click();
    await expect(
      page.getByRole('button', { name: 'Preview migration', exact: true }),
    ).toBeVisible();
    await fits(page);
    if (width === 390 || width === 1440)
      expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  });
}

test('server selector handles long names; navigation has meaningful groups and scrolls independently', async ({
  page,
}) => {
  await fixture(page);
  await page.route('**/api/servers', (route) =>
    route.fulfill({
      json: {
        ok: true,
        data: {
          servers: [
            host,
            {
              ...remote,
              serverName: 'An exceptionally long remote Minecraft server name '.repeat(5),
              serverId: 'remote-' + 'a'.repeat(64),
            },
          ],
          networkActive: true,
        },
      },
    }),
  );
  await page.setViewportSize({ width: 390, height: 640 });
  await page.goto('/');
  await page.getByLabel('Server context', { exact: true }).selectOption('remote-' + 'a'.repeat(64));
  await fits(page);
  await expect(page.getByText('Online · telemetry not reported', { exact: true })).toBeVisible();
  for (const text of ['Dashboard 2.0', 'Server workspace', 'Embedded server dashboard'])
    await expect(page.getByText(text, { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Open navigation' }).click();
  await expect(page.getByRole('heading', { name: 'Players & access', exact: true })).toBeVisible();
  await page
    .getByRole('link', { name: 'Dashboard settings', exact: true })
    .scrollIntoViewIfNeeded();
  expect(await page.locator('.sidebar nav').evaluate((e) => e.scrollTop)).toBeGreaterThan(0);
  await page.getByRole('searchbox', { name: 'Find a page' }).fill('no-such-page');
  await expect(page.getByText('No matching pages. Try another search.')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: 'Open navigation' })).toBeFocused();
});

test('fresh telemetry, missing telemetry and offline servers are distinct', async ({ page }) => {
  await fixture(page);
  await page.route('**/api/servers', (route) =>
    route.fulfill({
      json: {
        ok: true,
        data: {
          servers: [
            host,
            remote,
            {
              ...remote,
              serverId: 'reported',
              serverName: 'Reporting server',
              runtimeDetailsAvailable: true,
              onlinePlayers: 0,
              enabledModuleCount: 14,
              runtimeUpdatedAtMs: Date.now(),
            },
            {
              ...remote,
              serverId: 'offline',
              serverName: 'Offline server',
              online: false,
              runtimeDetailsAvailable: false,
              onlinePlayers: 99,
              enabledModuleCount: 14,
            },
          ],
          networkActive: true,
        },
      },
    }),
  );
  await page.goto('/');
  const selector = page.getByLabel('Server context', { exact: true });
  await selector.selectOption('stoneblock');
  await expect(page.getByText('Online · telemetry not reported', { exact: true })).toBeVisible();
  await expect(page.locator('.metric').filter({ hasText: 'Online players' })).toContainText(
    'Not reported',
  );
  await selector.selectOption('reported');
  await expect(page.getByText('Runtime telemetry received', { exact: true })).toBeVisible();
  await expect(
    page.locator('.metric').filter({ hasText: 'Online players' }).locator('strong'),
  ).toHaveText('0');
  await selector.selectOption('offline');
  await expect(page.getByText('Server offline', { exact: true })).toBeVisible();
  await expect(page.locator('.metric').filter({ hasText: 'Online players' })).not.toContainText(
    '99',
  );
});

test('Storage retains diagnostic and migration operations with the original payloads', async ({
  page,
}) => {
  const requests = await fixture(page);
  await page.goto('/storage');
  await page.getByLabel('Server context', { exact: true }).selectOption('host');
  await page.getByRole('button', { name: 'Test connection', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Connection test', exact: true })).toBeVisible();
  await page.getByText('Technical diagnostics', { exact: true }).click();
  await page.getByRole('button', { name: 'Health report', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Health report', exact: true })).toBeVisible();
  await page.getByText('Migration preview', { exact: true }).click();
  await page.getByLabel('Conflict policy', { exact: true }).selectOption('fail');
  await page.getByRole('button', { name: 'Preview migration', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Migration preview', exact: true })).toBeVisible();
  expect(requests.find((r) => r.path === '/api/storage/migration/dry-run')?.body).toEqual({
    source: 'json',
    target: 'sql',
    policy: 'fail',
  });
  expect(requests.find((r) => r.path === '/api/storage/test')?.body).toEqual({});
  expect(requests.some((r) => r.path === '/api/storage/health')).toBe(true);
});
