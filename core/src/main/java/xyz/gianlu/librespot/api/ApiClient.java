package xyz.gianlu.librespot.api;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.Session;

/**
 * @author Gianlu
 */
public class ApiClient {
    private final Session session;

    public ApiClient(@NotNull Session session) {
        this.session = session;
    }
}
