package xyz.gianlu.librespot.api;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.server.AbsApiHandler;
import xyz.gianlu.librespot.api.server.ApiServer;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.Player;

/**
 * @author Gianlu
 */
public class PlayerHandler extends AbsApiHandler {
    private final Player player;

    public PlayerHandler(@NotNull Session session) {
        super("player");
        this.player = session.player();
    }

    @Override
    protected @NotNull JsonElement handleRequest(ApiServer.@NotNull Request request) throws ApiServer.PredefinedJsonRpcException {
        switch (request.getSuffix()) {
            case "play":
            case "stop":
            case "pause":
            case "next":
            case "prev":
            case "playPause":
                return string("OK");
            default:
                throw ApiServer.PredefinedJsonRpcException.from(request, ApiServer.PredefinedJsonRpcError.METHOD_NOT_FOUND);

        }
    }

    @Override
    protected void handleNotification(ApiServer.@NotNull Request request) {
    }
}
