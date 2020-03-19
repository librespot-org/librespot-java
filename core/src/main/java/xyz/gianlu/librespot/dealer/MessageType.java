package xyz.gianlu.librespot.dealer;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public enum MessageType {
    PING("ping"), PONG("pong"), MESSAGE("message"), REQUEST("request");

    private final String val;

    MessageType(@NotNull String val) {
        this.val = val;
    }

    @NotNull
    public static MessageType parse(@NotNull String type) {
        for (MessageType msg : values())
            if (msg.val.equals(type))
                return msg;

        throw new IllegalArgumentException("Unknown MessageType: " + type);
    }

    @NotNull
    public String value() {
        return val;
    }
}
