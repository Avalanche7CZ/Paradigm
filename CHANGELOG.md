# Changelog

## 2.5.2

### Fixes

- Fixed a regression introduced in 2.5.1 that could leave ghost player entries in TAB after disconnects when player-info updates bypassed Vanish's packet bookkeeping.
- Restored correct player-info cleanup when Vanish is disabled or no players are vanished.
- Preserved hidden-player privacy and authorized staff visibility while allowing legitimate cleanup packets.
- Applied the fix across all supported modern Fabric, Forge, and NeoForge targets.

