package xyz.gianlu.librespot.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Gianlu
 */
public interface AuthConfiguration {
    @Nullable
    String username();

    @Nullable
    String password();

    @Nullable
    String blob();

    @NotNull
    Strategy strategy();

    enum Strategy {
        FACEBOOK, BLOB,
        USER_PASS, ZEROCONF
    }
}
