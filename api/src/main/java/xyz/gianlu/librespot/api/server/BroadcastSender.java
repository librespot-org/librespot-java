package xyz.gianlu.librespot.api.server;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public interface BroadcastSender {
    void sendTextBroadcast(@NotNull String payload);

    void sendBytesBroadcast(@NotNull byte[] payload);
}
