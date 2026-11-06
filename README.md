# MCPVP Tiers (Fabric 1.21.11)

Shows each player's selected tier on its own line above their name tag and beside their name in the tab list. Install Mod Menu to edit the settings from its config button.

## Settings

- **Button 1 — Tier to show:** Highest (default), Overall, or a supported kit/mode.
- **Button 2 — Source:** MCPVP (default), MCTIERS, PVPTIERS, or SUBTIERS.
- The selected source and tier are saved in `config/mcpvp-tiers.properties`.

## Tier sources

- **MCPVP:** Public player page at `https://www.mcpvp.com/@PlayerName`.
- **MCTIERS:** Public profile API at `https://mctiers.com/api/v2/profile/{uuid}` (UUID with dashes).
- **PVPTIERS:** Public profile API at `https://pvptiers.com/api/profile/{uuid}` (UUID without dashes).
- **SUBTIERS:** Public profile API at `https://subtiers.net/api/v2/profile/{uuid}` (UUID with dashes).

The mod resolves a Minecraft username to its UUID through Mojang before querying the UUID-based providers. Lookups run in the background and are cached for ten minutes; failed lookups retry after one minute. Switching source or tier clears the cache. The mod makes public HTTPS GET requests only; it does not send Minecraft account credentials.

## Build

Build with Java 21 and the included Gradle wrapper using `gradlew build`. The jar is written to `build/libs/`.

Tier colors are defined once in `TierColors.java`.
