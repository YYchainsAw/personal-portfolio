package xyz.yychainsaw.portfolio.publishing.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SnapshotCodec {
    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper mapper;

    public SnapshotCodec(ObjectMapper base) {
        mapper = base.copy()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public EncodedSnapshot encode(Object value) {
        try {
            String json = mapper.writeValueAsString(value);
            String sha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(json.getBytes(StandardCharsets.UTF_8)));
            return new EncodedSnapshot(SCHEMA_VERSION, json, sha256);
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("cannot encode publication snapshot", error);
        }
    }

    public <T> T decode(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("invalid publication snapshot", error);
        }
    }
}
