export interface Page {
  id: string;
  label: string;
  group: 'Workspace' | 'Management' | 'System';
  categories?: string[];
  hostOnly?: boolean;
  description: string;
}
export const pages: Page[] = [
  {
    id: 'overview',
    label: 'Overview',
    group: 'Workspace',
    description: 'Your network, at a glance.',
  },
  {
    id: 'servers',
    label: 'Servers',
    group: 'Workspace',
    description: 'Known servers and their latest heartbeat.',
  },
  {
    id: 'players',
    label: 'Players',
    group: 'Workspace',
    description: 'Player profiles and available online information.',
  },
  {
    id: 'general',
    label: 'Modules & configuration',
    group: 'Management',
    categories: [
      'modules',
      'command_groups',
      'admin_utilities',
      'afk',
      'moderation',
      'custom_commands',
    ],
    description: 'Manage modules and server behavior.',
  },
  {
    id: 'motd',
    label: 'MOTD & welcome',
    group: 'Management',
    categories: ['motd'],
    description: 'Craft the first impression of your server.',
  },
  {
    id: 'chat',
    label: 'Chat',
    group: 'Management',
    categories: ['chat'],
    description: 'Formatting, mentions and player interactions.',
  },
  {
    id: 'announcements',
    label: 'Announcements',
    group: 'Management',
    categories: ['announcements'],
    description: 'Messages, channels and scheduled delivery.',
  },
  {
    id: 'teleports',
    label: 'Teleports',
    group: 'Management',
    categories: ['teleports'],
    description: 'Homes, warps, spawn and teleport requests.',
  },
  {
    id: 'tablist',
    label: 'Tablist',
    group: 'Management',
    categories: ['tablist'],
    description: 'Headers, player names and world overrides.',
  },
  {
    id: 'restart',
    label: 'Restart schedule',
    group: 'Management',
    categories: ['restart'],
    description: 'Schedules, warnings and restart actions.',
  },
  {
    id: 'menus',
    label: 'Menus',
    group: 'Management',
    hostOnly: true,
    description: 'Inventory layouts, items and click actions.',
  },
  {
    id: 'holograms',
    label: 'Holograms',
    group: 'Management',
    hostOnly: true,
    description: 'Text displays, placement and interaction.',
  },
  {
    id: 'customCommands',
    label: 'Custom commands',
    group: 'Management',
    hostOnly: true,
    description: 'Commands, arguments, actions and conditions.',
  },
  {
    id: 'commands',
    label: 'Command settings',
    group: 'Management',
    categories: ['commands'],
    description: 'Enable commands and control root ownership.',
  },
  {
    id: 'cooldowns',
    label: 'Cooldowns & warmups',
    group: 'Management',
    categories: ['cooldowns'],
    description: 'Timing for built-in commands.',
  },
  {
    id: 'permissions',
    label: 'Permissions',
    group: 'Management',
    description: 'Groups, users, contexts and ordered tracks.',
  },
  {
    id: 'moderation',
    label: 'Moderation',
    group: 'Management',
    hostOnly: true,
    description: 'Player history, punishments and revocations.',
  },
  {
    id: 'tickets',
    label: 'Support tickets',
    group: 'Management',
    description: 'Network support requests and conversations.',
  },
  {
    id: 'storage',
    label: 'Storage',
    group: 'System',
    hostOnly: true,
    description: 'Provider health, diagnostics and migration previews.',
  },
  {
    id: 'storageConfig',
    label: 'Storage configuration',
    group: 'System',
    hostOnly: true,
    description: 'Connection settings and server identity.',
  },
  {
    id: 'audit',
    label: 'Audit log',
    group: 'System',
    description: 'Administrative activity and operation outcomes.',
  },
  {
    id: 'discord',
    label: 'Discord integration',
    group: 'System',
    categories: ['discord'],
    description: 'Relay configuration and connection controls.',
  },
  {
    id: 'dashboard',
    label: 'Dashboard settings',
    group: 'System',
    categories: ['dashboard'],
    hostOnly: true,
    description: 'Listener, sessions and request limits.',
  },
];
export const route = (hash: string) =>
  pages.some((p) => p.id === hash.slice(1)) ? hash.slice(1) : 'overview';
