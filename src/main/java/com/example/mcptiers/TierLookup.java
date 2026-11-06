package com.example.mcptiers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks up tiers from MCPVP, MCTiers, PvPTiers, or SubTiers asynchronously.
 * Rendering code only reads the in-memory cache and never blocks on network I/O.
 */
public final class TierLookup {
    private static final Logger LOG = LoggerFactory.getLogger("mcpvp_tiers");
    private static final String[] OPTIONS = {"highest", "overall", "Sword", "SMP", "UHC", "Shield", "Pot", "Mace", "Spear", "Early Game", "Late Game", "End Game", "Diamond SMP", "Netherite Pot", "Creeper", "Cart", "Bow", "Crystal"};
    private static final String[] SOURCES = {"MCPVP", "MCTIERS", "PVPTIERS", "SUBTIERS"};
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final String TIER_REGEX = "(R?[SLMH]T[1-6])";
    private static final Pattern TIER = Pattern.compile("(?i)\\b" + TIER_REGEX + "\\b");
    private static final Pattern OVERALL_WITH_POINTS = Pattern.compile("(?i)\\b([SLMH]T[1-6])\\s+[0-9,.]+\\s*/\\s*[0-9,.]+\\s*Points");

    private static final long OK_TTL = Duration.ofMinutes(10).toMillis();
    private static final long ERROR_TTL = Duration.ofSeconds(60).toMillis();
    private static final long REQUEST_GAP_MILLIS = 150;
    private static final Map<String, CachedTier> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "tier-provider-lookup");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile String configuredTier = "highest";
    private static volatile String configuredSource = "MCPVP";
    private static volatile Path configPath;

    private TierLookup() {}

    // --------------------------------------------------------------- config

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
                properties.setProperty("source", "MCPVP");
                try (var out = Files.newOutputStream(path)) {
                    properties.store(out, "MCPVP Tiers settings");
                }
            }
        } catch (Exception exception) {
            LOG.warn("[mcpvp_tiers] Could not read config; using defaults", exception);
        }

        String savedTier = canonicalOption(properties.getProperty("tier", "highest"));
        String savedSource = canonicalSource(properties.getProperty("source", "MCPVP"));
        configuredTier = savedTier == null ? "highest" : savedTier;
        // MCPVP remains the default for existing and new config files.
        configuredSource = savedSource == null ? "MCPVP" : savedSource;
        saveSettings();
        LOG.info("[mcpvp_tiers] tier setting = {}, source = {}", configuredTier, configuredSource);
    }

    public static String getConfiguredTier() {
        return configuredTier;
    }

    public static String getConfiguredSource() {
        return configuredSource;
    }

    public static synchronized void setConfiguredTier(String tier) {
        String option = canonicalOption(tier);
        if (option == null) return;
        configuredTier = option;
        CACHE.clear();
        saveSettings();
    }

    public static synchronized void setConfiguredSource(String source) {
        String option = canonicalSource(source);
        if (option == null) return;
        configuredSource = option;
        CACHE.clear();
        IN_FLIGHT.clear();
        saveSettings();
        LOG.info("[mcpvp_tiers] switched provider to {}", option);
    }

    public static String[] getOptions() {
        return OPTIONS.clone();
    }

    public static String[] getSources() {
        return SOURCES.clone();
    }

    private static String canonicalOption(String value) {
        if (value == null) return null;
        for (String option : OPTIONS) if (option.equalsIgnoreCase(value.trim())) return option;
        return null;
    }

    private static String canonicalSource(String value) {
        if (value == null) return null;
        for (String source : SOURCES) if (source.equalsIgnoreCase(value.trim())) return source;
        return null;
    }

    private static synchronized void saveSettings() {
        Path path = configPath;
        if (path == null) return;
        Properties properties = new Properties();
        properties.setProperty("tier", configuredTier);
        properties.setProperty("source", configuredSource);
        try {
            Files.createDirectories(path.getParent());
            try (var out = Files.newOutputStream(path)) {
                properties.store(out, "MCPVP Tiers settings");
            }
        } catch (Exception exception) {
            LOG.warn("[mcpvp_tiers] Could not save settings", exception);
        }
    }

    private static final AtomicInteger DEBUG_BUDGET = new AtomicInteger(25);

    public static void debug(String message) {
        if (DEBUG_BUDGET.getAndDecrement() > 0) LOG.info("[mcpvp_tiers] {}", message);
    }

    // --------------------------------------------------------------- display

    public static String resolveName(String profileName, Text display) {
        String shown = display == null ? "" : display.getString().trim();
        if (isPlayerLike(shown)) return shown;
        if (profileName == null || !USERNAME.matcher(profileName).matches()) {
            String[] parts = shown.split("\\s+");
            for (int i = parts.length - 1; i >= 0; i--) {
                if (isPlayerLike(parts[i])) return parts[i];
            }
        }
        return profileName;
    }

    private static boolean isPlayerLike(String value) {
        if (value == null || !USERNAME.matcher(value).matches()) return false;
        for (String option : OPTIONS) if (option.equalsIgnoreCase(value)) return false;
        return true;
    }

    public static Text suffix(String username) {
        String tier = lookup(username);
        return tier == null ? Text.empty() : Text.literal(" ").append(TierColors.tag(tier));
    }

    public static Text tag(String username) {
        String tier = lookup(username);
        return tier == null ? null : TierColors.tag(tier);
    }

    private static String lookup(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) return null;
        String source = configuredSource;
        String key = source + ":" + username.toLowerCase(Locale.ROOT);
        CachedTier cached = CACHE.get(key);
        boolean stale = cached == null || System.currentTimeMillis() - cached.fetchedAt >= cached.ttl;
        if (stale && IN_FLIGHT.putIfAbsent(key, Boolean.TRUE) == null) {
            try {
                EXECUTOR.execute(() -> fetch(key, username, source, configuredTier));
            } catch (RuntimeException exception) {
                IN_FLIGHT.remove(key);
            }
        }
        return cached == null ? null : cached.tier;
    }

    // --------------------------------------------------------------- network

    private static void fetch(String key, String username, String source, String selection) {
        LOG.info("[mcpvp_tiers] looking up {} from {} ({})", username, source, selection);
        try {
            String tier = switch (source) {
                case "MCTIERS" -> fetchJsonTier(
                        "https://mctiers.com/api/v2/profile/by-name/" + encode(username), username, selection);
                case "PVPTIERS" -> fetchJsonTier(
                        "https://pvptiers.com/api/search_profile/" + encode(username.toLowerCase(Locale.ROOT)), username, selection);
                case "SUBTIERS" -> fetchSubTiers(username, selection);
                default -> fetchMcpvpTier(username, selection);
            };
            long ttl = tier == null ? ERROR_TTL : OK_TTL;
            put(key, source, selection, new CachedTier(tier, System.currentTimeMillis(), ttl));
            if (tier == null) LOG.info("[mcpvp_tiers] no {} tier found for {} ({})", source, username, selection);
            else LOG.info("[mcpvp_tiers] {} / {} ({}) -> {}", source, username, selection, tier);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Throwable exception) {
            LOG.warn("[mcpvp_tiers] {} lookup failed for {}", source, username, exception);
            put(key, source, selection, new CachedTier(null, System.currentTimeMillis(), ERROR_TTL));
        } finally {
            IN_FLIGHT.remove(key);
            try {
                Thread.sleep(REQUEST_GAP_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String fetchMcpvpTier(String username, String selection) throws Exception {
        HttpResponse<String> response = get("https://www.mcpvp.com/@" + encode(username));
        if (response.statusCode() != 200) return null;
        String found = parseTier(response.body(), username, selection);
        if (found == null) {
            String pageText = Jsoup.parse(response.body()).text();
            int index = pageText.toLowerCase(Locale.ROOT).indexOf("rankings");
            LOG.warn("[mcpvp_tiers] MCPVP page did not contain a parsed tier for {}. Text: {}",
                    username, index < 0 ? "<no rankings section>" :
                            pageText.substring(index, Math.min(pageText.length(), index + 350)));
        }
        return found;
    }

    private static String fetchSubTiers(String username, String selection) throws Exception {
        // SubTiers' profile API is UUID-based, so resolve the current Minecraft name first.
        HttpResponse<String> profile = get("https://api.mojang.com/users/profiles/minecraft/" + encode(username));
        if (profile.statusCode() == 204 || profile.statusCode() == 404) return null;
        if (profile.statusCode() != 200) throw new IllegalStateException("Mojang profile HTTP " + profile.statusCode());
        JsonObject mojang = JsonParser.parseString(profile.body()).getAsJsonObject();
        if (!mojang.has("id")) return null;
        String rawUuid = mojang.get("id").getAsString();
        String uuid = rawUuid.replaceFirst(
                "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                "$1-$2-$3-$4-$5");
        HttpResponse<String> response = get("https://subtiers.net/api/v2/profile/" + uuid);
        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200) throw new IllegalStateException("SubTiers HTTP " + response.statusCode());
        return parseJsonTier(response.body(), selection);
    }

    private static String fetchJsonTier(String url, String username, String selection) throws Exception {
        HttpResponse<String> response = get(url);
        if (response.statusCode() == 404 || response.statusCode() == 422) return null;
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        return parseJsonTier(response.body(), selection);
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(9))
                .header("User-Agent", "MCPVP-Tiers-Fabric/1.1")
                .header("Accept", "application/json,text/html")
                .GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void put(String key, String source, String selection, CachedTier value) {
        if (source.equals(configuredSource) && selection.equals(configuredTier)) CACHE.put(key, value);
    }

    // --------------------------------------------------------------- parsing

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

        if (wanted.equals("highest")) return bestTier(segment);

        Matcher kit = Pattern.compile("(?i)(?<![A-Za-z])(?<!diamond )(?<!netherite )"
                + Pattern.quote(selection.trim()) + "[\\s:\\-]*" + TIER_REGEX + "\\b").matcher(segment);
        return kit.find() ? kit.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static String parseOverall(String text, String lower, String username) {
        Matcher strict = Pattern.compile("(?i)" + Pattern.quote(username)
                + "['\\u2019]s Minecraft Skin\\s+([SLMH]T[1-6])\\s+[0-9,.]+\\s*/\\s*[0-9,.]+\\s*Points").matcher(text);
        if (strict.find()) return strict.group(1).toUpperCase(Locale.ROOT);
        Matcher points = OVERALL_WITH_POINTS.matcher(text);
        if (points.find()) return points.group(1).toUpperCase(Locale.ROOT);
        int heading = lower.indexOf("overall ranking");
        if (heading >= 0) {
            Matcher after = TIER.matcher(text.substring(heading, Math.min(text.length(), heading + 300)));
            if (after.find()) return after.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static String parseJsonTier(String json, String selection) {
        JsonElement root = JsonParser.parseString(json);
        List<NamedTier> tiers = new ArrayList<>();
        collectTiers(root, "", tiers);
        if (tiers.isEmpty()) return null;

        String wanted = normalizeMode(selection);
        if (wanted.equals("overall")) {
            for (NamedTier tier : tiers) {
                if (normalizeMode(tier.mode).equals("overall")) return tier.tier;
            }
        } else if (!wanted.equals("highest")) {
            for (NamedTier tier : tiers) {
                if (normalizeMode(tier.mode).equals(wanted)) return tier.tier;
            }
        }
        String best = null;
        for (NamedTier tier : tiers) {
            if (best == null || rank(tier.tier) < rank(best)) best = tier.tier;
        }
        return best;
    }

    private static void collectTiers(JsonElement element, String mode, List<NamedTier> found) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("tier") && !object.get("tier").isJsonNull()) {
                String tier = tierValue(object.get("tier"));
                if (tier != null) found.add(new NamedTier(mode, tier));
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String childMode = entry.getKey();
                // Wrapper keys should not replace the actual gamemode name.
                if (childMode.equalsIgnoreCase("data") || childMode.equalsIgnoreCase("profile")
                        || childMode.equalsIgnoreCase("rankings") || childMode.equalsIgnoreCase("player")) {
                    childMode = mode;
                }
                collectTiers(entry.getValue(), childMode, found);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectTiers(child, mode, found);
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString().trim();
            Matcher matcher = TIER.matcher(value);
            if (matcher.matches() && !mode.isBlank()) {
                found.add(new NamedTier(mode, matcher.group(1).toUpperCase(Locale.ROOT)));
            }
        }
    }

    private static String tierValue(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String candidate = value.getAsString().trim();
            Matcher matcher = TIER.matcher(candidate);
            return matcher.matches() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String key : List.of("code", "name", "tier")) {
                if (object.has(key) && object.get(key).isJsonPrimitive()) {
                    String candidate = object.get(key).getAsString().trim();
                    Matcher matcher = TIER.matcher(candidate);
                    if (matcher.matches()) return matcher.group(1).toUpperCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    private static String normalizeMode(String value) {
        String mode = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return switch (mode) {
            case "nethop", "netheritepot", "nethpot" -> "nethop";
            case "crystal", "crystalpvp" -> "crystal";
            case "uhc" -> "uhc";
            case "smp" -> "smp";
            case "sword" -> "sword";
            case "axe" -> "axe";
            case "mace" -> "mace";
            case "pot" -> "pot";
            case "shield" -> "shield";
            case "spear" -> "spear";
            case "earlygame" -> "earlygame";
            case "lategame" -> "lategame";
            case "endgame" -> "endgame";
            case "diamondsmp" -> "smp";
            case "overall" -> "overall";
            default -> mode;
        };
    }

    private static String bestTier(String text) {
        Matcher matcher = TIER.matcher(text);
        String best = null;
        while (matcher.find()) {
            String candidate = matcher.group(1).toUpperCase(Locale.ROOT);
            if (best == null || rank(candidate) < rank(best)) best = candidate;
        }
        return best;
    }

    /** Lower is better: HT1, MT1, LT1, then HT2... Retired tiers keep the same rank. */
    private static int rank(String tier) {
        String normalized = tier.startsWith("R") ? tier.substring(1) : tier;
        int level = normalized.charAt(normalized.length() - 1) - '0';
        int place = switch (normalized.charAt(0)) {
            case 'H' -> 0;
            case 'M' -> 1;
            case 'L' -> 2;
            default -> 3;
        };
        return level * 4 + place;
    }

    private record NamedTier(String mode, String tier) {}
    private record CachedTier(String tier, long fetchedAt, long ttl) {}
}
