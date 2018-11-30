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
                return handle(MercuryRequests.getPlaylist(PlaylistId.fromUri(request.params.getAsString())));
            case "track":
                return handle(MercuryRequests.getTrack(TrackId.fromUri(request.params.getAsString())));
            default:
                throw ApiServer.PredefinedJsonRpcException.from(request, ApiServer.PredefinedJsonRpcError.METHOD_NOT_FOUND);
        }
    }

    @NotNull
    private <P extends AbstractMessageLite> JsonElement handle(@NotNull ProtoJsonMercuryRequest<P> req) throws HandlingException {
        try {
            return client.sendSync(req).json();
        } catch (IOException | MercuryClient.MercuryException ex) {
            throw new HandlingException(ex, 100); // FIXME: Create error codes table
        }
    }

    @Override
    protected void handleNotification(ApiServer.@NotNull Request request) {

    }
}
