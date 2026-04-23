package com.kostya.agebot.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public record BotConfig(String botToken,
                        String botUsername,
                        String dbPath,
                        String defaultGroupId,
                        Set<Long> initialAdminIds) {

    public static BotConfig fromEnv() {
        Map<String, String> env = System.getenv();

        String token = requireAny(env, "BOT_TOKEN", "TELEGRAM_BOT_TOKEN");
        String username = requireAny(env, "BOT_USERNAME", "TELEGRAM_BOT_USERNAME");

        String dbPath = getOrDefault(env, "DB_PATH", "./data/bot.db");
        String groupId = blankToNull(getOrDefault(env, "TARGET_GROUP_ID", ""));
        Set<Long> adminIds = parseAdminIds(getOrDefault(env, "ADMIN_IDS", ""));

        return new BotConfig(token, username, dbPath, groupId, adminIds);
    }

    private static String requireAny(Map<String, String> env, String... keys) {
        for (String key : keys) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalArgumentException("Missing required env variable: " + String.join(" or ", keys));
    }

    private static String getOrDefault(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static Set<Long> parseAdminIds(String csv) {
        Set<Long> ids = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return ids;
        }

        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .forEach(raw -> {
                    try {
                        long id = Long.parseLong(raw);
                        if (id > 0) {
                            ids.add(id);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                });

        return ids;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
