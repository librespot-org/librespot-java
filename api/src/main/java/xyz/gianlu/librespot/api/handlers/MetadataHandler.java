package xyz.gianlu.librespot.api.handlers;

import com.google.gson.JsonObject;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.api.Utils;
import xyz.gianlu.librespot.common.ProtobufToJson;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.dealer.ApiClient;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.model.*;

import java.io.IOException;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;

/**
 * @author Gianlu
 */
public final class MetadataHandler implements HttpHandler {
    private static final Logger LOGGER = Logger.getLogger(MetadataHandler.class);
    private final Session session;

    public MetadataHandler(@NotNull Session session) {
        this.session = session;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        exchange.startBlocking();
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        Map<String, Deque<String>> params = Utils.readParameters(exchange);
        String typeStr = Utils.getFirstString(params, "type");
        if (typeStr == null) {
            Utils.invalidParameter(exchange, "type");
            return;
        }

        MetadataType type = MetadataType.parse(typeStr);
        if (type == null) {
            Utils.invalidParameter(exchange, "type");
            return;
        }

        String uri = Utils.getFirstString(params, "uri");
        if (uri == null) {
            Utils.invalidParameter(exchange, "uri");
            return;
        }

        try {
            JsonObject obj = handle(type, uri);
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
    private JsonObject handle(@NotNull MetadataType type, @NotNull String uri) throws IOException, MercuryClient.MercuryException, IllegalArgumentException {
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
            default:
                throw new IllegalArgumentException(type.name());
        }
    }

    private enum MetadataType {
        EPISODE("episode"), TRACK("track"), ALBUM("album"),
        ARTIST("artist"), SHOW("show");

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
    }
}
