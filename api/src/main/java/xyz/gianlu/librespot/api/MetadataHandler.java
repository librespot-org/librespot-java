package xyz.gianlu.librespot.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.server.AbsApiHandler;
import xyz.gianlu.librespot.api.server.ApiServer;
import xyz.gianlu.librespot.common.proto.Playlist4Changes;
import xyz.gianlu.librespot.common.proto.Playlist4Content;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;

import java.io.IOException;
import java.util.List;

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
                return handleRootlists();
            default:
                throw ApiServer.PredefinedJsonRpcException.from(request, ApiServer.PredefinedJsonRpcError.METHOD_NOT_FOUND);
        }
    }

    @NotNull
    private JsonElement handleRootlists() throws HandlingException {
        try {
            Playlist4Changes.SelectedListContent list = client.sendSync(MercuryRequests.getRootPlaylists(session.apWelcome().getCanonicalUsername()));
            List<Playlist4Content.Item> items = list.getContents().getItemsList();

            JsonArray array = new JsonArray(items.size());
            for (Playlist4Content.Item item : items)
                array.add(item.getUri());

            return array;
        } catch (IOException | MercuryClient.MercuryException ex) {
            throw new HandlingException(100, ""); // FIXME: Create error codes table
        }
    }

    @Override
    protected void handleNotification(ApiServer.@NotNull Request request) {

    }
}
