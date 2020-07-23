package xyz.gianlu.librespot.api;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.handlers.PlayerHandler;

/**
 * @author devgianlu
 */
public class PlayerApiServer extends ApiServer {
    public PlayerApiServer(int port, @NotNull String host, @NotNull PlayerWrapper wrapper) {
        super(port, host, wrapper);

        handler.post("/player/{cmd}", new PlayerHandler(wrapper));
        wrapper.setListener(events);
    }
}
