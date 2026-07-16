package xyz.yychainsaw.portfolio.auth.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;

@Repository
public class AdminUserRepository {
    private static final RowMapper<AdminUser> ADMIN_MAPPER = AdminUserRepository::map;

    private final JdbcClient jdbc;

    public AdminUserRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public long count() {
        return jdbc.sql("select count(*) from portfolio.admin_user")
                .query(Long.class)
                .single();
    }

    public Optional<AdminUser> findByUsername(String username) {
        if (!isExternalUsernameValid(username)) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select id, username, password_hash, status, totp_key_version, totp_nonce,
                               totp_ciphertext, last_login_at, version, created_at, updated_at
                        from portfolio.admin_user
                        where lower(username)=lower(:username)
                        """)
                .param("username", username)
                .query(ADMIN_MAPPER)
                .optional();
    }

    public Optional<AdminUser> findById(UUID id) {
        Objects.requireNonNull(id, "admin id is required");
        return jdbc.sql("""
                        select id, username, password_hash, status, totp_key_version, totp_nonce,
                               totp_ciphertext, last_login_at, version, created_at, updated_at
                        from portfolio.admin_user
                        where id=:id
                        """)
                .param("id", id)
                .query(ADMIN_MAPPER)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AdminUser> findByIdForUpdate(UUID id) {
        Objects.requireNonNull(id, "admin id is required");
        return jdbc.sql("""
                        select id, username, password_hash, status, totp_key_version, totp_nonce,
                               totp_ciphertext, last_login_at, version, created_at, updated_at
                        from portfolio.admin_user
                        where id=:id
                        for update
                        """)
                .param("id", id)
                .query(ADMIN_MAPPER)
                .optional();
    }

    public void insert(AdminUser admin) {
        Objects.requireNonNull(admin, "admin user is required");
        int changed = jdbc.sql("""
                        insert into portfolio.admin_user
                            (id, singleton_key, username, password_hash, status, totp_key_version,
                             totp_nonce, totp_ciphertext, last_login_at, version, created_at, updated_at)
                        values
                            (:id, true, :username, :passwordHash, :status, :keyVersion,
                             :nonce, :ciphertext, :lastLoginAt, :version, :createdAt, :updatedAt)
                        """)
                .param("id", admin.id())
                .param("username", admin.username())
                .param("passwordHash", admin.passwordHash())
                .param("status", admin.status().name())
                .param("keyVersion", admin.totpSecret().keyVersion())
                .param("nonce", admin.totpSecret().nonce())
                .param("ciphertext", admin.totpSecret().ciphertext())
                .param("lastLoginAt", toOffsetDateTime(admin.lastLoginAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("version", admin.version())
                .param("createdAt", toOffsetDateTime(admin.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("updatedAt", toOffsetDateTime(admin.updatedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        requireOne(changed, "admin insert affected an unexpected number of rows");
    }

    public void updateLastLogin(UUID id, Instant instant) {
        Objects.requireNonNull(id, "admin id is required");
        Objects.requireNonNull(instant, "last-login timestamp is required");
        int changed = jdbc.sql("""
                        update portfolio.admin_user
                        set last_login_at=:instant, version=version+1
                        where id=:id
                        """)
                .param("instant", toOffsetDateTime(instant), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", id)
                .update();
        requireOne(changed, "admin last-login update affected an unexpected number of rows");
    }

    public void replaceCredentials(UUID id, String passwordHash, EncryptedTotpSecret secret) {
        Objects.requireNonNull(id, "admin id is required");
        if (passwordHash == null || passwordHash.isBlank() || passwordHash.length() > 255) {
            throw new IllegalArgumentException(
                    "password hash must contain between 1 and 255 characters");
        }
        Objects.requireNonNull(secret, "encrypted TOTP secret is required");
        int changed = jdbc.sql("""
                        update portfolio.admin_user
                        set password_hash=:passwordHash,
                            totp_key_version=:keyVersion,
                            totp_nonce=:nonce,
                            totp_ciphertext=:ciphertext,
                            status='ACTIVE',
                            version=version+1
                        where id=:id
                        """)
                .param("passwordHash", passwordHash)
                .param("keyVersion", secret.keyVersion())
                .param("nonce", secret.nonce())
                .param("ciphertext", secret.ciphertext())
                .param("id", id)
                .update();
        requireOne(changed, "admin credential update affected an unexpected number of rows");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long updatePassword(UUID adminId, String passwordHash) {
        Objects.requireNonNull(adminId, "admin id is required");
        requirePasswordHash(passwordHash);
        return jdbc.sql("""
                        update portfolio.admin_user
                        set password_hash=:passwordHash,
                            version=version+1
                        where id=:adminId
                        returning version
                        """)
                .param("passwordHash", passwordHash)
                .param("adminId", adminId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "admin password update affected an unexpected number of rows"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long updateTotp(UUID adminId, EncryptedTotpSecret secret) {
        Objects.requireNonNull(adminId, "admin id is required");
        EncryptedTotpSecret snapshot = snapshot(
                Objects.requireNonNull(secret, "encrypted TOTP secret is required"));
        return jdbc.sql("""
                        update portfolio.admin_user
                        set totp_key_version=:keyVersion,
                            totp_nonce=:nonce,
                            totp_ciphertext=:ciphertext,
                            version=version+1
                        where id=:adminId
                        returning version
                        """)
                .param("keyVersion", snapshot.keyVersion())
                .param("nonce", snapshot.nonce())
                .param("ciphertext", snapshot.ciphertext())
                .param("adminId", adminId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "admin TOTP update affected an unexpected number of rows"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long bumpSecurityVersion(UUID adminId) {
        Objects.requireNonNull(adminId, "admin id is required");
        return jdbc.sql("""
                        update portfolio.admin_user
                        set version=version+1
                        where id=:adminId
                        returning version
                        """)
                .param("adminId", adminId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "admin security-version update affected an unexpected number of rows"));
    }

    private static AdminUser map(ResultSet resultSet, int rowNumber) throws SQLException {
        OffsetDateTime lastLogin = resultSet.getObject("last_login_at", OffsetDateTime.class);
        return new AdminUser(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("username"),
                resultSet.getString("password_hash"),
                AdminStatus.valueOf(resultSet.getString("status")),
                new EncryptedTotpSecret(
                        resultSet.getInt("totp_key_version"),
                        resultSet.getBytes("totp_nonce"),
                        resultSet.getBytes("totp_ciphertext")),
                lastLogin == null ? null : lastLogin.toInstant(),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static boolean isExternalUsernameValid(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        int length = username.codePointCount(0, username.length());
        return length >= 3 && length <= 64;
    }

    private static void requirePasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank() || passwordHash.length() > 255) {
            throw new IllegalArgumentException(
                    "password hash must contain between 1 and 255 characters");
        }
    }

    private static EncryptedTotpSecret snapshot(EncryptedTotpSecret secret) {
        return new EncryptedTotpSecret(
                secret.keyVersion(), secret.nonce(), secret.ciphertext());
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static void requireOne(int changed, String message) {
        if (changed != 1) {
            throw new IllegalStateException(message);
        }
    }
}
