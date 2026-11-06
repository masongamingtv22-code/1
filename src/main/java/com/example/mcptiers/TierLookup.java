package com.example.mcptiers;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks up a player's tier on mcpvp.com (https://www.mcpvp.com/@Name) in the background,
 * caches it, and hands the cached value to the renderers. Rendering code never blocks.
 */
public final class TierLookup {
    private static final Logger LOG = LoggerFactory.getLogger("mcpvp_tiers");
    private static final String[] OPTIONS = {"highest", "overall", "Sword", "SMP", "UHC", "Shield", "Pot", "Mace", "Spear", "Early Game", "Late Game", "End Game", "Diamond SMP", "Netherite Pot", "Creeper", "Cart", "Bow", "Crystal"};
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final String TIER_REGEX = "([SLMH]T[1-6])";
    private static final Pattern TIER = Pattern.compile("(?i)\\b" + TIER_REGEX + "\\b");
    private static final Pattern OVERALL_WITH_POINTS = Pattern.compile("(?i)\\b" + TIER_REGEX + "\\s+[0-9,.]+\\s*/\\s*[0-9,.]+\\s*Points");

    private static final long OK_TTL = Duration.ofMinutes(10).toMillis();
    /** Failed lookups (timeouts, 429, 5xx) are retried much sooner than successful ones. */
    private static final long ERROR_TTL = Duration.ofSeconds(60).toMillis();
    private static final long REQUEST_GAP_MILLIS = 150;

