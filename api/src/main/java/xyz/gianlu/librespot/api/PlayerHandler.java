package xyz.gianlu.librespot.api;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.server.AbsApiHandler;
import xyz.gianlu.librespot.api.server.ApiServer;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.model.TrackId;
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
                player.play();
                break;
            case "pause":
                player.pause();
                break;
            case "next":
                player.next();
                break;
            case "prev":
                player.previous();
                break;
            case "playPause":
                player.playPause();
                break;
            case "load":
                player.load(ApiUtils.extractId(TrackId.class, request, request.params));
                break;
            default:
                throw ApiServer.PredefinedJsonRpcException.from(request, ApiServer.PredefinedJsonRpcError.METHOD_NOT_FOUND);
        }

        return string("OK");
    }

    @Override
    protected void handleNotification(ApiServer.@NotNull Request request) {
    }
}
