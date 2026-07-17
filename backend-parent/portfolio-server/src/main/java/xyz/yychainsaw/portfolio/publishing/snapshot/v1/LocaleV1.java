package xyz.yychainsaw.portfolio.publishing.snapshot.v1;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LocaleV1 {
    ZH_CN("zh-CN"),
    EN("en");

    private final String value;

    LocaleV1(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static LocaleV1 from(String value) {
        for (LocaleV1 locale : values()) {
            if (locale.value.equals(value)) {
                return locale;
            }
        }
        throw new IllegalArgumentException("unsupported snapshot locale: " + value);
    }
}
