package xyz.gianlu.librespot.core;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.crypto.Packet;

/**
 * @author Gianlu
 */
public interface PacketsReceiver {
    void dispatch(@NotNull Packet packet);
}
