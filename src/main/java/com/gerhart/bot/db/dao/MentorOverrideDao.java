package com.gerhart.bot.db.dao;

import com.gerhart.bot.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class MentorOverrideDao {
    private final Database database;

    public MentorOverrideDao(Database database) {
        this.database = database;
    }

    public Optional<Long> findOverrideSellerId(long buyerUserId, int level) {
        String sql = "SELECT seller_user_id FROM mentor_overrides WHERE buyer_user_id = ? AND level = ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, buyerUserId);
            ps.setInt(2, level);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong(1));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void upsertOverride(long buyerUserId, int level, long sellerUserId) {
        String sql = """
                INSERT INTO mentor_overrides(buyer_user_id, level, seller_user_id, created_at)
                VALUES (?, ?, ?, strftime('%s','now'))
                ON CONFLICT(buyer_user_id, level) DO UPDATE SET
                    seller_user_id = excluded.seller_user_id,
                    created_at = excluded.created_at
                """;
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, buyerUserId);
            ps.setInt(2, level);
            ps.setLong(3, sellerUserId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
