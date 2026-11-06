# MCPVP Tiers (Fabric 1.21.11)

Shows each player's mcpvp.com tier on its own line **above their name tag** and beside their name in the tab list. Install Mod Menu to edit the tier choice from its config button. The screen offers Highest (default), Overall, and kit names. The same choice can be edited in `config/mcpvp-tiers.properties` as `tier=highest`, `tier=overall`, or a kit name such as `Sword`, `SMP`, or `UHC`. Lookups run in the background and are cached for ten minutes (failed lookups retry after one minute).

Colors (same everywhere): ST6–HT6 brown; LT5–HT5 blue; LT4–HT4 red; LT3–HT3 bronze; LT2–HT2 silver; LT1–HT1 yellow. They are defined once in `TierColors.java`.

Build with Java 21 and the included Gradle wrapper using `gradlew build`. The jar is written to `build/libs/`. Profile source: `https://www.mcpvp.com/@PlayerName`.
