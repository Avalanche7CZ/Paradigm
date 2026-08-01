# Paradigm Essentials [![Downloads](https://www.modpackindex.com/badge/mod/60637/paradigm/downloads.svg)](https://www.modpackindex.com/mod/60637/paradigm) [![Modpacks](https://www.modpackindex.com/badge/mod/60637/paradigm/modpacks.svg)](https://www.modpackindex.com/mod/60637/paradigm)

**Paradigm Essentials** is a modular **server administration suite** for Minecraft.

Manage your server, players, permissions, moderation, communication, and automation from one cozy little mod with a built-in **local web dashboard** for when editing JSON files stops being fun :P

Built for **Forge, Fabric, and NeoForge** servers.

**Version:** `2.2.4`  
**Author:** Avalanche7CZ  
**License:** CC-BY-NC-ND-4.0

---

# One Mod, Lots of Server features

Paradigm combines the everyday tools a server needs into one modular system.

Enable what you need, disable what you dont, and do everything without downloading lots  of separate mods.

### Local Server Dashboard

Paradigm includes a built-in **local web dashboard** for server administration and configuration.

- Configuration editors for each module
- Permission and group management
- Command settings and cooldowns
- MOTD editor with formatting tools and previews
- Custom command editor
- Storage status and migration tools

**No external panel required. It runs with your server all localy.**

![dasboard_local_beta](https://cdn.modrinth.com/data/cached_images/0c3711b5d2973fb17ddf9948865c7fd6e53aa844.png)

---

### Server Administration

A collection of familiar server management and essential :3 commands.

- Homes and `/back`
- Spawn management
- Warps
- /tpa
- Fly and movement speed
- Heal and feed
- Gamemode shortcuts
- Time and weather controls

And plenty of admin utilities:

`/vanish`, `/god`, `/invsee`, `/repair`, `/enchant`, `/sudo`, `/near`, `/whois`, `/top`, `/jump`, `/jump`

Most built-in commands can be individually enabled or disabled.

---

### Built-In Permissions & Groups

Paradigm includes its own **permission and group system**. 

- Create and manage permission groups
- Group inheritance
- Prefixes, suffixes, weights, and descriptions
- Direct player permissions
- Explicit allow and deny nodes
- Temporary permissions and group assignments

Already using LuckPerms? Paradigm also includes **LuckPerms migration tools** for importing or exporting permission data.

---

### Moderation Tools

Standard Commands for Moderating Players

- Kicks /kick
- Bans and temporary bans (/ban, /tempban)
- IP bans (/ipban)
- Mutes and temporary mutes (/mute)
- Warnings (/warn)
- Jail system (/jail)
- Punishment history 
- Active punishment tracking
- Server and network punishment scopes (for Network bans , using MySQL)

The dashboard provides a player history, like date of the ban, reason etc. 

---

### Communication Tools

Keep players and staff connected without needing another chat mods.

- Private messages with `/msg`
- Quick replies with `/reply` or `/r`
- Player mentions using `@PlayerName`
- Staff chat `/sc`
- Private group chats `/groupchat`
- **CUSTOM **Join and leave messages

![Private Messages](https://cdn.modrinth.com/data/cached_images/01608a40a5aec74de9368d89f7088b131e8fd9cc.png)

---

### Server Announcements

Create scheduled and richly (RGB Support) formatted server announcements.

- Chat broadcasts
- Actionbar messages
- Titles and subtitles
- Bossbars
- Independent schedules
- Random message rotation
- Interactive text components (Hover, click, command)

![Announcements](https://cdn.modrinth.com/data/cached_images/f8eb3598a0dc25f0f9c28bb5b2b3ed0f22390e76.png)

---

### Rich Message Formatting

Paradigm includes its own message formatting system (inspired by mini message format) for supported messages and editors.

- Minecraft colors
- Hex colors (`&#RRGGBB`)
- Gradients and styled text
- Bold, italic, underline, and strikethrough
- Clickable links
- Hover text
- Click-to-run commands
- Dynamic placeholders

![In-Game MOTD](https://cdn.modrinth.com/data/cached_images/d3310a386583c554820e482b251b4ab6978b0eb5.png)

---

### MOTD & Server List Customization

Make the server a little prettier before players even join.

- Dynamic server-list MOTDs
- Multiple MOTD profiles
- Random MOTD rotation
- Custom server icons
- Player-count hover customization
- Personalized in-game welcome MOTD
- Dashboard MOTD editor with live-style previews and few templates

![Server List](https://cdn.modrinth.com/data/cached_images/b2a38e9b9fb07f3e41d56be4b947f235f92cb60b_0.webp)

---

### Restart Automation (THE SERVER NEEDS ITS OWN SCRIPT, more on WIKI)

Keep server restarts predictable and friendly.

- Scheduled automatic restarts
- Configurable warning times
- Countdown messages
- Pre-restart commands
- Manual restart scheduling and cancellation

![Restart Scheduler](https://cdn.modrinth.com/data/cached_images/95665b5516330e3b0b78da22bbcbf8f30022bfb6.png)

---

### Custom Commands

Create your own commands without writing a mod.

Custom commands can be configured with structured actions and managed through Paradigm's configuration or dashboard editor.

Perfect for server information, links, shortcuts, rules, and other small server-specific commands.

---

### Flexible Data Storage

Paradigm can store server data using multiple storage providers.

- JSON
- SQLite
- MySQL / MariaDB

Storage status, connection testing, fallback state, and migration dry runs are available directly from the dashboard.

Existing data can be migrated between supported storage providers with conflict handling and migration previews.

---

# Modular by Design

Don't need part of Paradigm? Turn it off.

Modules and commands can be configured individually, and built-in command roots can be enabled or disabled through the dashboard or in json config.

---

## Installation

1. Install the supported **Forge, Fabric, or NeoForge** version for your Minecraft server.
2. Download the matching Paradigm `.jar`.
3. Place it in your server's `mods` folder.
4. Start the server.

Paradigm will generate its configuration and data files automatically.

For Fabric versions, **dont forget Fabric API**. (If you are using downloaded fabric modpack, the API will be there already)

---

## Commands

Paradigm contains quite a few commands now. Like... a lot. qwq

Use:

```

/paradigm help

```

for built-in help and module information.

The dashboard also includes searchable command settings for enabling or disabling built-in command roots.

---

## License

Paradigm is licensed under **CC-BY-NC-ND-4.0**.

See the [LICENSE](LICENSE) file for the full license terms.

Want to include Paradigm somewhere, create an integration, translate it, modify it, or use it in a way that may not be covered by the license? Feel free to ask :3

I am open to most reasonable requests. Any additional permission must be agreed to in writing first.
---

## Support & Community

Need help, found a bug, or have a cute little feature idea?

Come say hi ♡

[![Discord](https://img.shields.io/badge/Join%20our%20Discord-5865F2?logo=discord&logoColor=white)](https://discord.gg/bbqPQTzK7b)

---

## Support Development

Enjoying Paradigm and want to support its development?

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/L3L4Z8L38)

Every bit of support helps me spend more time making Paradigm better and adding more silly server things. ♡

---

# Credits

**Paradigm** is developed and maintained with lots of caffeine and questionable sleep decisions by **Avalanche7CZ**.
```
