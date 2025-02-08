# Forge Announcements

[![Minecraft 1.19.2](https://img.shields.io/badge/Minecraft-1.19.2-brightgreen)](https://www.minecraft.net/)
[![Minecraft 1.18.2](https://img.shields.io/badge/Minecraft-1.18.2-brightgreen)](https://www.minecraft.net/)
[![Minecraft 1.12.2](https://img.shields.io/badge/Minecraft-1.12.2-brightgreen)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-Recommended-blueviolet)](https://files.minecraftforge.net/)
[![License](https://img.shields.io/badge/License-MIT-brightgreen)](LICENSE)

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/L3L4Z8L38)

**Forge Announcements** is a powerful and versatile Minecraft Forge mod designed to streamline communication on your server.  Specifically created for server administrators, it automates the broadcasting of messages, announcements, and server information to players.  Configure and schedule messages with ease, ensuring crucial updates, server rules, event details, and more reach your entire player base reliably and without manual effort.  Enhance player engagement and keep your community informed with Forge Announcements.

## Version Compatibility

| Minecraft Version | Forge Version  | LuckPerms Compatibility | ForgeEssentials Compatibility | Status   |
| :---------------- | :------------- | :---------------------- | :-------------------------- | :------- |
| **1.19.2**         | 41.1.0        | ✅                        | ❌                            | **ACTIVE** |
| **1.18.2**         | 40.2.21        | ✅                        | ✅                            | **ACTIVE** |
| **1.12.2**         | 14.23.5        | ✅                        | ✅                            | **ACTIVE** |

**Legend:** ✅ - Compatible, ❌ - Incompatible, ACTIVE - Actively Maintained

## ✨ Key Features

*   **Automated Broadcasts:** Schedule and automate server-wide announcements at specified intervals. Keep players informed about events, updates, rules, and more without manual intervention.
*   **Highly Customizable Messages:** Design engaging broadcasts with full control over:
    *   Prefixes and Suffixes
    *   Headers and Footers (with toggle option)
    *   Message Content
    *   Hex Colors and Formatting
    *   Clickable Links (for websites, forums, etc.)
    *   Sounds (to grab player attention)
*   **Customizable MOTD (Message of the Day):** Greet players with a dynamic and informative MOTD. Utilize tags for placeholders and create a welcoming server experience.
*   **Powerful Chat Features:**
    *   **Staff Chat:**  Dedicated chat channel for staff communication, keeping public chat clean.
    *   **Group Chat:** Enable players to create and manage their own private chat groups.
    *   **Mentions:**  Notify specific players (`@PlayerName`) or all online players (`@everyone`) within chat.
    *   **Custom Commands:** Define and add your own custom commands to enhance server functionality.
*   **Scheduled Server Restarts:** Plan server restarts with countdown messages and warnings, ensuring smooth server management and player awareness.
*   **Multi-Language Support:**  Available in English, Russian, and Czech languages. (Contribute translations to expand language support!)

## 🛠️ Installation

1.  **Download:** Get the latest version of "Forge Announcements" from the [Releases page](https://github.com/Avalanche7CZ/ForgeAnnouncements/releases). Ensure you download the `.jar` file compatible with your Minecraft server version.
2.  **Place in `mods` folder:** Locate your Minecraft server directory and place the downloaded `.jar` file into the `mods` folder.
3.  **Start Server:** Run your Minecraft server. Forge Announcements will automatically generate the default configuration files in the `configs/forgeannouncements/` directory on the first server startup.
4.  **(Optional) Server Restart:**  For configuration changes to fully take effect after editing the config files, a server restart is generally recommended.

**Important Note:** Make sure your server is running the correct version of Minecraft Forge compatible with Forge Announcements.

## ⚙️ Configuration

Forge Announcements is highly configurable to suit your server's needs. Configuration files are located in the `configs/forgeannouncements/` directory within your server folder.

*   `announcements.toml`:  Customize scheduled broadcast messages, prefixes, headers, footers, and sounds.
*   `mentions.toml`: Configure mention system settings (e.g., `@everyone` permissions).
*   `motd.toml`:  Edit the Message of the Day lines, utilizing formatting tags and placeholders.
*   `restarts.toml`:  Schedule and configure server restart warnings and countdowns.
*   `main.toml`:  Enable or disable individual features of Forge Announcements (MOTD, Announcements, Staff Chat, etc.).

**Editing Configuration:**  Use a text editor to modify the `.toml` files.  It's recommended to stop your server before making changes to ensure configurations are loaded correctly on the next startup.  Refer to comments within each configuration file for detailed explanations of each setting.

## 🙋 Support & Community

Need help or have questions? Join our Discord community!

[![Discord-Join-Us](https://img.shields.io/badge/Join%20our%20Discord-Connect%20Now!-blueviolet?logo=discord&logoColor=white)](https://discord.gg/bbqPQTzK7b)

Our Discord server is the best place to:
*   Get assistance with installation and configuration.
*   Ask questions about mod features and usage.
*   Report bugs or suggest new features.
*   Connect with other Forge Announcements users.

## 🧪 Dev Builds (Experimental)

For adventurous server owners and testers, experimental "dev builds" are available on our [Discord](https://discord.gg/bbqPQTzK7b).

**Please Note:** Dev builds are for testing and may contain bugs or be unstable. Use them at your own risk and always back up your server!  Feedback on dev builds is highly appreciated in our Discord.

---
