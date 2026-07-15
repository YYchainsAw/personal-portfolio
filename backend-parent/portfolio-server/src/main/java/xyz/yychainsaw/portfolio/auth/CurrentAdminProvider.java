package xyz.yychainsaw.portfolio.auth;

import java.util.UUID;

public interface CurrentAdminProvider {
    UUID requireAdminId();
}
