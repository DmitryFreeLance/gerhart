package com.gerhart.bot.db.dao;

import com.gerhart.bot.db.Database;
import com.gerhart.bot.model.Role;
import com.gerhart.bot.model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class UserDao {
    private final Database database;

    public UserDao(Database database) {
        this.database = database;
    }

    public Optional<User> findByTgId(long tgId) {
        String sql = "SELECT * FROM users WHERE tg_id = ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<User> findById(long id) {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public User create(long tgId, String username, String firstName, Long sponsorUserId, Role role) {
        String sql = "INSERT INTO users(tg_id, username, first_name, sponsor_user_id, purchased_level, role) VALUES(?, ?, ?, ?, 0, ?)";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            ps.setString(2, username);
            ps.setString(3, firstName);
            if (sponsorUserId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, sponsorUserId);
            }
            ps.setString(5, role.name());
            ps.executeUpdate();
            return findByTgId(tgId).orElseThrow();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void updateProfile(long tgId, String username, String firstName) {
        String sql = "UPDATE users SET username = ?, first_name = ? WHERE tg_id = ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, firstName);
            ps.setLong(3, tgId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void setPurchasedLevel(long userId, int level) {
        String sql = "UPDATE users SET purchased_level = ? WHERE id = ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, level);
            ps.setLong(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void setSponsorIfAbsent(long userId, long sponsorUserId) {
        String sql = "UPDATE users SET sponsor_user_id = ? WHERE id = ? AND sponsor_user_id IS NULL";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sponsorUserId);
            ps.setLong(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void setEmail(long userId, String email) {
        String sql = "UPDATE users SET email = ? WHERE id = ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setLong(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void setPaymentDetails(long userId, String paymentDetails) {
        String sql = "UPDATE users SET payment_details = ? WHERE id = ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, paymentDetails);
            ps.setLong(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countDirectReferrals(long sponsorUserId) {
        String sql = "SELECT COUNT(*) FROM users WHERE sponsor_user_id = ? AND purchased_level >= 1";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sponsorUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<User> getDirectReferrals(long sponsorUserId, int limit) {
        String sql = "SELECT * FROM users WHERE sponsor_user_id = ? AND purchased_level >= 1 ORDER BY id DESC LIMIT ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sponsorUserId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<User> users = new ArrayList<>();
                while (rs.next()) {
                    users.add(map(rs));
                }
                return users;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<User> listUsers(int limit) {
        String sql = "SELECT * FROM users ORDER BY id DESC LIMIT ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<User> users = new ArrayList<>();
                while (rs.next()) {
                    users.add(map(rs));
                }
                return users;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<User> findUplineByDistance(long userId, int distance) {
        long currentUserId = userId;
        for (int i = 0; i < distance; i++) {
            Optional<User> current = findById(currentUserId);
            if (current.isEmpty() || current.get().sponsorUserId() == null) {
                return Optional.empty();
            }
            currentUserId = current.get().sponsorUserId();
        }
        return findById(currentUserId);
    }

    public int countAllUsers() {
        String sql = "SELECT COUNT(*) FROM users";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private User map(ResultSet rs) throws SQLException {
        return new User(
                rs.getLong("id"),
                rs.getLong("tg_id"),
                rs.getString("username"),
                rs.getString("first_name"),
                getNullableLong(rs, "sponsor_user_id"),
                rs.getInt("purchased_level"),
                Role.valueOf(rs.getString("role")),
                rs.getString("email"),
                rs.getString("payment_details")
        );
    }

    private Long getNullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
