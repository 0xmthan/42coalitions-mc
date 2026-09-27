package fr.fortytwo.coalitions.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** config.yml plus the credentials from .env, resolved once per reload. */
public record Settings(
        String clientId,
        String clientSecret,
        String baseUrl,
        int campusId,
        int cursusId,
        double requestsPerSecond,
        Duration timeout,

        boolean requireCoalition,
        boolean allowOnApiError,
        Set<String> bypass,
        Map<String, String> aliases,
        String unknownUserMessage,
        String noCoalitionMessage,
        String apiErrorMessage,

        Set<String> allowed,
        List<String> preferred,
        Map<String, String> colorOverrides,

        boolean colorNameTags,
        boolean colorChat,
        String tagPrefix,

        boolean friendlyFire,
        boolean coverIndirect,
        String friendlyFireMessage,

        Duration cacheTtl,
        Duration negativeCacheTtl,
        boolean persistCache) {

    public static Settings from(FileConfiguration config, DotEnv env) {
        int campus = config.getInt("intra.campus-id", 0);
        if (campus <= 0) {
            campus = parseInt(env.get("FT_CAMPUS_ID", ""), 0);
        }

        return new Settings(
                env.get("FT_CLIENT_ID", ""),
                env.get("FT_CLIENT_SECRET", ""),
                config.getString("intra.base-url", "https://api.intra.42.fr"),
                campus,
                config.getInt("intra.cursus-id", 21),
                config.getDouble("intra.requests-per-second", 2.0),
                Duration.ofSeconds(Math.max(1, config.getInt("intra.timeout-seconds", 10))),

                config.getBoolean("login.require-coalition", true),
                "allow".equalsIgnoreCase(config.getString("login.on-api-error", "deny")),
                lowercased(config.getStringList("login.bypass")),
                lowercasedKeys(config.getConfigurationSection("login.aliases")),
                config.getString("login.messages.unknown-user", "<red>You are not on a 42 coalition.</red>"),
                config.getString("login.messages.no-coalition", "<red>Your 42 account has no coalition.</red>"),
                config.getString("login.messages.api-error", "<red>Could not reach the 42 API.</red>"),

                lowercased(config.getStringList("coalitions.allow")),
                config.getStringList("coalitions.preferred").stream()
                        .map(value -> value.toLowerCase(Locale.ROOT)).toList(),
                lowercasedKeys(config.getConfigurationSection("coalitions.colors")),

                config.getBoolean("display.name-tags", true),
                config.getBoolean("display.chat", true),
                config.getString("display.tag-prefix", ""),

                config.getBoolean("pvp.friendly-fire", false),
                config.getBoolean("pvp.cover-indirect", true),
                config.getString("pvp.friendly-fire-message", ""),

                Duration.ofMinutes(Math.max(1, config.getLong("cache.ttl-minutes", 360))),
                Duration.ofMinutes(Math.max(1, config.getLong("cache.negative-ttl-minutes", 15))),
                config.getBoolean("cache.persist", true));
    }

    /** The 42 login to look up for a Minecraft name. */
    public String loginFor(String minecraftName) {
        String key = minecraftName.toLowerCase(Locale.ROOT);
        return aliases.getOrDefault(key, key);
    }

    public boolean isBypassed(String minecraftName) {
        return bypass.contains(minecraftName.toLowerCase(Locale.ROOT));
    }

    /** Whether a house counts, by slug, name or id. Empty "allow" means all. */
    public boolean allows(int id, String slug, String name) {
        return allowed.isEmpty()
                || allowed.contains(String.valueOf(id))
                || allowed.contains(slug.toLowerCase(Locale.ROOT))
                || allowed.contains(name.toLowerCase(Locale.ROOT));
    }

    private static Set<String> lowercased(List<String> values) {
        Set<String> out = new HashSet<>(values.size());
        for (String value : values) {
            out.add(value.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static Map<String, String> lowercasedKeys(ConfigurationSection section) {
        Map<String, String> out = new HashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String value = section.getString(key);
                if (value != null) {
                    out.put(key.toLowerCase(Locale.ROOT), value);
                }
            }
        }
        return out;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
