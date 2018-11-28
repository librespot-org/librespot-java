package xyz.gianlu.librespot.api.server;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public interface Receiver {
    void onReceivedText(@NotNull Sender sender, @NotNull String payload);

    void onReceivedBytes(@NotNull Sender sender, @NotNull byte[] payload);
}
