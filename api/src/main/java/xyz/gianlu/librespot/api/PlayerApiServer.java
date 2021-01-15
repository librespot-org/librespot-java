package xyz.gianlu.librespot.api;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.handlers.InstanceHandler;
import xyz.gianlu.librespot.api.handlers.PlayerHandler;

/**
 * @author devgianlu
 */
public class PlayerApiServer extends ApiServer {
    private final PlayerWrapper wrapper;

    public PlayerApiServer(int port, @NotNull String host, @NotNull PlayerWrapper wrapper) {
        super(port, host, wrapper);
        this.wrapper = wrapper;

        handler.post("/player/{cmd}", new PlayerHandler(wrapper));
        handler.post("/instance/{action}", InstanceHandler.forPlayer(this, wrapper)); // Overrides session only handler
        wrapper.setListener(events);
    }
}
