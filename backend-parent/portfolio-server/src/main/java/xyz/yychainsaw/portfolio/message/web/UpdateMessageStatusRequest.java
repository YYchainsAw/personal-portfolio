package xyz.yychainsaw.portfolio.message.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import xyz.yychainsaw.portfolio.message.application.MessageStatus;

public record UpdateMessageStatusRequest(
        @NotNull(message = "must not be null") MessageStatus status,
        @NotNull(message = "must not be null")
                @PositiveOrZero(message = "must be greater than or equal to 0")
                Integer version) {
}
