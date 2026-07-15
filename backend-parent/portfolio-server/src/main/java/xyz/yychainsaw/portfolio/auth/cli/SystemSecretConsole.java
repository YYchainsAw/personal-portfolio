package xyz.yychainsaw.portfolio.auth.cli;

import java.io.Console;
import java.io.IOError;
import java.io.PrintWriter;
import java.util.Arrays;
import org.springframework.stereotype.Component;

@Component
public final class SystemSecretConsole implements SecretConsole {
    private static final String NO_CONSOLE =
            "administrator CLI requires an interactive system console";
    private static final String INPUT_CANCELLED = "administrator CLI input was cancelled";
    private static final String INPUT_FAILED = "administrator CLI input failed";
    private static final String OUTPUT_FAILED = "administrator CLI output failed";

    private final Console console;

    public SystemSecretConsole() {
        this(System.console());
    }

    SystemSecretConsole(Console console) {
        this.console = console;
    }

    @Override
    public String readLine(String prompt) {
        requirePrompt(prompt);
        Console provider = requireConsole();
        requireReadableThread();

        String value;
        try {
            value = provider.readLine("%s", prompt);
        } catch (RuntimeException | IOError providerFailure) {
            throw inputFailed();
        }
        if (Thread.currentThread().isInterrupted() || value == null) {
            throw inputCancelled();
        }
        return value;
    }

    @Override
    public char[] readSecret(String prompt) {
        requirePrompt(prompt);
        Console provider = requireConsole();
        requireReadableThread();

        char[] value;
        try {
            value = provider.readPassword("%s", prompt);
        } catch (RuntimeException | IOError providerFailure) {
            throw inputFailed();
        }
        if (Thread.currentThread().isInterrupted()) {
            wipe(value);
            throw inputCancelled();
        }
        if (value == null) {
            throw inputCancelled();
        }
        return value;
    }

    @Override
    public void println(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value is required");
        }
        Console provider = requireConsole();
        if (Thread.currentThread().isInterrupted()) {
            throw outputFailed();
        }

        try {
            PrintWriter writer = provider.writer();
            writer.println(value);
            writer.flush();
            boolean outputError = writer.checkError();
            if (Thread.currentThread().isInterrupted() || outputError) {
                throw outputFailed();
            }
        } catch (RuntimeException | IOError providerFailure) {
            throw outputFailed();
        }
    }

    private static void requirePrompt(String prompt) {
        if (prompt == null) {
            throw new IllegalArgumentException("prompt is required");
        }
    }

    private Console requireConsole() {
        if (console == null) {
            throw new IllegalStateException(NO_CONSOLE);
        }
        return console;
    }

    private static void requireReadableThread() {
        if (Thread.currentThread().isInterrupted()) {
            throw inputCancelled();
        }
    }

    private static IllegalStateException inputCancelled() {
        return new IllegalStateException(INPUT_CANCELLED);
    }

    private static IllegalStateException inputFailed() {
        return new IllegalStateException(INPUT_FAILED);
    }

    private static IllegalStateException outputFailed() {
        return new IllegalStateException(OUTPUT_FAILED);
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
