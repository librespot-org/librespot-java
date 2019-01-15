package xyz.gianlu.librespot.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Gianlu
 */
public interface AuthConfiguration {
    @Nullable
    String authUsername();

    @Nullable
    String authPassword();

    @Nullable
    String authBlob();

    @NotNull
    Strategy authStrategy();

    enum Strategy {
        FACEBOOK, BLOB,
        USER_PASS, ZEROCONF
    }
}
