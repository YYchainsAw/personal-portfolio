package xyz.yychainsaw.portfolio.message.application;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record MessagePage(List<MessageSummary> items, String nextCursor) {
    private static final int MAXIMUM_CURSOR_LENGTH = 116;
    private static final Pattern CURSOR_TOKEN = Pattern.compile("[A-Za-z0-9_-]+");

    public MessagePage {
        items = List.copyOf(Objects.requireNonNull(items, "message page items are required"));
        if (nextCursor != null
                && (nextCursor.isBlank()
                        || nextCursor.length() > MAXIMUM_CURSOR_LENGTH
                        || !CURSOR_TOKEN.matcher(nextCursor).matches())) {
            throw new IllegalArgumentException("message page cursor is invalid");
        }
    }
}
