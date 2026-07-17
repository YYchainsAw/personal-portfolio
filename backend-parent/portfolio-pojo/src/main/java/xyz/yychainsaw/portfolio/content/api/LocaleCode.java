package xyz.yychainsaw.portfolio.content.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LocaleCode {
    ZH_CN("zh-CN"),
    EN("en");

    private final String value;

    LocaleCode(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static LocaleCode from(String value) {
        for (LocaleCode locale : values()) {
            if (locale.value.equals(value)) {
                return locale;
            }
        }
        throw new IllegalArgumentException("unsupported locale: " + value);
    }
}
