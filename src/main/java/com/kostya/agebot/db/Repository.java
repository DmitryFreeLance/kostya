package com.kostya.agebot.db;

import org.telegram.telegrambots.meta.api.objects.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class Repository {
    private final Database database;

    public Repository(Database database) {
        this.database = database;
    }

    public void recordStart(User user, String source, String payload) {
        String now = Instant.now().toString();

        String upsertUserSql = """
                INSERT INTO users (user_id, username, first_name, first_ref, is_verified, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    username = excluded.username,
                    first_name = excluded.first_name,
                    updated_at = excluded.updated_at;
                """;

        String insertStartSql = """
                INSERT INTO start_events (user_id, source, payload, created_at)
                VALUES (?, ?, ?, ?);
                """;

        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement(upsertUserSql);
                 PreparedStatement insertStart = connection.prepareStatement(insertStartSql)) {

                upsert.setLong(1, user.getId());
                upsert.setString(2, user.getUserName());
                upsert.setString(3, user.getFirstName());
                upsert.setString(4, source);
                upsert.setString(5, now);
                upsert.setString(6, now);
                upsert.executeUpdate();

                insertStart.setLong(1, user.getId());
                insertStart.setString(2, source);
                insertStart.setString(3, payload);
                insertStart.setString(4, now);
                insertStart.executeUpdate();

                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record /start", e);
        }
    }

    public boolean isUserVerified(long userId) {
        String sql = "SELECT is_verified FROM users WHERE user_id = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getInt("is_verified") == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check user verification", e);
        }
    }

    public boolean markUserVerified(long userId) {
        String now = Instant.now().toString();
        String sql = """
                UPDATE users
                SET is_verified = 1,
                    verified_at = ?,
                    updated_at = ?
                WHERE user_id = ?
                  AND is_verified = 0;
                """;

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, now);
            statement.setString(2, now);
            statement.setLong(3, userId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark user as verified", e);
        }
    }

    public void saveInviteLink(long userId, String groupId, String inviteLink) {
        String sql = """
                INSERT INTO invite_links (user_id, group_id, invite_link, created_at)
                VALUES (?, ?, ?, ?);
                """;

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, groupId);
            statement.setString(3, inviteLink);
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store invite link", e);
        }
    }

    public void bootstrapAdmins(Set<Long> adminIds) {
        if (adminIds == null || adminIds.isEmpty()) {
            return;
        }
        String sql = """
                INSERT OR IGNORE INTO admins (user_id, added_by, created_at)
                VALUES (?, NULL, ?);
                """;
        String now = Instant.now().toString();

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Long adminId : adminIds) {
                if (adminId == null || adminId <= 0) {
                    continue;
                }
                statement.setLong(1, adminId);
                statement.setString(2, now);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to bootstrap admins", e);
        }
    }

    public boolean isAdmin(long userId) {
        String sql = "SELECT 1 FROM admins WHERE user_id = ? LIMIT 1";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check admin", e);
        }
    }

    public List<Long> getAdminIds() {
        String sql = "SELECT user_id FROM admins ORDER BY user_id ASC";
        List<Long> ids = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong("user_id"));
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load admins", e);
        }
    }

    public int getAdminCount() {
        String sql = "SELECT COUNT(*) FROM admins";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count admins", e);
        }
    }

    public boolean addAdmin(long newAdminId, long addedBy) {
        String sql = """
                INSERT OR IGNORE INTO admins (user_id, added_by, created_at)
                VALUES (?, ?, ?);
                """;

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, newAdminId);
            statement.setLong(2, addedBy);
            statement.setString(3, Instant.now().toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add admin", e);
        }
    }

    public boolean removeAdmin(long adminId) {
        String sql = "DELETE FROM admins WHERE user_id = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, adminId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to remove admin", e);
        }
    }

    public Optional<String> getGroupId() {
        String sql = "SELECT value FROM settings WHERE key = 'group_id'";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (rs.next()) {
                String value = rs.getString("value");
                return Optional.ofNullable(value);
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read group_id", e);
        }
    }

    public void setGroupId(String groupId) {
        String sql = """
                INSERT INTO settings (key, value, updated_at)
                VALUES ('group_id', ?, ?)
                ON CONFLICT(key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at;
                """;

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupId);
            statement.setString(2, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update group_id", e);
        }
    }

    public void setInitialGroupIdIfMissing(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return;
        }

        String sql = """
                INSERT INTO settings (key, value, updated_at)
                VALUES ('group_id', ?, ?)
                ON CONFLICT(key) DO NOTHING;
                """;

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupId);
            statement.setString(2, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set initial group_id", e);
        }
    }

    public void saveGeneratedSource(String source, long createdBy) {
        String sql = """
                INSERT INTO generated_refs (source, created_by, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT(source) DO UPDATE SET
                    created_by = excluded.created_by,
                    created_at = excluded.created_at;
                """;

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, source);
            statement.setLong(2, createdBy);
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save generated source", e);
        }
    }

    public Stats getStats() {
        Map<String, MutableSourceStat> statsBySource = new HashMap<>();

        String startsSql = "SELECT source, COUNT(*) AS starts FROM start_events GROUP BY source";
        String generatedSql = "SELECT source FROM generated_refs";
        String usersSql = """
                SELECT first_ref AS source,
                       COUNT(*) AS users,
                       SUM(CASE WHEN is_verified = 1 THEN 1 ELSE 0 END) AS verified
                FROM users
                GROUP BY first_ref;
                """;

        try (Connection connection = database.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(startsSql);
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String source = safeSource(rs.getString("source"));
                    MutableSourceStat stat = statsBySource.computeIfAbsent(source, k -> new MutableSourceStat());
                    stat.starts = rs.getLong("starts");
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(generatedSql);
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String source = safeSource(rs.getString("source"));
                    statsBySource.computeIfAbsent(source, k -> new MutableSourceStat());
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(usersSql);
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String source = safeSource(rs.getString("source"));
                    MutableSourceStat stat = statsBySource.computeIfAbsent(source, k -> new MutableSourceStat());
                    stat.users = rs.getLong("users");
                    stat.verified = rs.getLong("verified");
                }
            }

            long totalStarts = scalarLong(connection, "SELECT COUNT(*) FROM start_events");
            long uniqueStarts = scalarLong(connection, "SELECT COUNT(DISTINCT user_id) FROM start_events");
            long totalUsers = scalarLong(connection, "SELECT COUNT(*) FROM users");
            long totalVerified = scalarLong(connection, "SELECT COUNT(*) FROM users WHERE is_verified = 1");

            Map<String, MutableSourceStat> ordered = new LinkedHashMap<>();
            statsBySource.entrySet().stream()
                    .sorted((left, right) -> {
                        int byStarts = Long.compare(right.getValue().starts, left.getValue().starts);
                        if (byStarts != 0) {
                            return byStarts;
                        }
                        int byUsers = Long.compare(right.getValue().users, left.getValue().users);
                        if (byUsers != 0) {
                            return byUsers;
                        }
                        return left.getKey().compareTo(right.getKey());
                    })
                    .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));

            List<SourceStat> sourceStats = new ArrayList<>();
            for (Map.Entry<String, MutableSourceStat> entry : ordered.entrySet()) {
                sourceStats.add(new SourceStat(
                        entry.getKey(),
                        entry.getValue().starts,
                        entry.getValue().users,
                        entry.getValue().verified
                ));
            }

            return new Stats(totalStarts, uniqueStarts, totalUsers, totalVerified, sourceStats);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load stats", e);
        }
    }

    private String safeSource(String source) {
        if (source == null || source.isBlank()) {
            return "direct";
        }
        return source;
    }

    private long scalarLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        }
    }

    private static class MutableSourceStat {
        private long starts;
        private long users;
        private long verified;
    }

    public record SourceStat(String source, long starts, long users, long verifiedUsers) {
    }

    public record Stats(long totalStarts,
                        long uniqueStarts,
                        long totalUsers,
                        long totalVerified,
                        List<SourceStat> sourceStats) {
    }
}
