package com.gerhart.bot.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public record AppConfig(
        String botToken,
        String botUsername,
        Set<Long> adminTgIds,
        String dbUrl,
        int maxLevel,
        String supportContact
) {
    public static AppConfig fromEnv() {
        String token = required("BOT_TOKEN");
        String username = required("BOT_USERNAME");
        String adminsRaw = System.getenv().getOrDefault("ADMIN_IDS", "");
        Set<Long> adminIds = Arrays.stream(adminsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toSet());

        String dbPath = System.getenv().getOrDefault("DB_PATH", "data/bot.db");
        String dbUrl = "jdbc:sqlite:" + dbPath;
        int maxLvl = Integer.parseInt(System.getenv().getOrDefault("MAX_LEVEL", "8"));
        String support = System.getenv().getOrDefault("SUPPORT_CONTACT", "@support");

        return new AppConfig(token, username, adminIds, dbUrl, maxLvl, support);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Environment variable " + key + " is required");
        }
        return value;
    }
}
