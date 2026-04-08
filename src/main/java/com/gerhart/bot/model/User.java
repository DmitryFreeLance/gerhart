package com.gerhart.bot.model;

public record User(
        long id,
        long tgId,
        String username,
        String firstName,
        Long sponsorUserId,
        int purchasedLevel,
        Role role,
        String email,
        String paymentDetails
) {
}
