import type { Page } from '@playwright/test';
export const capabilities = {
  pages: Object.fromEntries(
    [
      'overview',
      'servers',
      'players',
      'general',
      'motd',
      'chat',
      'announcements',
      'restart',
      'teleports',
      'tablist',
      'menus',
      'holograms',
      'customCommands',
      'commands',
      'cooldowns',
      'permissions',
      'moderation',
      'tickets',
      'storage',
      'storageConfig',
      'audit',
      'discord',
      'dashboard',
    ].map((k) => [k, true]),
  ),
  config: Object.fromEntries(
    [
      'general',
      'motd',
      'chat',
      'announcements',
      'restart',
      'teleports',
      'tablist',
      'commands',
      'cooldowns',
      'dashboard',
      'discord',
    ].map((k) => [k, { view: true, edit: true }]),
  ),
};
export const host = {
  serverId: 'host',
  serverName: 'Paradigm Hub',
  networkId: 'network',
  current: true,
  online: true,
  version: '2.0',
  minecraftVersion: '1.21.1',
  loader: 'Fabric',
  lastSeenMs: Date.now(),
  onlinePlayers: 12,
  moduleCount: 20,
  enabledModuleCount: 18,
  activeProvider: 'mysql',
  storageHealth: 'available',
  runtimeDetailsAvailable: true,
};
export const remote = {
  ...host,
  serverId: 'stoneblock',
  serverName: 'FTB StoneBlock 4',
  current: false,
  onlinePlayers: null,
  moduleCount: null,
  enabledModuleCount: null,
  runtimeDetailsAvailable: false,
};
export function field(category = 'motd') {
  return {
    key: `${category}.message`,
    category,
    label: 'Welcome message',
    help: 'Text shown to players',
    type: 'STRING',
    value: { value: 'Hello', secret: false, set: true },
    defaultValue: { value: 'Welcome', secret: false, set: true },
    options: [],
    editable: true,
    masked: false,
    nullable: false,
    required: true,
    allowEmptyItems: false,
    reloadBehavior: 'IMMEDIATE',
    riskLevel: 'LOW',
    origin: 'server',
    serverValue: { value: 'Hello', secret: false, set: true },
    networkValue: { value: 'Default', secret: false, set: true },
  };
}
export function remoteSnapshot(serverId = 'stoneblock', category = 'motd') {
  return {
    serverId,
    networkId: 'network',
    online: true,
    schemaCompatible: true,
    fields: [field(category)],
    categories: [{ id: category, label: category }],
    sections: [
      {
        section: category,
        adopted: true,
        networkRevision: 2,
        serverRevision: 4,
        appliedGlobalRevision: 2,
        appliedServerRevision: 3,
        appliedAtMs: Date.now(),
        lastError: '',
      },
    ],
  };
}
export async function mockApi(page: Page) {
  const requests: { path: string; body: unknown }[] = [];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const body = request.method() === 'POST' ? request.postDataJSON() : undefined;
    requests.push({ path, body });
    const category = url.searchParams.get('categories') || 'motd';
    let data: unknown = {};
    if (path === '/api/auth/status' || path === '/api/auth/login')
      data = {
        authenticated: true,
        requireLogin: true,
        csrfToken: 'csrf',
        principal: { name: 'Administrator', uuid: 'admin', console: true },
        capabilities,
        serverId: 'host',
        serverName: 'Paradigm Hub',
        networkActive: true,
      };
    else if (path === '/api/servers')
      data = {
        servers: [
          host,
          remote,
          { ...remote, serverId: 'offline', serverName: 'Offline server', online: false },
        ],
        networkActive: true,
      };
    else if (path === '/api/overview')
      data = {
        ...host,
        serverId: 'host',
        uptimeMs: 3600000,
        onlinePlayers: 12,
        modules: { total: 20, enabled: 18 },
        warnings: [],
        recentActivity: [],
        runtime: { tps: 20, mspt: 8 },
      };
    else if (path === '/api/config/formatting') data = { tokens: ['{player}', '{online_players}'] };
    else if (path === '/api/config/snapshot')
      data = {
        revision: 'local-1',
        categories: [],
        fields: [
          'modules',
          'motd',
          'chat',
          'announcements',
          'teleports',
          'tablist',
          'restart',
          'commands',
          'cooldowns',
          'discord',
          'dashboard',
        ].map((c) => ({ ...field(c), origin: undefined })),
      };
    else if (path === '/api/remote-config/snapshot')
      data = remoteSnapshot(url.searchParams.get('serverId') || 'host', category);
    else if (path === '/api/remote-config/patch')
      data = {
        saved: true,
        revision: 5,
        result: { accepted: ['motd.message'], rejected: [], warnings: [] },
      };
    else if (path === '/api/config/patch')
      data = { accepted: ['motd.message'], rejected: [], warnings: [] };
    else if (path === '/api/players')
      data = {
        players: [
          {
            name: 'Alex',
            uuid: 'uuid-alex',
            online: true,
            lastSeenMs: Date.now(),
            playtimeMs: 3600000,
          },
        ],
        matchTotal: 1,
      };
    else if (path === '/api/menus')
      data = {
        menus: [{ id: 'main', title: 'Main menu', rows: 3, editable: true }],
        sessions: [],
        errors: [],
        actionTypes: ['message', 'open_menu', 'await_input'],
        conditionTypes: ['has_permission'],
        placeholderTokens: ['{player}'],
      };
    else if (path === '/api/menus/item')
      data = {
        definition: {
          id: 'main',
          title: 'Main menu',
          rows: 3,
          permission: '',
          refreshSeconds: 0,
          slots: [],
        },
      };
    else if (path === '/api/holograms')
      data = {
        supported: true,
        config: {
          enabled: true,
          defaultViewDistance: 48,
          defaultRefreshIntervalSeconds: 5,
          holograms: {},
        },
        temporary: [],
        runtimeStatus: [],
        capabilities: { textDisplays: true },
      };
    else if (path === '/api/custom-commands')
      data = {
        commands: [{ name: 'hello', description: 'Say hello' }],
        actionTypes: ['message', 'open_menu', 'conditional'],
        conditionTypes: ['has_permission'],
      };
    else if (path === '/api/custom-commands/item')
      data = {
        command: {
          name: 'hello',
          description: 'Say hello',
          command: {
            name: 'hello',
            description: 'Say hello',
            actions: [],
            arguments: [],
            extension: { retained: true },
          },
        },
      };
    else if (path === '/api/tickets')
      data = {
        entries: [
          {
            ticketKey: 'NET-1',
            subject: 'Help',
            creatorName: 'Alex',
            originServerId: 'host',
            status: 'OPEN',
            priority: 'NORMAL',
            revision: 3,
          },
        ],
        total: 1,
        statuses: ['OPEN', 'CLOSED'],
        priorities: ['NORMAL', 'HIGH'],
        categories: [],
        summary: { open: 1 },
      };
    else if (path === '/api/tickets/item')
      data = {
        ticket: { ticketKey: 'NET-1', subject: 'Help', revision: 3 },
        messages: [],
        events: [],
      };
    else if (path === '/api/storage/configuration')
      data = {
        provider: 'mysql',
        networkId: 'network',
        serverId: 'host',
        serverName: 'Paradigm Hub',
        fallbackToJsonOnSqlFailure: true,
        sqlitePath: 'data/db',
        sqlHost: 'localhost',
        sqlPort: 3306,
        sqlDatabase: 'paradigm',
        sqlUsername: 'paradigm',
        sqlPoolSize: 5,
        sqlSsl: false,
        sqlPasswordEnv: '',
      };
    else if (path === '/api/permissions/groups')
      data = {
        groups: [{ name: 'default', description: 'Default group', permissions: [], parents: [] }],
      };
    else if (path === '/api/permissions/users') data = { users: [], total: 0 };
    else if (path === '/api/permissions/nodes') data = { nodes: [], total: 0 };
    else if (path === '/api/permissions/tracks') data = { tracks: [] };
    else if (path === '/api/audit/recent') data = { entries: [], total: 0 };
    else if (path === '/api/moderation/capabilities')
      data = {
        actions: Object.fromEntries(
          [
            'warn',
            'mute',
            'tempmute',
            'unmute',
            'ban',
            'tempban',
            'unban',
            'ipban',
            'tempipban',
            'unipban',
            'jail',
            'unjail',
            'revoke',
          ].map((action) => [action, true]),
        ),
      };
    else if (path.startsWith('/api/moderation/')) data = { punishments: [] };
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ok: true, data, warnings: [] }),
    });
  });
  return requests;
}
