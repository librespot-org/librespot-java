package xyz.gianlu.librespot.api;

import org.jetbrains.annotations.NotNull;

/**
 * Configuration parameters used for the `api` module
 */
public interface ApiConfiguration {
    int apiPort();

    @NotNull
    String apiHost();
}
