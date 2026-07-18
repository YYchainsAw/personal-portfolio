package xyz.yychainsaw.portfolio.support;

import org.testcontainers.utility.DockerImageName;

public final class PostgresTestImage {
    public static final DockerImageName NAME = DockerImageName
            .parse("postgres:17-bookworm@sha256:4f736ae292687621d4dbe0d499ffd024a36bd2ee7d8ca6f2ccd4c800f047b394")
            .asCompatibleSubstituteFor("postgres");

    private PostgresTestImage() {
    }
}
