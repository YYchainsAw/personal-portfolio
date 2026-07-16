package xyz.yychainsaw.portfolio.media.storage;

import java.util.regex.Pattern;

public final class TencentCosProperties {
    private static final int MAXIMUM_CREDENTIAL_LENGTH = 256;
    private static final int MAXIMUM_SESSION_TOKEN_LENGTH = 4096;
    private static final Pattern REGION = Pattern.compile(
            "(?:ap|na|eu|sa|af|me)-[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern BUCKET = Pattern.compile(
            "(?:[a-z0-9]|[a-z0-9][a-z0-9-]{0,48}[a-z0-9])-[1-9][0-9]{4,11}");
    private final String region;
    private final String bucket;
    private final String secretId;
    private final String secretKey;
    private final String sessionToken;

    public TencentCosProperties(
            String region,
            String bucket,
            String secretId,
            String secretKey,
            String sessionToken) {
        if (region == null || region.length() > 64 || !REGION.matcher(region).matches()) {
            throw new IllegalArgumentException("COS region is invalid");
        }
        if (bucket == null || bucket.length() > 63 || !BUCKET.matcher(bucket).matches()) {
            throw new IllegalArgumentException("COS bucket is invalid");
        }
        if (!validCredential(secretId, MAXIMUM_CREDENTIAL_LENGTH)
                || !validCredential(secretKey, MAXIMUM_CREDENTIAL_LENGTH)) {
            throw new IllegalArgumentException("COS credentials are invalid");
        }
        if (sessionToken != null && sessionToken.isBlank()) {
            sessionToken = null;
        } else if (sessionToken != null
                && !validCredential(sessionToken, MAXIMUM_SESSION_TOKEN_LENGTH)) {
            throw new IllegalArgumentException("COS session token is invalid");
        }
        this.region = region;
        this.bucket = bucket;
        this.secretId = secretId;
        this.secretKey = secretKey;
        this.sessionToken = sessionToken;
    }

    public String region() {
        return region;
    }

    public String bucket() {
        return bucket;
    }

    public String secretId() {
        return secretId;
    }

    public String secretKey() {
        return secretKey;
    }

    public String sessionToken() {
        return sessionToken;
    }

    @Override
    public String toString() {
        return "TencentCosProperties[region=" + region + ", bucket=" + bucket
                + ", secretId=<redacted>, secretKey=<redacted>, sessionToken=<redacted>]";
    }

    private static boolean validCredential(String value, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
