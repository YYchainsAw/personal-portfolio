package xyz.yychainsaw.portfolio.auth.cli;

public interface SecretConsole {
    String readLine(String prompt);

    char[] readSecret(String prompt);

    void println(String value);
}
