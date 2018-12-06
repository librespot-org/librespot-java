package xyz.gianlu.librespot.api;

import com.google.gson.JsonElement;
import com.google.protobuf.AbstractMessageLite;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.server.AbsApiHandler;
import xyz.gianlu.librespot.api.server.ApiServer;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.ProtoJsonMercuryRequest;
import xyz.gianlu.librespot.mercury.model.AlbumId;
import xyz.gianlu.librespot.mercury.model.ArtistId;
import xyz.gianlu.librespot.mercury.model.PlaylistId;
import xyz.gianlu.librespot.mercury.model.TrackId;

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

    @Override
    protected @NotNull JsonElement handleRequest(ApiServer.@NotNull Request request) throws ApiServer.PredefinedJsonRpcException, HandlingException {
        switch (request.getSuffix()) {
            case "rootlists":
                return handle(MercuryRequests.getRootPlaylists(session.apWelcome().getCanonicalUsername()));
            case "playlist":
                return handle(MercuryRequests.getPlaylist(ApiUtils.extractId(PlaylistId.class, request, request.params)));
            case "track":
                return handle(MercuryRequests.getTrack(ApiUtils.extractId(TrackId.class, request, request.params)));
            case "artist":
                return handle(MercuryRequests.getArtist(ApiUtils.extractId(ArtistId.class, request, request.params)));
            case "album":
                return handle(MercuryRequests.getAlbum(ApiUtils.extractId(AlbumId.class, request, request.params)));
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
