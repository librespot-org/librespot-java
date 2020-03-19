package xyz.gianlu.librespot.api.handlers;

import com.google.gson.JsonObject;
import io.undertow.server.HttpServerExchange;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.api.SessionWrapper;
import xyz.gianlu.librespot.api.Utils;
import xyz.gianlu.librespot.common.ProtobufToJson;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.dealer.ApiClient;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.*;

import java.io.IOException;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;

/**
 * @author Gianlu
 */
public final class MetadataHandler extends AbsSessionHandler {
    private static final Logger LOGGER = Logger.getLogger(MetadataHandler.class);
    private final boolean needsType;

    public MetadataHandler(@NotNull SessionWrapper wrapper, boolean needsType) {
        super(wrapper);
        this.needsType = needsType;
    }

    @Override
    public void handleRequest(@NotNull HttpServerExchange exchange, @NotNull Session session) throws Exception {
        exchange.startBlocking();
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        Map<String, Deque<String>> params = Utils.readParameters(exchange);
        String uri = Utils.getFirstString(params, "uri");
        if (uri == null) {
            Utils.invalidParameter(exchange, "uri");
            return;
        }

        MetadataType type;
        String typeStr = Utils.getFirstString(params, "type");
        if (typeStr == null) {
            if (needsType) {
                Utils.invalidParameter(exchange, "type");
                return;
            }

            type = MetadataType.guessTypeFromUri(uri);
        } else {
            type = MetadataType.parse(typeStr);
        }

        if (type == null) {
            Utils.invalidParameter(exchange, "type");
            return;
        }

        try {
            JsonObject obj = handle(session, type, uri);
            exchange.getResponseSender().send(obj.toString());
        } catch (ApiClient.StatusCodeException ex) {
            if (ex.code == 404) {
                Utils.invalidParameter(exchange, "uri", "404: Unknown uri");
                return;
            }

            Utils.internalError(exchange, ex);
            LOGGER.error(String.format("Failed handling api request. {type: %s, uri: %s, code: %d}", type, uri, ex.code), ex);
        } catch (IOException | MercuryClient.MercuryException ex) {
            Utils.internalError(exchange, ex);
            LOGGER.error(String.format("Failed handling api request. {type: %s, uri: %s}", type, uri), ex);
        } catch (IllegalArgumentException ex) {
            Utils.invalidParameter(exchange, "uri", "Invalid uri for type: " + type);
        }
    }

    @NotNull
    private JsonObject handle(@NotNull Session session, @NotNull MetadataType type, @NotNull String uri) throws IOException, MercuryClient.MercuryException, IllegalArgumentException {
        switch (type) {
            case ALBUM:
                return ProtobufToJson.convert(session.api().getMetadata4Album(AlbumId.fromUri(uri)));
            case ARTIST:
                return ProtobufToJson.convert(session.api().getMetadata4Artist(ArtistId.fromUri(uri)));
            case SHOW:
                return ProtobufToJson.convert(session.api().getMetadata4Show(ShowId.fromUri(uri)));
            case EPISODE:
                return ProtobufToJson.convert(session.api().getMetadata4Episode(EpisodeId.fromUri(uri)));
            case TRACK:
                return ProtobufToJson.convert(session.api().getMetadata4Track(TrackId.fromUri(uri)));
            case PLAYLIST:
                return handlePlaylist(session, uri);
            default:
                throw new IllegalArgumentException(type.name());
        }
    }

    @NotNull
    private JsonObject handlePlaylist(@NotNull Session session, @NotNull String uri) throws IOException, MercuryClient.MercuryException {
        JsonObject obj = new JsonObject();
        obj.add("tracks", session.mercury().sendSync(MercuryRequests.getPlaylist(PlaylistId.fromUri(uri))).json());

        try {
            obj.add("annotations", session.mercury().sendSync(MercuryRequests.getPlaylistAnnotation(PlaylistId.fromUri(uri))).json());
        } catch (MercuryClient.MercuryException ignored) {
        }

        return obj;
    }

    private enum MetadataType {
        EPISODE("episode"), TRACK("track"), ALBUM("album"),
        ARTIST("artist"), SHOW("show"), PLAYLIST("playlist");

        private final String val;

        MetadataType(String val) {
            this.val = val;
        }

        @Nullable
        private static MetadataType parse(@NotNull String val) {
            for (MetadataType type : values())
                if (Objects.equals(type.val, val))
                    return type;

            return null;
        }

        @Nullable
        public static MetadataType guessTypeFromUri(@NotNull String uri) {
            for (MetadataType type : values())
                if (uri.contains(type.val))
                    return type;

            return null;
        }
    }
}
