package xyz.gianlu.librespot.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.AbstractMessageLite;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.api.server.AbsApiHandler;
import xyz.gianlu.librespot.api.server.ApiServer;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.ProtoJsonMercuryRequest;
import xyz.gianlu.librespot.mercury.model.*;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class MetadataHandler extends AbsApiHandler {
    private final MercuryClient client;
    private final Session session;

    public MetadataHandler(@NotNull Session session) {
        super("metadata");
        this.session = session;
        this.client = session.mercury();
    }

    @NotNull
    private static <I extends SpotifyId> I extractId(@NotNull Class<I> clazz, @NotNull ApiServer.Request request, @Nullable JsonElement params) throws ApiServer.PredefinedJsonRpcException {
        if (params == null || !params.isJsonObject())
            throw ApiServer.PredefinedJsonRpcException.from(request, ApiServer.PredefinedJsonRpcError.INVALID_PARAMS);

        try {
            JsonObject obj = params.getAsJsonObject();
            if (obj.has("gid")) {
                return SpotifyId.fromHex(clazz, obj.get("gid").getAsString());
            } else if (obj.has("uri")) {
                return SpotifyId.fromUri(clazz, obj.get("uri").getAsString());
            } else if (obj.has("base62")) {
                return SpotifyId.fromBase62(clazz, obj.get("gid").getAsString());
            } else {
                throw ApiServer.PredefinedJsonRpcException.from(request, ApiServer.PredefinedJsonRpcError.INVALID_REQUEST);
            }
        } catch (SpotifyId.SpotifyIdParsingException ex) {
            throw ApiServer.PredefinedJsonRpcException.from(request, ApiServer.PredefinedJsonRpcError.INVALID_REQUEST);
        }
    }

    @Override
    protected @NotNull JsonElement handleRequest(ApiServer.@NotNull Request request) throws ApiServer.PredefinedJsonRpcException, HandlingException {
        switch (request.getSuffix()) {
            case "rootlists":
                return handle(MercuryRequests.getRootPlaylists(session.apWelcome().getCanonicalUsername()));
            case "playlist":
                return handle(MercuryRequests.getPlaylist(extractId(PlaylistId.class, request, request.params)));
            case "track":
                return handle(MercuryRequests.getTrack(extractId(TrackId.class, request, request.params)));
            case "artist":
                return handle(MercuryRequests.getArtist(extractId(ArtistId.class, request, request.params)));
            case "album":
                return handle(MercuryRequests.getAlbum(extractId(AlbumId.class, request, request.params)));
            default:
                throw ApiServer.PredefinedJsonRpcException.from(request, ApiServer.PredefinedJsonRpcError.METHOD_NOT_FOUND);
        }
    }

    @NotNull
    private <P extends AbstractMessageLite> JsonElement handle(@NotNull ProtoJsonMercuryRequest<P> req) throws HandlingException {
        try {
            return client.sendSync(req).json();
        } catch (MercuryClient.MercuryException ex) {
            throw new HandlingException(ex, ErrorCode.MERCURY_EXCEPTION);
        } catch (IOException ex) {
            throw new HandlingException(ex, ErrorCode.IO_EXCEPTION);
        }
    }

    @Override
    protected void handleNotification(ApiServer.@NotNull Request request) {
    }
}
