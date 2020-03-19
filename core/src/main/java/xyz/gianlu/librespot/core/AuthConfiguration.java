package xyz.gianlu.librespot.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

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

    boolean storeCredentials();

    @Nullable
    File credentialsFile();

    default boolean hasStoredCredentials() {
        File file = credentialsFile();
        return storeCredentials() && file != null && file.exists() && file.canRead();
    }

    enum Strategy {
        FACEBOOK, BLOB,
        USER_PASS, ZEROCONF
    }
}
