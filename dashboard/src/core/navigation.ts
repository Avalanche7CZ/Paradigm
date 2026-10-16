export const navigationGroups = [
  'Network',
  'Players & access',
  'Server settings',
  'Content & commands',
  'System',
] as const;
export interface Page {
  id: string;
  label: string;
  group: (typeof navigationGroups)[number];
  categories?: string[];
  hostOnly?: boolean;
  description: string;
}
export const pages: Page[] = [
  {
    id: 'overview',
    label: 'Overview',
    group: 'Network',
    description: 'Your network, at a glance.',
  },
  {
    id: 'servers',
    label: 'Servers',
    group: 'Network',
    description: 'Known servers and their latest heartbeat.',
  },
  {
    id: 'players',
    label: 'Players',
    group: 'Players & access',
    description: 'Player profiles and available online information.',
  },
  {
    id: 'general',
    label: 'Modules & configuration',
    group: 'Server settings',
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
    group: 'Server settings',
    categories: ['motd'],
    description: 'Craft the first impression of your server.',
  },
  {
    id: 'chat',
    label: 'Chat',
    group: 'Server settings',
    categories: ['chat'],
    description: 'Formatting, mentions and player interactions.',
  },
  {
    id: 'announcements',
    label: 'Announcements',
    group: 'Server settings',
    categories: ['announcements'],
    description: 'Messages, channels and scheduled delivery.',
  },
  {
    id: 'teleports',
    label: 'Teleports',
    group: 'Server settings',
    categories: ['teleports'],
    description: 'Homes, warps, spawn and teleport requests.',
  },
  {
    id: 'tablist',
    label: 'Tablist',
    group: 'Server settings',
    categories: ['tablist'],
    description: 'Headers, player names and world overrides.',
  },
  {
    id: 'restart',
    label: 'Restart schedule',
    group: 'Server settings',
    categories: ['restart'],
    description: 'Schedules, warnings and restart actions.',
  },
  {
    id: 'menus',
    label: 'Menus',
    group: 'Content & commands',
    hostOnly: true,
    description: 'Inventory layouts, items and click actions.',
  },
  {
    id: 'holograms',
    label: 'Holograms',
    group: 'Content & commands',
    hostOnly: true,
    description: 'Text displays, placement and interaction.',
  },
  {
    id: 'customCommands',
    label: 'Custom commands',
    group: 'Content & commands',
    hostOnly: true,
    description: 'Commands, arguments, actions and conditions.',
  },
  {
    id: 'commands',
    label: 'Command settings',
    group: 'Content & commands',
    categories: ['commands'],
    description: 'Enable commands and control root ownership.',
  },
  {
    id: 'cooldowns',
    label: 'Cooldowns & warmups',
    group: 'Content & commands',
    categories: ['cooldowns'],
    description: 'Timing for built-in commands.',
  },
  {
    id: 'permissions',
    label: 'Permissions',
    group: 'Players & access',
    description: 'Groups, users, contexts and ordered tracks.',
  },
  {
    id: 'moderation',
    label: 'Moderation',
    group: 'Players & access',
    hostOnly: true,
    description: 'Player history, punishments and revocations.',
  },
  {
    id: 'tickets',
    label: 'Support tickets',
    group: 'Players & access',
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
