package xyz.gianlu.librespot.mercury.model;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public interface PlayableId {
    @NotNull byte[] getGid();

    @NotNull String hexId();
}
