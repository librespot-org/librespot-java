package xyz.gianlu.librespot.api.server;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public interface Sender extends BroadcastSender {
    void sendText(@NotNull String payload);

    void sendBytes(@NotNull byte[] payload);
}