    private static final Map<String, CachedTier> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, task -> {
        Thread t = new Thread(task, "mcpvp-tier-lookup");
        t.setDaemon(true);
        return t;
    });

    private static volatile String configuredTier = "highest";
    private static volatile Path configPath;

    private TierLookup() {}

    // ---------------------------------------------------------------- config

    public static void start() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("mcpvp-tiers.properties");
        configPath = path;
        Properties properties = new Properties();
        try {
            if (Files.exists(path)) {
                try (var in = Files.newInputStream(path)) {
                    properties.load(in);
                }
            } else {
                Files.createDirectories(path.getParent());
                properties.setProperty("tier", "highest");
                try (var out = Files.newOutputStream(path)) {
                    properties.store(out, "MCPVP Tiers settings");
                }
            }
        } catch (Exception ignored) {
            // Unreadable config: fall back to defaults below.
        }
        String saved = canonicalOption(properties.getProperty("tier", "highest").trim());
        configuredTier = saved != null ? saved : "highest";
        LOG.info("[mcpvp_tiers] loaded v3-debug, tier setting = {}", configuredTier);
    }

    public static String getConfiguredTier() {
        return configuredTier;
    }

    public static synchronized void setConfiguredTier(String tier) {
        String option = canonicalOption(tier);
        if (option == null) return;
        configuredTier = option;
        CACHE.clear();
        Path path = configPath;
        if (path == null) return;
        Properties properties = new Properties();
        properties.setProperty("tier", option);
        try {
            Files.createDirectories(path.getParent());
            try (var out = Files.newOutputStream(path)) {
                properties.store(out, "MCPVP Tiers settings");
            }
        } catch (Exception ignored) {
            // Setting still applies for this session even if it cannot be saved.
        }
    }

    public static String[] getOptions() {
        return OPTIONS.clone();
    }

    private static String canonicalOption(String value) {
        if (value == null) return null;
        for (String option : OPTIONS) if (option.equalsIgnoreCase(value.trim())) return option;
        return null;
    }


    private static final AtomicInteger DEBUG_BUDGET = new AtomicInteger(25);

    /** Logs the first few display events so we can confirm tiers are actually being drawn. */
    public static void debug(String message) {
        if (DEBUG_BUDGET.getAndDecrement() > 0) LOG.info("[mcpvp_tiers] {}", message);
    }

    /**
     * Works out which player a tab list row is for. Servers with a custom tab list (like mcpvp.com)
     * use placeholder profiles, so the real name may only exist in the displayed text.
     */
    public static String resolveName(String profileName, Text display) {
        String shown = display == null ? "" : display.getString().trim();
        if (isPlayerLike(shown)) return shown;
        if (profileName == null || !USERNAME.matcher(profileName).matches()) {
            String[] parts = shown.split("\\s+");
            for (int i = parts.length - 1; i >= 0; i--) if (isPlayerLike(parts[i])) return parts[i];
        }
        return profileName;
    }

    private static boolean isPlayerLike(String value) {
        if (value == null || !USERNAME.matcher(value).matches()) return false;
        for (String option : OPTIONS) if (option.equalsIgnoreCase(value)) return false; // tab headers like "Sword"
        return true;
    }

    // ---------------------------------------------------------------- display

    /** " [HT3]" in the tier color for the tab list, or empty text if unknown / still loading. */
    public static Text suffix(String username) {
        String tier = lookup(username);
        return tier == null ? Text.empty() : Text.literal(" ").append(TierColors.tag(tier));
    }

    /** "[HT3]" in the tier color for the name tag, or null if unknown / still loading. */
    public static Text tag(String username) {
        String tier = lookup(username);
        return tier == null ? null : TierColors.tag(tier);
    }

    private static String lookup(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) return null;
        String key = username.toLowerCase(Locale.ROOT);
        CachedTier cached = CACHE.get(key);
        boolean stale = cached == null || System.currentTimeMillis() - cached.fetchedAt >= cached.ttl;
        if (stale && IN_FLIGHT.putIfAbsent(key, Boolean.TRUE) == null) {
            try {
                EXECUTOR.execute(() -> fetch(key, username));
            } catch (RuntimeException e) {
                IN_FLIGHT.remove(key);
            }
        }
        // Keep showing the old tier while a refresh is running so it does not flicker.
        return cached == null ? null : cached.tier;
    }

    // ---------------------------------------------------------------- network

    private static void fetch(String key, String username) {
        String selection = configuredTier;
        LOG.info("[mcpvp_tiers] looking up {} ({})", username, selection);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://www.mcpvp.com/@" + username))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "MCPVP-Tiers-Fabric/1.0")
                    .header("Accept", "text/html")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int status = res.statusCode();
            String found = null;
            long ttl = ERROR_TTL;
            if (status == 200) {
                found = parseTier(res.body(), username, selection);
                ttl = OK_TTL;
                if (found == null) {
                    String text = Jsoup.parse(res.body()).text();
                    int i = text.toLowerCase(Locale.ROOT).indexOf("rankings");
                    LOG.warn("[mcpvp_tiers] {} ({}): no tier parsed. Page text near rankings: {}", username, selection,
                            i < 0 ? "<no 'rankings' found; length " + text.length() + ">" : text.substring(i, Math.min(text.length(), i + 400)));
                } else {
                    LOG.info("[mcpvp_tiers] {} ({}) -> {}", username, selection, found);
                }
            } else if (status == 404) {
                ttl = OK_TTL; // player has no profile on the site
                LOG.info("[mcpvp_tiers] {}: no profile on mcpvp.com (404)", username);
            } else {
                LOG.warn("[mcpvp_tiers] {}: HTTP {}", username, status);
            }
            put(key, selection, new CachedTier(found, System.currentTimeMillis(), ttl));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            // Network errors AND any parsing surprise: cache briefly so we never retry every frame.
            LOG.warn("[mcpvp_tiers] {}: lookup failed", username, e);
            put(key, selection, new CachedTier(null, System.currentTimeMillis(), ERROR_TTL));
        } finally {
            IN_FLIGHT.remove(key);
            try {
                Thread.sleep(REQUEST_GAP_MILLIS); // be polite to the site when the tab list is large
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void put(String key, String selection, CachedTier value) {
        // Drop results fetched for a setting the player has since changed.
        if (selection.equals(configuredTier)) CACHE.put(key, value);
    }

    // ---------------------------------------------------------------- parsing

    static String parseTier(String html, String username, String selection) {
        String text = Jsoup.parse(html).text();
        String lower = text.toLowerCase(Locale.ROOT);
        String wanted = selection.trim().toLowerCase(Locale.ROOT);

        if (wanted.equals("overall")) return parseOverall(text, lower, username);

        int skin = lower.indexOf("minecraft skin");
        int start = lower.indexOf("rankings", Math.max(skin, 0));
        if (start < 0) return null;
        int end = lower.indexOf("overall ranking", start);
        if (end < 0 || end - start < 10) end = Math.min(text.length(), start + 2500);
        String segment = text.substring(start, end);

        if (wanted.equals("highest")) {
            Matcher ranks = TIER.matcher(segment);
            String best = null;
            while (ranks.find()) {
                String candidate = ranks.group(1).toUpperCase(Locale.ROOT);
                if (best == null || rank(candidate) < rank(best)) best = candidate;
            }
            return best;
        }

        // A specific kit. The lookbehinds stop "SMP" from matching inside "Diamond SMP"
        // and "Pot" from matching inside "Netherite Pot".
        Matcher kit = Pattern.compile("(?i)(?<![A-Za-z])(?<!diamond )(?<!netherite )"
                + Pattern.quote(selection.trim()) + "[\\s:\\-]*" + TIER_REGEX + "\\b").matcher(segment);
        return kit.find() ? kit.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static String parseOverall(String text, String lower, String username) {
        // 1) "<name>'s Minecraft Skin HT4 60.5 / 75 Points"
        Matcher strict = Pattern.compile("(?i)" + Pattern.quote(username)
                + "['\u2019]s Minecraft Skin\\s+" + TIER_REGEX + "\\s+[0-9,.]+\\s*/\\s*[0-9,.]+\\s*Points").matcher(text);
        if (strict.find()) return strict.group(1).toUpperCase(Locale.ROOT);
        // 2) a tier label directly in front of the "x / y Points" line
        Matcher points = OVERALL_WITH_POINTS.matcher(text);
        if (points.find()) return points.group(1).toUpperCase(Locale.ROOT);
        // 3) first tier label after the "Overall Ranking" heading
        int heading = lower.indexOf("overall ranking");
        if (heading >= 0) {
            Matcher after = TIER.matcher(text.substring(heading, Math.min(text.length(), heading + 300)));
            if (after.find()) return after.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /** Lower is better: HT1 is best, then MT1, LT1, then HT2... */
    private static int rank(String tier) {
        int level = tier.charAt(2) - '0';
        int place = switch (tier.charAt(0)) {
            case 'H' -> 0;
            case 'M' -> 1;
            case 'L' -> 2;
            default -> 3;
        };
        return level * 4 + place;
    }

    private record CachedTier(String tier, long fetchedAt, long ttl) {}
}
