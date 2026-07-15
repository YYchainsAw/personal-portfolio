package xyz.yychainsaw.portfolio.auth.persistence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RecoveryCodeRepository {
    private final JdbcClient jdbc;

    public RecoveryCodeRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    @Transactional
    public void replace(UUID adminId, List<String> hashes) {
        Objects.requireNonNull(adminId, "admin id is required");
        List<String> snapshot = validatedSnapshot(hashes);
        lockAdmin(adminId);

        jdbc.sql("delete from portfolio.totp_recovery_code where admin_id=:adminId")
                .param("adminId", adminId)
                .update();
        for (String hash : snapshot) {
            int changed = jdbc.sql("""
                            insert into portfolio.totp_recovery_code(id, admin_id, code_hash)
                            values (:id, :adminId, :hash)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("adminId", adminId)
                    .param("hash", hash)
                    .update();
            if (changed != 1) {
                throw new IllegalStateException(
                        "recovery-code insert affected an unexpected number of rows");
            }
        }
    }

    public List<StoredCode> findUnused(UUID adminId) {
        Objects.requireNonNull(adminId, "admin id is required");
        List<StoredCode> codes = jdbc.sql("""
                        select id, code_hash
                        from portfolio.totp_recovery_code
                        where admin_id=:adminId and used_at is null
                        order by created_at, id
                        """)
                .param("adminId", adminId)
                .query((resultSet, rowNumber) -> new StoredCode(
                        resultSet.getObject("id", UUID.class), resultSet.getString("code_hash")))
                .list();
        return List.copyOf(codes);
    }

    @Transactional
    public boolean markUsed(UUID adminId, UUID codeId) {
        Objects.requireNonNull(adminId, "admin id is required");
        Objects.requireNonNull(codeId, "recovery-code id is required");
        lockAdmin(adminId);
        int changed = jdbc.sql("""
                        update portfolio.totp_recovery_code
                        set used_at=clock_timestamp()
                        where id=:codeId and admin_id=:adminId and used_at is null
                        """)
                .param("codeId", codeId)
                .param("adminId", adminId)
                .update();
        if (changed == 0) {
            return false;
        }
        if (changed == 1) {
            return true;
        }
        throw new IllegalStateException(
                "recovery-code consumption affected an unexpected number of rows");
    }

    private void lockAdmin(UUID adminId) {
        boolean exists = jdbc.sql("""
                        select id from portfolio.admin_user
                        where id=:adminId
                        for update
                        """)
                .param("adminId", adminId)
                .query(UUID.class)
                .optional()
                .isPresent();
        if (!exists) {
            throw new IllegalStateException("administrator does not exist");
        }
    }

    private static List<String> validatedSnapshot(List<String> hashes) {
        if (hashes == null || hashes.size() < 1 || hashes.size() > 10) {
            throw new IllegalArgumentException(
                    "recovery-code hash count must be between 1 and 10");
        }
        List<String> snapshot = new ArrayList<>(hashes.size());
        Set<String> distinct = new HashSet<>();
        for (String hash : hashes) {
            validateHash(hash);
            if (!distinct.add(hash)) {
                throw new IllegalArgumentException("recovery-code hashes must be distinct");
            }
            snapshot.add(hash);
        }
        return List.copyOf(snapshot);
    }

    private static void validateHash(String hash) {
        if (hash == null || hash.isBlank() || hash.length() > 255) {
            throw new IllegalArgumentException(
                    "recovery-code hash must contain between 1 and 255 characters");
        }
    }

    public record StoredCode(UUID id, String hash) {
        public StoredCode {
            if (id == null) {
                throw new IllegalArgumentException("recovery-code id is required");
            }
            validateHash(hash);
        }

        @Override
        public String toString() {
            return "StoredCode[id=" + id + ", hash=<redacted>]";
        }
    }
}
