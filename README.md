# Paradigm
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/L3L4Z8L38)

**Paradigm** is a modular server administration and communication tool for Minecraft, designed to give you powerful control over server messaging, player interactions, and automation. Whether you need scheduled announcements, dynamic MOTD, staff coordination channels, private messages, or custom commands, Paradigm provides a complete suite of features to enhance your server's functionality and keep your community engaged.

**Current Version:** `Whatever the latest version is` | **Author:** Avalanche7CZ | **License:** CC-BY-NC-ND-4.0

---
## Core Features

### Advanced Announcement System
* **Multiple Message Channels:** Broadcast messages globally, via action bar, title, or boss bar with independent scheduling
* **Random Message Support:** Configure random announcement rotations to keep messages fresh
* **Flexible Scheduling:** Set custom intervals for each message type (milliseconds to hours)
* **Dynamic Formatting:** Full support for Minecraft text components with colors, styles, and interactive elements
* **Server Status Broadcasts:** Announce server events and status changes automatically

### Communication & Chat Features
* **Private Messages:** Direct player-to-player messaging with `/msg` plus aliases `/tell`, `/w`, `/whisper`, and quick reply via `/reply` or `/r`
* **Mentions System:** Players can ping each other with `@PlayerName` syntax with configurable cooldowns and permissions
* **Group Chat:** Create and manage private chat groups for teams, friends, or special discussions
* **Staff Chat:** Dedicated private channel for server administrators with optional boss bar indicator when enabled
* **Join/Leave Messages:** Customize welcome and departure messages with player-specific information
* **Message Formatting Parser:** Advanced text parsing supporting clickable links, hover events, and command execution

###  Customization & Formatting
* **Rich Text Components:** Full support for colors, bold, italic, underline, and strikethrough formatting
* **Hex Color Codes:** Use `&#RRGGBB` format for unlimited color customization beyond standard Minecraft palette
* **Interactive Elements:** Add clickable links, runnable commands, and hover tooltips to any message
* **Placeholder System:** Support for dynamic placeholders in announcements and messages
* **Multilingual Support:** Built-in translations for Czech, English, and Russian with extensible language system
* **Server List MOTD:** Display custom formatted messages in the Minecraft server list browser with random rotation and custom icon support
* **Server Icons:** Use custom 64x64 PNG icons with random rotation or specific selection per MOTD
* **In-Game MOTD:** Show personalized welcome messages to players when they join

### Custom Commands & Server Management
* **Custom Command Creation:** Define custom server commands with multiple actions and flexible arguments
* **Restart Scheduler:** Automated server restart scheduling with pre-restart commands and warning broadcasts
* **Configurable Warning System:** Multiple warning messages at customizable intervals before restart
* **Countdown Timers:** Dynamic countdown displays to alert players of upcoming restarts
* **Permission-Based Access:** Integrate with LuckPerms for granular permission control

### 🛠️ Web Configuration Editor
* **Live Configuration Editing:** Web-based interface for editing configuration files without server restart
* **Session Management:** Secure WebSocket connections for real-time configuration updates
* **Visual Configuration:** User-friendly interface for managing complex settings

### Development Features
* **Modular Architecture:** Clean plugin system with `ParadigmModule` interface for extensibility
* **Debug Logging:** Built-in debug logger for development and troubleshooting
* **Event System:** Comprehensive event listener system for chat events and server lifecycle
* **Telemetry Reporter:** Optional telemetry for usage statistics
* **JSON Validation:** Automatic JSON configuration validation and error reporting

---
## Compatibility

| Version        | Mod Loader         | Required Version | LuckPerms | Status             |
|:---------------|:-------------------|:-----------------|:---------:|:-------------------|
| **1.21.11**    | Forge              | `61.1.3+`        | ✅        | Active             |
| **1.21.1**     | Forge              | `52.0.26+`       | ✅        | Active             |
| **1.20.1**     | Forge              | `47.1.0+`        | ✅        | Active             |
| **1.19.2**     | Forge              | `43.3.0+`        | ✅        | Active             |
| **1.18.2**     | Forge              | `40.2.0+`        | ✅        | Active             |
| **1.12.2**     | Forge              | `14.23.5+`       | ✅        | **BUG FIXES ONLY** |
| **1.21.11**    | Fabric             | `0.18.4+`        | ✅        | Active             |
| **1.21.8**     | Fabric             | `0.16.14+`       | ✅        | Active             |
| **1.21.1**     | Fabric             | `0.16.14+`       | ✅        | Active             |
| **1.20.1**     | Fabric             | `0.15.11+`       | ✅        | Active             |
| **1.21.1**     | NeoForge           | `21.1.21+`       | ✅        | Active             |


---
## Installation

