package com.gerhart.bot.db.dao;

import com.gerhart.bot.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class AppTextDao {
    private final Database database;

    public AppTextDao(Database database) {
        this.database = database;
    }

    public Optional<String> get(String key) {
        String sql = "SELECT value FROM app_texts WHERE key = ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString(1));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void put(String key, String value) {
        String sql = """
                INSERT INTO app_texts(key, value, updated_at)
                VALUES (?, ?, strftime('%s','now'))
                ON CONFLICT(key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at
                """;
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
