package xyz.gianlu.librespot.mercury;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public interface SubListener {
    void event(@NotNull MercuryClient.Response resp);
}
