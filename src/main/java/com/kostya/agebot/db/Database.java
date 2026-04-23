package com.kostya.agebot.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {
    private final String dbPath;

    public Database(String dbPath) {
        this.dbPath = dbPath;
    }

    public void init() {
        try {
            ensureParentDirectoryExists();
            try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL;");
                statement.execute("PRAGMA foreign_keys=ON;");
                statement.execute("PRAGMA busy_timeout=5000;");

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS users (
                            user_id INTEGER PRIMARY KEY,
                            username TEXT,
                            first_name TEXT,
                            first_ref TEXT NOT NULL DEFAULT 'direct',
                            is_verified INTEGER NOT NULL DEFAULT 0,
                            verified_at TEXT,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        );
                        """);

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS start_events (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            user_id INTEGER NOT NULL,
                            source TEXT NOT NULL,
                            payload TEXT,
                            created_at TEXT NOT NULL,
                            FOREIGN KEY (user_id) REFERENCES users(user_id)
                        );
                        """);

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS admins (
                            user_id INTEGER PRIMARY KEY,
                            added_by INTEGER,
                            created_at TEXT NOT NULL
                        );
                        """);

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS settings (
                            key TEXT PRIMARY KEY,
                            value TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        );
                        """);

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS invite_links (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            user_id INTEGER NOT NULL,
                            group_id TEXT NOT NULL,
                            invite_link TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            FOREIGN KEY (user_id) REFERENCES users(user_id)
                        );
                        """);

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS generated_refs (
                            source TEXT PRIMARY KEY,
                            created_by INTEGER NOT NULL,
                            created_at TEXT NOT NULL
                        );
                        """);

                statement.execute("CREATE INDEX IF NOT EXISTS idx_start_events_source ON start_events(source);");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_users_first_ref ON users(first_ref);");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize database", e);
        }
    }

    public Connection getConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON;");
            statement.execute("PRAGMA busy_timeout=5000;");
        }
        return connection;
    }

    private void ensureParentDirectoryExists() throws Exception {
        Path dbFile = Path.of(dbPath);
        Path parent = dbFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
