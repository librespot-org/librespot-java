package xyz.gianlu.librespot.mercury;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.ByteString;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.proto.Mercury;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.common.proto.Playlist4Changes;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.mercury.model.*;
import xyz.gianlu.librespot.player.remote.Remote3Page;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Gianlu
 */
public final class MercuryRequests {
    private static final String KEYMASTER_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd";

    private MercuryRequests() {
    }

    @NotNull
    public static ProtobufMercuryRequest<Playlist4Changes.SelectedListContent> getRootPlaylists(@NotNull String username) {
        return new ProtobufMercuryRequest<>(RawMercuryRequest.get(String.format("hm://playlist/user/%s/rootlist", username)),
                Playlist4Changes.SelectedListContent.parser());
    }

    @NotNull
    public static ProtobufMercuryRequest<Playlist4Changes.SelectedListContent> getPlaylist(@NotNull PlaylistId id) {
        return new ProtobufMercuryRequest<>(RawMercuryRequest.get(id.toMercuryUri()),
                Playlist4Changes.SelectedListContent.parser());
    }

    @NotNull
    public static ProtobufMercuryRequest<Metadata.Track> getTrack(@NotNull TrackId id) {
        return new ProtobufMercuryRequest<>(RawMercuryRequest.get(id.toMercuryUri()), Metadata.Track.parser());
    }

    @NotNull
    public static ProtobufMercuryRequest<Metadata.Artist> getArtist(@NotNull ArtistId id) {
        return new ProtobufMercuryRequest<>(RawMercuryRequest.get(id.toMercuryUri()), Metadata.Artist.parser());
    }

    @NotNull
    public static ProtobufMercuryRequest<Metadata.Album> getAlbum(@NotNull AlbumId id) {
        return new ProtobufMercuryRequest<>(RawMercuryRequest.get(id.toMercuryUri()), Metadata.Album.parser());
    }

    @NotNull
    public static ProtobufMercuryRequest<Metadata.Episode> getEpisode(@NotNull EpisodeId id) {
        return new ProtobufMercuryRequest<>(RawMercuryRequest.get(id.toMercuryUri()), Metadata.Episode.parser());
    }

    @NotNull
    public static ProtobufMercuryRequest<Metadata.Show> getShow(@NotNull ShowId id) {
        return new ProtobufMercuryRequest<>(RawMercuryRequest.get(id.toMercuryUri()), Metadata.Show.parser());
    }

    @NotNull
    public static ProtobufMercuryRequest<Mercury.MercuryMultiGetReply> multiGet(@NotNull String uri, Mercury.MercuryRequest... subs) {
        RawMercuryRequest.Builder request = RawMercuryRequest.newBuilder()
                .setContentType("vnd.spotify/mercury-mget-request")
                .setMethod("GET")
                .setUri(uri);

        Mercury.MercuryMultiGetRequest.Builder multi = Mercury.MercuryMultiGetRequest.newBuilder();
        for (Mercury.MercuryRequest sub : subs)
            multi.addRequest(sub);

        request.addProtobufPayload(multi.build());
        return new ProtobufMercuryRequest<>(request.build(), Mercury.MercuryMultiGetReply.parser());
    }

    @NotNull
    public static JsonMercuryRequest<StationsWrapper> getStationFor(@NotNull String context) {
        return new JsonMercuryRequest<>(RawMercuryRequest.get("hm://radio-apollo/v3/stations/" + context), StationsWrapper.class);
    }

    @NotNull
    public static RawMercuryRequest autoplayQuery(@NotNull String context) {
        return RawMercuryRequest.get("hm://autoplay-enabled/query?uri=" + context);
    }

    @NotNull
    public static JsonMercuryRequest<ResolvedContextWrapper> resolveContext(@NotNull String uri) {
        return new JsonMercuryRequest<>(RawMercuryRequest.get(String.format("hm://context-resolve/v1/%s", uri)), ResolvedContextWrapper.class);
    }

    @NotNull
    public static JsonMercuryRequest<KeymasterToken> requestToken(@NotNull String deviceId, @NotNull String scope) {
        return new JsonMercuryRequest<>(RawMercuryRequest.get(String.format("hm://keymaster/token/authenticated?scope=%s&client_id=%s&device_id=%s", scope, KEYMASTER_CLIENT_ID, deviceId)), KeymasterToken.class);
    }

    @NotNull
    private static String getAsString(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement elm = obj.get(key);
        if (elm == null) throw new NullPointerException("Unexpected null value for " + key);
        else return elm.getAsString();
    }

    @Contract("_, _, !null -> !null")
    private static String getAsString(@NotNull JsonObject obj, @NotNull String key, @Nullable String fallback) {
        JsonElement elm = obj.get(key);
        if (elm == null) return fallback;
        else return elm.getAsString();
    }

    public static final class StationsWrapper extends JsonWrapper {

        public StationsWrapper(@NotNull JsonObject obj) {
            super(obj);
        }

        @NotNull
        public String uri() {
            return getAsString(obj, "uri");
        }

        @NotNull
        public List<Spirc.TrackRef> tracks() {
            JsonArray array = obj.getAsJsonArray("tracks");
            List<Spirc.TrackRef> list = new ArrayList<>(array.size());
            for (JsonElement elm : array) {
                String uri = getAsString(elm.getAsJsonObject(), "uri");
                list.add(Spirc.TrackRef.newBuilder()
                        .setUri(uri)
                        .setGid(ByteString.copyFrom(TrackId.fromUri(uri).getGid()))
                        .build());
            }

            return list;
        }
    }

    public static final class ResolvedContextWrapper extends JsonWrapper {

        public ResolvedContextWrapper(@NotNull JsonObject obj) {
            super(obj);
        }

        @NotNull
        public List<Remote3Page> pages() {
            List<Remote3Page> list = Remote3Page.opt(obj.getAsJsonArray("pages"));
            if (list == null) throw new IllegalArgumentException("Invalid context!");
            return list;
        }

        @NotNull
        public JsonObject metadata() {
            return obj.getAsJsonObject("metadata");
        }

        @NotNull
        public String uri() {
            return getAsString(obj, "uri");
        }

        @NotNull
        public String url() {
            return getAsString(obj, "url");
        }
    }

    public static final class KeymasterToken extends JsonWrapper {

        public KeymasterToken(@NotNull JsonObject obj) {
            super(obj);
        }
    }
}
