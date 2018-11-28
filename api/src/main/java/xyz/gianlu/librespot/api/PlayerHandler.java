package xyz.gianlu.librespot.api;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.server.AbsApiHandler;
import xyz.gianlu.librespot.api.server.ApiServer;

/**
 * @author Gianlu
 */
public class PlayerHandler extends AbsApiHandler {
    public PlayerHandler() {
        super("player");
    }

    @Override
    protected @NotNull JsonElement handleRequest(ApiServer.@NotNull Request request) {
        return string("OK");
    }

    @Override
    protected void handleNotification(ApiServer.@NotNull Request request) {
    }
}
