package com.gerhart.bot.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {
    private final String dbUrl;

    public Database(String dbUrl) {
        this.dbUrl = dbUrl;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    public void init() {
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        tg_id INTEGER NOT NULL UNIQUE,
                        username TEXT,
                        first_name TEXT,
                        sponsor_user_id INTEGER,
                        purchased_level INTEGER NOT NULL DEFAULT 1,
                        role TEXT NOT NULL DEFAULT 'USER',
                        email TEXT,
                        payment_details TEXT,
                        created_at INTEGER NOT NULL DEFAULT (strftime('%s','now')),
                        FOREIGN KEY (sponsor_user_id) REFERENCES users(id)
                    )
                    """);

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sales (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        seller_user_id INTEGER NOT NULL,
                        buyer_user_id INTEGER NOT NULL,
                        level INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        proof_type TEXT,
                        proof_file_id TEXT,
                        created_at INTEGER NOT NULL DEFAULT (strftime('%s','now')),
                        reviewer_user_id INTEGER,
                        reviewed_at INTEGER,
                        rejection_reason TEXT,
                        FOREIGN KEY (seller_user_id) REFERENCES users(id),
                        FOREIGN KEY (buyer_user_id) REFERENCES users(id),
                        FOREIGN KEY (reviewer_user_id) REFERENCES users(id)
                    )
                    """);

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS user_states (
                        tg_id INTEGER PRIMARY KEY,
                        state TEXT NOT NULL,
                        payload TEXT,
                        updated_at INTEGER NOT NULL DEFAULT (strftime('%s','now'))
                    )
                    """);

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mentor_overrides (
                        buyer_user_id INTEGER NOT NULL,
                        level INTEGER NOT NULL,
                        seller_user_id INTEGER NOT NULL,
                        created_at INTEGER NOT NULL DEFAULT (strftime('%s','now')),
                        PRIMARY KEY (buyer_user_id, level),
                        FOREIGN KEY (buyer_user_id) REFERENCES users(id),
                        FOREIGN KEY (seller_user_id) REFERENCES users(id)
                    )
                    """);

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS app_texts (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL,
                        updated_at INTEGER NOT NULL DEFAULT (strftime('%s','now'))
                    )
                    """);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database", e);
        }
    }
}
