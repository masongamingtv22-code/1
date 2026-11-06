# MCPVP Tiers (Fabric 1.21.11)

Shows each player's selected tier on its own line above their name tag and beside their name in the tab list. Install Mod Menu to edit the settings from its config button.

## Settings

- **Button 1 — Tier to show:** Highest (default), Overall, or a supported kit/mode.
- **Button 2 — Source:** MCPVP (default), MCTIERS, PVPTIERS, or SUBTIERS.
- The selected source and tier are saved in `config/mcpvp-tiers.properties`.

## Tier sources

- MCPVP: `https://www.mcpvp.com/@PlayerName`
- MCTIERS: public profile API at `https://mctiers.com/api/v2/profile/by-name/PlayerName`
- PVPTIERS: public profile search API at `https://pvptiers.com/api/search_profile/playername`
- SUBTIERS: public profile API at `https://subtiers.net/api/v2/profile/UUID` (the mod resolves the Minecraft username to a UUID first)

Lookups run in the background and are cached for ten minutes; failed lookups retry after one minute. Switching source or tier clears the cache. The mod makes public HTTPS GET requests only; it does not send Minecraft account credentials.

## Build

Build with Java 21 and the included Gradle wrapper using `gradlew build`. The jar is written to `build/libs/`.

Tier colors are defined once in `TierColors.java`.