### For Forge
1. Download and install the **recommended version of Minecraft Forge** for your game version (see compatibility table above)
2. Download the latest **Forge** version of Paradigm from the [**Releases Page**](https://modrinth.com/mod/paradigm)
3. Place the Paradigm `.jar` file into your server's `mods` folder
4. (Optional) Install **[LuckPerms](https://luckperms.net/)** for permission-based features

### For Fabric
1. Download and install the **Fabric Loader** for your game version
2. Download the **[Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api)** and place it in your `mods` folder (**required**)
3. Download the latest **Fabric** version of Paradigm from the [**Releases Page**](https://modrinth.com/mod/paradigm)
4. Place the Paradigm `.jar` file into your server's `mods` folder
5. (Optional) Install **[LuckPerms](https://luckperms.net/)** for permission-based features

### For NeoForge
1. Download and install **NeoForge** for your game version
2. Download the latest **NeoForge** version of Paradigm from the [**Releases Page**](https://modrinth.com/mod/paradigm)
3. Place the Paradigm `.jar` file into your server's `mods` folder
4. (Optional) Install **[LuckPerms](https://luckperms.net/)** for permission-based features

### For NeoForge via Sinytra Connector (Experimental)
If you prefer to use the Fabric version with NeoForge, you can use Sinytra Connector:

1. Download and install **NeoForge** for your game version
2. Download **[Sinytra Connector](https://www.curseforge.com/minecraft/mc-mods/sinytra-connector)** and place it in your `mods` folder
3. Download **[Forgified Fabric API](https://www.curseforge.com/minecraft/mc-mods/forgified-fabric-api)** and place it in your `mods` folder
4. Download the latest **Fabric** version of Paradigm from the [**Releases Page**](https://modrinth.com/mod/paradigm)
5. Place the Paradigm **Fabric** `.jar` file into your `mods` folder
6. (Optional) Install **[LuckPerms](https://luckperms.net/)** for permission-based features

**Note:** The dedicated NeoForge version is recommended. The Sinytra Connector method is experimental and may have compatibility issues.

---
## Configuration

### File Structure
After running the server once with Paradigm installed, configuration files will be generated in your server's `config/Paradigm/` directory:

```
config/Paradigm/
├── config.json               # Main settings and module toggles
├── announcements.json        # Automated announcement scheduling
├── motd.json                 # Message of the Day configuration
├── mention.json              # Mention system settings
├── restart.json              # Server restart scheduler
├── chat.json                 # Group chat, staff chat, and private message settings
└── cooldown.json             # Cooldown configurations
```

### Using the Web Editor
Paradigm includes a web-based configuration editor for convenient live editing without server restarts. Access the web interface and make real-time changes to your configuration files.

### Multiplayer Server List (Custom MOTD/Icon)
Paradigm supports custom MOTD and server icon directly in the multiplayer server list (the server browser screen).

- Configure this in `motd.json`
- You can use formatted text and random MOTD rotation
- You can set a custom 64x64 server icon and rotate icons together with MOTD profiles
- This affects how your server appears before players join (in the multiplayer list)

---
## Commands Reference

### Main Commands
- `/paradigm` - Display main Paradigm help
- `/paradigm help` - Show help for Paradigm modules
- `/paradigm help <module>` - Show help for a specific module
- `/paradigm reload <config>` - Reload specific config (main, announcements, chat, motd, mention, restart, customcommands, all)
- `/paradigm editor` - Access web configuration editor
- `/paradigm apply <code>` - Apply web editor changes from code

### Broadcast Commands
- `/paradigm broadcast <message>` - Send chat broadcast
- `/paradigm actionbar <message>` - Send actionbar message
- `/paradigm title <title || subtitle>` - Send title/subtitle message
- `/paradigm bossbar <interval> <color> <message>` - Send bossbar message

### Private Messages
- `/msg <player> <message>` - Send a private message
- `/tell <player> <message>` - Alias for `/msg`
- `/w <player> <message>` - Alias for `/msg`
- `/whisper <player> <message>` - Alias for `/msg`
- `/reply <message>` - Reply to your last PM conversation
- `/r <message>` - Alias for `/reply`

### Restart Management
- `/restart now` - Schedule an immediate server restart
- `/restart cancel` - Cancel a scheduled restart

### Staff Chat
- `/sc` - Toggle staff chat mode
- `/sc toggle` - Toggle staff chat mode

### Group Chat
- `/groupchat create <name>` - Create a new group chat
- `/groupchat join <name>` - Join an existing group chat
- `/groupchat leave` - Leave current group chat

### Mentions
- `/mention <player>` - Mention a specific player

### Web Editor
- `/paradigm editor trust` - Trust a player for web editor access
- `/paradigm editor untrust` - Remove web editor trust
- `/paradigm editor trusted` - List trusted players

---
## Permissions

Paradigm uses LuckPerms for permission management. Common permission nodes:

```
paradigm.mention.everyone     # Use @everyone in Mentions
paradigm.mention.player       # Mention individual players
paradigm.msg                  # Send private messages (/msg, /tell, /w, /whisper)
paradigm.reply                # Reply to private messages (/reply, /r)
paradigm.staff                # Access staff chat (/sc)
paradigm.restart.manage       # Manage server restarts
paradigm.broadcast            # Access /paradigm broadcast, actionbar, title, bossbar
paradigm.reload               # Reload configurations
paradigm.editor               # Access /paradigm editor and /paradigm apply
paradigm.groupchat            # Use group chat commands
```

---
## License

This project is licensed under the **CC-BY-NC-ND-4.0** license. See the [LICENSE](LICENSE) file for details.

- ✅ You can use this project
- ❌ You cannot modify it
- ❌ You cannot distribute it commercially
- ✅ You must give credit to the author

---
## Support & Community

Have questions, need help, or want to try the latest development builds? Join our community on Discord!

[![Discord](https://img.shields.io/badge/Join%20our%20Discord-5865F2?logo=discord&logoColor=white)](https://discord.gg/bbqPQTzK7b)

---
## Credits

**Paradigm** is developed and maintained by **Avalanche7CZ**. Special thanks to all contributors and community members who have provided feedback and support.






