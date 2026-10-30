import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mockApi, capabilities } from './fixtures';
const uuid = '12345678-1234-4234-8234-123456789abc';
const a = (id: string, node: string, contexts: Record<string, string> = {}, denied = false) => ({
  id,
  node,
  contexts,
  denied,
  expiresAtMs: null,
});
async function setup(
  page: Page,
  {
    readonly = false,
    failAt = 0,
    unknown = false,
    conflict = false,
    membershipExpiry = Date.now() + 3600123,
  } = {},
) {
  await mockApi(page);
  const writes: { route: string; body: any }[] = [];
  const groups = [
    {
      name: 'default',
      description: 'Default group',
      prefix: '&a[Member] ',
      suffix: '',
      weight: 10,
      parents: ['staff'],
      permissionCount: 2,
      assignments: [
        a('g-host', 'paradigm.home', { server: 'host' }),
        a('g-remote', 'paradigm.home', { server: 'stoneblock' }, true),
      ],
      members: [{ uuid, name: 'Alex', contexts: { server: 'host' }, expiresAtMs: null }],
      membershipCount: 1,
    },
    {
      name: 'staff',
      description: 'Staff group',
      prefix: '',
      suffix: '',
      weight: 50,
      parents: [],
      permissionCount: 1,
      assignments: [a('g-staff', 'paradigm.fly')],
    },
  ];
  await page.route('**/api/auth/status', (route) =>
    route.fulfill({
      json: {
        ok: true,
        data: {
          authenticated: true,
          requireLogin: true,
          csrfToken: 'csrf',
          principal: { name: 'Admin', uuid: 'admin', console: true },
          capabilities: { ...capabilities, mutations: { permissions: !readonly } },
          serverId: 'host',
          networkActive: true,
        },
      },
    }),
  );
  await page.route('**/api/permissions/**', async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      path = url.pathname;
    if (request.method() === 'POST') {
      writes.push({ route: path, body: request.postDataJSON() });
      if (writes.length === failAt) {
        await route.fulfill({
          status: conflict ? 409 : unknown ? 503 : 400,
          json: {
            ok: false,
            error: {
              code: conflict
                ? 'persistence_conflict'
                : unknown
                  ? 'storage_unavailable'
                  : 'validation_failed',
              message: 'Rejected test mutation',
            },
          },
        });
        return;
      }
      await route.fulfill({ json: { ok: true, data: { applied: true, message: 'Applied' } } });
      return;
    }
    let data: any = {};
    if (path.endsWith('/summary'))
      data = {
        internalEnabled: true,
        sharedStorage: true,
        contextKeys: ['server', 'network', 'world', 'dimension'],
        serverId: 'host',
        networkId: 'network',
      };
    else if (path.endsWith('/groups')) data = { groups };
    else if (path.endsWith('/group'))
      data = { group: groups.find((g) => g.name === url.searchParams.get('id')) };
    else if (path.endsWith('/users'))
      data = { users: [{ uuid, name: 'Alex', groups: 1, permissions: 1 }], total: 1 };
    else if (path.endsWith('/user'))
      data = {
        user: {
          uuid,
          info: {
            assignments: [
              {
                ...a('u-one', 'custom.node'),
                contexts: { contexts: { world: 'minecraft:overworld' } },
              },
            ],
            groupAssignments: [
              {
                id: 'member-one',
                value: 'default',
                contexts: { contexts: { server: 'host' } },
                denied: false,
                expiresAtMs: membershipExpiry,
              },
            ],
            implicitGroups: ['default'],
            meta: { primaryGroup: 'default' },
          },
        },
      };
    else if (path.endsWith('/effective'))
      data = {
        entries: [
          {
            node: 'paradigm.home',
            allowed: true,
            sourceType: 'group',
            sourceName: 'default',
            rule: 'paradigm.*',
            groups: ['default'],
          },
          { node: 'custom.unset', allowed: null, sourceType: 'none', rule: null },
        ],
        total: 2,
      };
    else if (path.endsWith('/tracks'))
      data = { tracks: [{ name: 'main', groups: ['default', 'staff'] }] };
    else if (path.endsWith('/nodes'))
      data = {
        nodes: [
          { node: 'paradigm.home', description: 'Use homes', source: 'paradigm' },
          { node: 'paradigm.fly', description: 'Flight', source: 'paradigm' },
        ],
        total: 2,
      };
    await route.fulfill({ json: { ok: true, data } });
  });
  await page.goto('/permissions');
  await page.getByRole('heading', { name: 'Permission workspace' }).waitFor();
  return writes;
}
async function chooseGroup(page: Page) {
  await page.getByRole('button', { name: /^default 2 permissions/ }).click();
  await page.getByRole('heading', { name: 'Direct permission assignments' }).waitFor();
}
async function review(page: Page) {
  await page.getByRole('button', { name: /Review & apply/ }).click();
  await expect(page.getByRole('dialog', { name: 'Review operation' })).toBeVisible();
  await page.getByRole('button', { name: 'Apply reviewed changes', exact: true }).click();
}
test('compact rows retain distinct IDs and remove only the selected context', async ({ page }) => {
  const writes = await setup(page);
  await chooseGroup(page);
  await expect(page.getByRole('row').filter({ hasText: 'paradigm.home' })).toHaveCount(2);
  await page.getByRole('button', { name: 'Remove paradigm.home g-remote', exact: true }).click();
  await review(page);
  await expect.poll(() => writes.length).toBe(1);
  expect(writes[0].body).toMatchObject({
    group: 'default',
    assignmentId: 'g-remote',
    contexts: { server: 'stoneblock' },
    confirmed: true,
  });
  expect(writes[0].body).toHaveProperty('permission', 'paradigm.home');
});
test('multi-node autocomplete accepts custom denies, contexts and temporary duration', async ({
  page,
}) => {
  const writes = await setup(page);
  await page.getByLabel('Server context', { exact: true }).selectOption('stoneblock');
  await chooseGroup(page);
  await page.getByRole('button', { name: 'Add permission', exact: true }).click();
  const input = page.getByRole('combobox', { name: 'Permission nodes' });
  await input.fill('paradigm');
  await page.getByRole('option', { name: /paradigm.fly/ }).waitFor();
  await input.press('ArrowDown');
  await input.press('Enter');
  await input.fill('custom.extra');
  await input.press('Enter');
  await page.getByRole('combobox', { name: 'Value', exact: true }).selectOption('deny');
  await page.getByLabel('World', { exact: true }).fill('minecraft:overworld');
  await page.getByLabel('Dimension', { exact: true }).fill('DIM7');
  await page.getByRole('combobox', { name: 'Expiration', exact: true }).selectOption('duration');
  await page.getByLabel('Duration', { exact: true }).fill('2h');
  await page.getByRole('button', { name: 'Stage assignments' }).click();
  expect(writes).toHaveLength(0);
  await review(page);
  await expect.poll(() => writes.length).toBe(2);
  for (const { body } of writes)
    expect(body).toMatchObject({
      group: 'default',
      denied: true,
      duration: '2h',
      scope: 'custom',
      contexts: { server: 'stoneblock', world: 'minecraft:overworld', dimension: 'DIM7' },
    });
});
test('editing uses add then precise removal and keeps partial failures staged', async ({
  page,
}) => {
  const writes = await setup(page, { failAt: 2 });
  await chooseGroup(page);
  await page
    .getByRole('row')
    .filter({ hasText: 'server: host' })
    .getByRole('button', { name: 'Edit', exact: true })
    .click();
  await page.getByRole('combobox', { name: 'Value', exact: true }).selectOption('deny');
  await page.getByRole('button', { name: 'Stage replacement' }).click();
  await review(page);
  await expect(page.getByText(/1 of 2 changes acknowledged/)).toBeVisible();
  expect(writes[0].route).toContain('/add');
  expect(writes[1].body.assignmentId).toBe('g-host');
  await expect(page.getByRole('button', { name: 'Review & apply (1)' })).toBeVisible();
});
test('unknown outcomes block retries and never automatically resend', async ({ page }) => {
  const writes = await setup(page, { failAt: 1, unknown: true });
  await chooseGroup(page);
  await page.getByRole('button', { name: 'Remove paradigm.home g-host', exact: true }).click();
  await review(page);
  await expect(page.getByText(/last outcome is unknown/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Review & apply (1)' })).toBeDisabled();
  expect(writes).toHaveLength(1);
});
test('definition conflicts block retries and distinguish rejection from unknown outcomes', async ({
  page,
}) => {
  const writes = await setup(page, { failAt: 1, conflict: true });
  await chooseGroup(page);
  await page.getByRole('button', { name: 'Remove paradigm.home g-host', exact: true }).click();
  await review(page);
  await expect(page.getByText(/last definition change was rejected/)).toBeVisible();
  await expect(page.getByText(/last outcome is unknown/)).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Review & apply (1)' })).toBeDisabled();
  expect(writes).toHaveLength(1);
});
test('inheritance stays read-only and cycle candidates are excluded', async ({ page }) => {
  const writes = await setup(page);
  await chooseGroup(page);
  await page.getByRole('button', { name: 'Inheritance', exact: true }).click();
  await expect(page.getByText('From staff', { exact: true })).toBeVisible();
  await expect(
    page.getByLabel('Parent group').getByRole('option', { name: 'default', exact: true }),
  ).toHaveCount(0);
  await page.getByRole('button', { name: 'Remove parent' }).click();
  await review(page);
  expect(writes[0].body).toMatchObject({ group: 'default', parent: 'staff' });
});
test('user selection uses UUIDs and effective unset is neutral', async ({ page }) => {
  await setup(page);
  await page.getByRole('button', { name: 'Users', exact: true }).click();
  await page.getByRole('button', { name: /^Alex/ }).click();
  await expect(page.getByRole('heading', { name: 'Alex', exact: true })).toBeVisible();
  await expect(page.getByText('world: minecraft:overworld', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Groups', exact: true }).last().click();
  await expect(page.getByText('server: host', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Effective permissions', exact: true }).click();
  await expect(page.getByText('Unset / unresolved', { exact: true })).toBeVisible();
  await expect(page.getByText('From default', { exact: true })).toHaveCount(0);
});
test('metadata previews stage supported fields individually, with unsaved guards', async ({
  page,
}) => {
  const writes = await setup(page);
  await chooseGroup(page);
  await page.getByRole('button', { name: 'Metadata', exact: true }).click();
  await page.getByLabel('Description', { exact: true }).fill('Updated');
  await page.getByRole('button', { name: 'Users', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Review operation' })).toBeVisible();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await page.getByLabel('Group weight', { exact: true }).fill('42');
  await page.getByRole('button', { name: 'Stage metadata changes' }).click();
  await page.keyboard.press('Control+s');
  await page.getByRole('button', { name: 'Apply reviewed changes', exact: true }).click();
  await expect.poll(() => writes.length).toBe(2);
  expect(writes.map((w) => w.body.metadata)).toEqual([
    { description: 'Updated' },
    { weight: '42' },
  ]);
});
test('track moves use native one-based positions and destructive changes require review', async ({
  page,
}) => {
  const writes = await setup(page);
  await page.getByRole('button', { name: 'Tracks', exact: true }).click();
  await page.getByRole('button', { name: /^main/ }).click();
  await page.getByRole('button', { name: 'Move staff up' }).click();
  await review(page);
  expect(writes[0].body).toMatchObject({ track: 'main', group: 'staff', position: 1 });
});
test('dangerous permissions require explicit review; cancellation sends no mutation', async ({
  page,
}) => {
  const writes = await setup(page);
  await chooseGroup(page);
  await page.getByRole('button', { name: 'Add permission', exact: true }).click();
  await page.getByRole('combobox', { name: 'Permission nodes' }).fill('paradigm.*');
  await page.getByRole('button', { name: 'Stage assignments' }).click();
  await page.keyboard.press('Control+s');
  await expect(page.getByRole('dialog')).toContainText('paradigm.*');
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(writes).toHaveLength(0);
});
test('read-only compact workspace is accessible in light, dark and mobile layouts', async ({
  page,
}) => {
  await setup(page, { readonly: true });
  await chooseGroup(page);
  await expect(page.getByRole('button', { name: 'Add permission', exact: true })).toBeDisabled();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.screenshot({ path: '/tmp/paradigm-permissions-light.png', fullPage: true });
  await page.getByRole('button', { name: 'Switch to dark theme' }).click();
  await page.waitForFunction(
    () =>
      getComputedStyle(document.querySelector('.permission-workspace-heading button')!)
        .backgroundColor === 'rgb(27, 31, 41)',
  );
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.screenshot({ path: '/tmp/paradigm-permissions-dark.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.screenshot({ path: '/tmp/paradigm-permissions-mobile.png', fullPage: true });
});

test('editing membership preserves the exact original expiration timestamp', async ({ page }) => {
  const membershipExpiry = Date.now() + 3600123;
  const writes = await setup(page, { membershipExpiry });
  await page.getByRole('button', { name: 'Users', exact: true }).click();
  await page.getByRole('button', { name: /^Alex/ }).click();
  await page.getByRole('heading', { name: 'Alex', exact: true }).waitFor();
  await page.getByRole('button', { name: 'Groups', exact: true }).last().click();
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByLabel('Network', { exact: true }).fill('network');
  await page.getByRole('button', { name: 'Stage replacement' }).click();
  await review(page);
  await expect.poll(() => writes.length).toBe(2);
  expect(writes[0].body.expiresAtMs).toBe(membershipExpiry);
  expect(writes[1].body).toMatchObject({ user: uuid, assignmentId: 'member-one' });
});
test('bulk removal retains individual IDs and staged changes guard server selection', async ({
  page,
}) => {
  const writes = await setup(page);
  await chooseGroup(page);
  await page.getByLabel('Select visible assignments').check();
  await page.getByRole('button', { name: 'Stage removal (2)' }).click();
  await page.getByLabel('Server context', { exact: true }).selectOption('stoneblock');
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(writes).toHaveLength(0);
  await review(page);
  await expect.poll(() => writes.length).toBe(2);
  expect(writes.map((w) => w.body.assignmentId)).toEqual(['g-host', 'g-remote']);
});
test('large assignment sets paginate and search without rendering every node', async ({ page }) => {
  await setup(page);
  await page.route('**/api/permissions/group?**', (route) =>
    route.fulfill({
      json: {
        ok: true,
        data: {
          group: {
            name: 'default',
            parents: [],
            weight: 1,
            assignments: Array.from({ length: 2000 }, (_, i) => a('bulk-' + i, 'bulk.node.' + i)),
          },
        },
      },
    }),
  );
  await chooseGroup(page);
  await expect(page.getByRole('row')).toHaveCount(51);
  await page.getByLabel('Search assignments', { exact: true }).fill('bulk.node.1999');
  await expect(page.getByRole('row')).toHaveCount(2);
  await expect(page.getByRole('row').filter({ hasText: 'bulk.node.1999' })).toBeVisible();
});
test('user track changes use canonical UUID, selected context and supported promotion flags', async ({
  page,
}) => {
  const writes = await setup(page);
  await page.getByLabel('Server context', { exact: true }).selectOption('stoneblock');
  await page.getByRole('button', { name: 'Users', exact: true }).click();
  await page.getByRole('button', { name: /^Alex/ }).click();
  await page.getByRole('heading', { name: 'Alex', exact: true }).waitFor();
  await page.getByRole('button', { name: 'Tracks', exact: true }).last().click();
  await page.getByRole('combobox', { name: 'Track', exact: true }).selectOption('main');
  await page.getByLabel('Do not add an unranked user to the first rank').check();
  await page.getByRole('button', { name: 'Stage rank change' }).click();
  await review(page);
  expect(writes[0].body).toMatchObject({
    user: uuid,
    track: 'main',
    dontAddToFirst: true,
    contexts: { server: 'stoneblock' },
  });
  expect(writes[0].body).not.toHaveProperty('permanent');
});

test('a replacement whose original is already absent completes without retrying either operation', async ({
  page,
}) => {
  const writes = await setup(page);
  await page.route('**/api/permissions/group/permission/remove', (route) =>
    route.fulfill({
      status: 404,
      json: {
        ok: false,
        error: { code: 'assignment_not_found', message: 'Original assignment already absent' },
      },
    }),
  );
  await chooseGroup(page);
  await page
    .getByRole('row')
    .filter({ hasText: 'server: host' })
    .getByRole('button', { name: 'Edit', exact: true })
    .click();
  await page.getByRole('combobox', { name: 'Value', exact: true }).selectOption('deny');
  await page.getByRole('button', { name: 'Stage replacement' }).click();
  await review(page);
  await expect(
    page.getByText(/1 original replacement assignments were already absent/),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: /Review & apply/ })).toHaveCount(0);
  expect(writes).toHaveLength(1);
});
test('an unreadable mutation outcome blocks the queue without automatic retry', async ({
  page,
}) => {
  await setup(page);
  let count = 0;
  await page.route('**/api/permissions/group/permission/remove', (route) => {
    count++;
    return route.fulfill({ json: { ok: true, data: {} } });
  });
  await chooseGroup(page);
  await page.getByRole('button', { name: 'Remove paradigm.home g-host', exact: true }).click();
  await review(page);
  await expect(page.getByText(/last outcome is unknown/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Review & apply (1)' })).toBeDisabled();
  expect(count).toBe(1);
});
test('member search uses paginated backend results', async ({ page }) => {
  await setup(page);
  const searches: string[] = [];
  await page.route('**/api/permissions/group/members?**', (route) => {
    const url = new URL(route.request().url());
    searches.push(url.searchParams.get('query') || '');
    return route.fulfill({
      json: {
        ok: true,
        data: {
          members: [
            { uuid, name: 'Remote Member', contexts: { server: 'stoneblock' }, expiresAtMs: null },
          ],
          total: 1200,
          page: 1,
          pageSize: 50,
        },
      },
    });
  });
  await chooseGroup(page);
  await page.getByRole('button', { name: 'Members', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Remote Member' })).toBeVisible();
  await page.getByLabel('Search group members').fill('Remote');
  await expect.poll(() => searches.at(-1)).toBe('Remote');
});

test('group rename is staged with the original identifier and applied through review', async ({ page }) => {
  const writes = await setup(page);
  await chooseGroup(page);
  await page.getByRole('button', { name: 'Rename group', exact: true }).click();
  await page.getByLabel('New group name', { exact: true }).fill('members');
  await page.getByRole('button', { name: 'Stage rename', exact: true }).click();
  expect(writes).toHaveLength(0);
  await review(page);
  await expect.poll(() => writes.length).toBe(1);
  expect(writes[0]).toMatchObject({ route: '/api/permissions/group/rename', body: { group: 'default', target: 'members' } });
});

test('group rename rejects a known collision before staging', async ({ page }) => {
  const writes = await setup(page);
  await chooseGroup(page);
  await page.getByRole('button', { name: 'Rename group', exact: true }).click();
  await page.getByLabel('New group name', { exact: true }).fill('staff');
  await page.getByRole('button', { name: 'Stage rename', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Choose a name that is not already in use.');
  expect(writes).toHaveLength(0);
});

test('read-only group editor cannot open rename', async ({ page }) => {
  await setup(page, { readonly: true });
  await chooseGroup(page);
  await expect(page.getByRole('button', { name: 'Rename group', exact: true })).toBeDisabled();
});
