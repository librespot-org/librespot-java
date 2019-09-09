package xyz.gianlu.librespot.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spotify.metadata.proto.Metadata;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.server.AbsApiHandler;
import xyz.gianlu.librespot.api.server.ApiServer;
import xyz.gianlu.librespot.api.server.ApiServer.PredefinedJsonRpcError;
import xyz.gianlu.librespot.api.server.ApiServer.PredefinedJsonRpcException;
import xyz.gianlu.librespot.common.ProtobufToJson;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.model.PlayableId;
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

    private void handleLoad(ApiServer.@NotNull Request request) throws PredefinedJsonRpcException {
        if (request.params == null || !request.params.isJsonArray())
            throw PredefinedJsonRpcException.from(request, PredefinedJsonRpcError.INVALID_PARAMS);

        JsonObject params = request.params.getAsJsonObject();
        if (!params.has("uri"))
            throw PredefinedJsonRpcException.from(request, PredefinedJsonRpcError.INVALID_REQUEST);

        boolean play = false;
        String uri = params.get("uri").getAsString();
        if (params.has("play")) play = params.get("play").getAsBoolean();

        player.load(uri, play);
    }

    @Override
    protected @NotNull JsonElement handleRequest(ApiServer.@NotNull Request request) throws PredefinedJsonRpcException {
        switch (request.getSuffix()) {
            case "load":
                handleLoad(request);
                break;
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
            case "currentlyPlaying":
                Metadata.Track track = player.currentTrack();
                if (track == null) {
                    Metadata.Episode episode = player.currentEpisode();
                    if (episode == null) {
                        PlayableId id = player.currentPlayableId();
                        if (id == null) {
                            throw PredefinedJsonRpcException.from(request, PlayerRpcError.NO_TRACK);
                        } else {
                            JsonObject obj = new JsonObject();
                            obj.addProperty("gid", Utils.bytesToHex(id.getGid()));
                            obj.addProperty("uri", id.toSpotifyUri());
                            return obj;
                        }
                    } else {
                        return ProtobufToJson.convert(episode);
                    }
                } else {
                    return ProtobufToJson.convert(track);
                }
            default:
                throw PredefinedJsonRpcException.from(request, PredefinedJsonRpcError.METHOD_NOT_FOUND);
        }

        return string("OK");
    }

    @Override
    protected void handleNotification(ApiServer.@NotNull Request request) {
    }

    public enum PlayerRpcError implements ApiServer.JsonRpcError {
        NO_TRACK(10001, "No track found.");

        private final int code;
        private final String msg;

        PlayerRpcError(int code, @NotNull String msg) {
            this.code = code;
            this.msg = msg;
        }

        @Override
        public int code() {
            return code;
        }

        @Override
        public @NotNull String msg() {
            return msg;
        }
    }
}
