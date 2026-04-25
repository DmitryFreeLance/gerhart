package com.gerhart.bot.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public record AppConfig(
        String botToken,
        String botUsername,
        String inviteBotUsername,
        Set<Long> adminTgIds,
        java.util.List<Long> systemUplineTgIds,
        String dbUrl,
        int maxLevel,
        String supportContact
) {
    public static AppConfig fromEnv() {
        String token = required("BOT_TOKEN");
        String username = required("BOT_USERNAME");
        String inviteUsername = System.getenv().getOrDefault("INVITE_BOT_USERNAME", "trusthand_bot");
        String adminsRaw = System.getenv().getOrDefault("ADMIN_IDS", "");
        Set<Long> adminIds = Arrays.stream(adminsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toSet());
        String systemUplinesRaw = System.getenv().getOrDefault("SYSTEM_UPLINE_TG_IDS", "");
        java.util.List<Long> systemUplines = Arrays.stream(systemUplinesRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();

        String dbPath = System.getenv().getOrDefault("DB_PATH", "data/bot.db");
        String dbUrl = "jdbc:sqlite:" + dbPath;
        int maxLvl = Integer.parseInt(System.getenv().getOrDefault("MAX_LEVEL", "8"));
        String support = System.getenv().getOrDefault("SUPPORT_CONTACT", "@support");

        return new AppConfig(token, username, inviteUsername, adminIds, systemUplines, dbUrl, maxLvl, support);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Environment variable " + key + " is required");
        }
        return value;
    }
}
