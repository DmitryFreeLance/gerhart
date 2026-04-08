package com.gerhart.bot.model;

public record Sale(
        long id,
        long sellerUserId,
        long buyerUserId,
        int level,
        SaleStatus status,
        String proofType,
        String proofFileId,
        long createdAt,
        Long reviewerUserId,
        Long reviewedAt,
        String rejectionReason
) {
}
