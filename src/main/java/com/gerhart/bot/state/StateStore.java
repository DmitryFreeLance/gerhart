package com.gerhart.bot.state;

import com.gerhart.bot.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class StateStore {
    private final Database database;

    public StateStore(Database database) {
        this.database = database;
    }

    public void setState(long tgId, String state, String payload) {
        String sql = """
                INSERT INTO user_states(tg_id, state, payload, updated_at)
                VALUES (?, ?, ?, strftime('%s','now'))
                ON CONFLICT(tg_id) DO UPDATE SET
                    state = excluded.state,
                    payload = excluded.payload,
                    updated_at = excluded.updated_at
                """;
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            ps.setString(2, state);
            ps.setString(3, payload);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<State> getState(long tgId) {
        String sql = "SELECT state, payload FROM user_states WHERE tg_id = ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new State(rs.getString("state"), rs.getString("payload")));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void clearState(long tgId) {
        String sql = "DELETE FROM user_states WHERE tg_id = ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public record State(String state, String payload) {
    }
}
