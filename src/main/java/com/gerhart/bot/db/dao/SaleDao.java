package com.gerhart.bot.db.dao;

import com.gerhart.bot.db.Database;
import com.gerhart.bot.model.Sale;
import com.gerhart.bot.model.SaleStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SaleDao {
    private final Database database;

    public SaleDao(Database database) {
        this.database = database;
    }

    public Sale create(long sellerUserId, long buyerUserId, int level, String proofType, String proofFileId) {
        String sql = "INSERT INTO sales(seller_user_id, buyer_user_id, level, status, proof_type, proof_file_id) VALUES(?, ?, ?, 'PENDING', ?, ?)";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sellerUserId);
            ps.setLong(2, buyerUserId);
            ps.setInt(3, level);
            ps.setString(4, proofType);
            ps.setString(5, proofFileId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        String findSql = "SELECT * FROM sales WHERE buyer_user_id = ? AND level = ? ORDER BY id DESC LIMIT 1";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(findSql)) {
            ps.setLong(1, buyerUserId);
            ps.setInt(2, level);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return map(rs);
                }
                throw new IllegalStateException("Created sale not found");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<Sale> findById(long id) {
        String sql = "SELECT * FROM sales WHERE id = ?";
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

    public Optional<Sale> findPendingForBuyerAndLevel(long buyerUserId, int level) {
        String sql = "SELECT * FROM sales WHERE buyer_user_id = ? AND level = ? AND status = 'PENDING' ORDER BY id DESC LIMIT 1";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, buyerUserId);
            ps.setInt(2, level);
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

    public int countConfirmedSalesBySellerAndLevel(long sellerUserId, int level) {
        String sql = "SELECT COUNT(*) FROM sales WHERE seller_user_id = ? AND level = ? AND status = 'CONFIRMED'";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sellerUserId);
            ps.setInt(2, level);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Sale> listPendingBySeller(long sellerUserId, int limit) {
        String sql = "SELECT * FROM sales WHERE seller_user_id = ? AND status = 'PENDING' ORDER BY id ASC LIMIT ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sellerUserId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Sale> sales = new ArrayList<>();
                while (rs.next()) {
                    sales.add(map(rs));
                }
                return sales;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Sale> listPendingGlobal(int limit) {
        String sql = "SELECT * FROM sales WHERE status = 'PENDING' ORDER BY id ASC LIMIT ?";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Sale> sales = new ArrayList<>();
                while (rs.next()) {
                    sales.add(map(rs));
                }
                return sales;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void confirm(long saleId, long reviewerUserId) {
        String sql = "UPDATE sales SET status = 'CONFIRMED', reviewer_user_id = ?, reviewed_at = strftime('%s','now'), rejection_reason = NULL WHERE id = ? AND status = 'PENDING'";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, reviewerUserId);
            ps.setLong(2, saleId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void reject(long saleId, long reviewerUserId, String reason) {
        String sql = "UPDATE sales SET status = 'REJECTED', reviewer_user_id = ?, reviewed_at = strftime('%s','now'), rejection_reason = ? WHERE id = ? AND status = 'PENDING'";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, reviewerUserId);
            ps.setString(2, reason);
            ps.setLong(3, saleId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countAllSales() {
        String sql = "SELECT COUNT(*) FROM sales";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countPendingSales() {
        String sql = "SELECT COUNT(*) FROM sales WHERE status = 'PENDING'";
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Sale map(ResultSet rs) throws SQLException {
        return new Sale(
                rs.getLong("id"),
                rs.getLong("seller_user_id"),
                rs.getLong("buyer_user_id"),
                rs.getInt("level"),
                SaleStatus.valueOf(rs.getString("status")),
                rs.getString("proof_type"),
                rs.getString("proof_file_id"),
                rs.getLong("created_at"),
                (Long) rs.getObject("reviewer_user_id"),
                (Long) rs.getObject("reviewed_at"),
                rs.getString("rejection_reason")
        );
    }
}
