package xyz.gianlu.librespot.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.spotify.connectstate.Player;
import com.spotify.connectstate.Player.ContextPlayerOptions;
import com.spotify.context.ContextOuterClass.Context;
import com.spotify.context.ContextPageOuterClass.ContextPage;
import com.spotify.context.ContextPlayerOptionsOuterClass;
import com.spotify.metadata.Metadata;
import com.spotify.playlist4.Playlist4ApiProto;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.metadata.PlayableId;

import java.lang.reflect.Field;
import java.util.*;

import static com.spotify.context.ContextTrackOuterClass.ContextTrack;
import static com.spotify.context.PlayOriginOuterClass.PlayOrigin;


/**
 * @author Gianlu
 */
public final class ProtoUtils {
    private static final Logger LOGGER = LogManager.getLogger(ProtoUtils.class);

    private ProtoUtils() {
    }

    public static void overrideDefaultValue(@NotNull Descriptors.FieldDescriptor desc, Object newDefault) throws IllegalAccessException, NoSuchFieldException {
        Field f = Descriptors.FieldDescriptor.class.getDeclaredField("defaultValue");
        f.setAccessible(true);
        f.set(desc, newDefault);
    }

    @NotNull
    public static List<Playlist4ApiProto.Op.Kind> opsKindList(@NotNull List<Playlist4ApiProto.Op> ops) {
        List<Playlist4ApiProto.Op.Kind> kinds = new ArrayList<>(ops.size());
        for (Playlist4ApiProto.Op op : ops) kinds.add(op.getKind());
        return kinds;
    }

    @NotNull
    private static JsonObject mapToJson(@NotNull Map<String, String> map) {
        JsonObject obj = new JsonObject();
        for (String key : map.keySet()) obj.addProperty(key, map.get(key));
        return obj;
    }

    @NotNull
    private static JsonObject trackToJson(@NotNull Player.ProvidedTrack track) {
        JsonObject obj = new JsonObject();
        obj.addProperty("uri", track.getUri());
        obj.addProperty("uid", track.getUid());
        obj.add("metadata", mapToJson(track.getMetadataMap()));
        return obj;
    }

    @NotNull
    private static JsonObject trackToJson(@NotNull ContextTrack track, String uriPrefix) {
        JsonObject obj = new JsonObject();
        if (track.hasUid()) obj.addProperty("uid", track.getUid());
        obj.add("metadata", mapToJson(track.getMetadataMap()));
        if (track.hasUri() && !track.getUri().isEmpty())
            obj.addProperty("uri", track.getUri());
        else if (track.hasGid() && uriPrefix != null)
            obj.addProperty("uri", uriPrefix + new String(PlayableId.BASE62.encode(track.getGid().toByteArray(), 22)));

        return obj;
    }

    @NotNull
    public static JsonObject craftContextStateCombo(@NotNull Player.PlayerStateOrBuilder ps, @NotNull List<ContextTrack> tracks) {
        JsonObject context = new JsonObject();
        context.addProperty("uri", ps.getContextUri());
        context.addProperty("url", ps.getContextUrl());
        context.add("metadata", mapToJson(ps.getContextMetadataMap()));

        JsonArray pages = new JsonArray(1);
        context.add("pages", pages);

        JsonObject page = new JsonObject();
        page.addProperty("page_url", "");
        page.addProperty("next_page_url", "");
        JsonArray tracksJson = new JsonArray(tracks.size());
        for (ContextTrack t : tracks) tracksJson.add(trackToJson(t, PlayableId.inferUriPrefix(ps.getContextUri())));
        page.add("tracks", tracksJson);
        page.add("metadata", mapToJson(ps.getPageMetadataMap()));
        pages.add(page);


        JsonObject state = new JsonObject();

        JsonObject options = new JsonObject();
        options.addProperty("shuffling_context", ps.getOptions().getShufflingContext());
        options.addProperty("repeating_context", ps.getOptions().getRepeatingContext());
        options.addProperty("repeating_track", ps.getOptions().getRepeatingTrack());
        state.add("options", options);
        state.add("skip_to", new JsonObject());
        state.add("track", trackToJson(ps.getTrack()));

        JsonObject result = new JsonObject();
        result.add("context", context);
        result.add("state", state);
        return result;
    }

    @NotNull
    public static ContextTrack jsonToContextTrack(@NotNull JsonObject obj) {
        ContextTrack.Builder builder = ContextTrack.newBuilder();
        Optional.ofNullable(Utils.optString(obj, "uri", null)).ifPresent(uri -> {
            builder.setUri(uri);
            builder.setGid(ByteString.copyFrom(PlayableId.fromUri(uri).getGid()));
        });
        Optional.ofNullable(Utils.optString(obj, "uid", null)).ifPresent(builder::setUid);

        JsonObject metadata = obj.getAsJsonObject("metadata");
        if (metadata != null) {
            for (String key : metadata.keySet())
                builder.putMetadata(key, metadata.get(key).getAsString());
        }

        return builder.build();
    }

    @NotNull
    public static List<ContextTrack> jsonToContextTracks(@NotNull JsonArray array) {
        List<ContextTrack> list = new ArrayList<>(array.size());
        for (JsonElement elm : array) list.add(jsonToContextTrack(elm.getAsJsonObject()));
        return list;
    }

    @NotNull
    public static ContextPage jsonToContextPage(@NotNull JsonObject obj) {
        ContextPage.Builder builder = ContextPage.newBuilder();
        Optional.ofNullable(Utils.optString(obj, "next_page_url", null)).ifPresent(builder::setNextPageUrl);
        Optional.ofNullable(Utils.optString(obj, "page_url", null)).ifPresent(builder::setPageUrl);

        JsonArray tracks = obj.getAsJsonArray("tracks");
        if (tracks != null) builder.addAllTracks(jsonToContextTracks(tracks));

        return builder.build();
    }

    @NotNull
    public static List<ContextPage> jsonToContextPages(@NotNull JsonArray array) {
        List<ContextPage> list = new ArrayList<>(array.size());
        for (JsonElement elm : array) list.add(jsonToContextPage(elm.getAsJsonObject()));
        return list;
    }

    @NotNull
    public static Player.PlayOrigin jsonToPlayOrigin(@NotNull JsonObject obj) {
        Player.PlayOrigin.Builder builder = Player.PlayOrigin.newBuilder();

        Optional.ofNullable(obj.get("feature_identifier")).ifPresent(elm -> builder.setFeatureIdentifier(elm.getAsString()));
        Optional.ofNullable(obj.get("feature_version")).ifPresent(elm -> builder.setFeatureVersion(elm.getAsString()));
        Optional.ofNullable(obj.get("view_uri")).ifPresent(elm -> builder.setViewUri(elm.getAsString()));
        Optional.ofNullable(obj.get("external_referrer")).ifPresent(elm -> builder.setExternalReferrer(elm.getAsString()));
        Optional.ofNullable(obj.get("referrer_identifier")).ifPresent(elm -> builder.setReferrerIdentifier(elm.getAsString()));
        Optional.ofNullable(obj.get("device_identifier")).ifPresent(elm -> builder.setDeviceIdentifier(elm.getAsString()));

        return builder.build();
    }

    @Nullable
    @Contract("null -> null")
    public static Player.PlayOrigin convertPlayOrigin(@Nullable PlayOrigin po) {
        if (po == null) return null;

        Player.PlayOrigin.Builder builder = Player.PlayOrigin.newBuilder();

        if (po.hasFeatureIdentifier()) builder.setFeatureIdentifier(po.getFeatureIdentifier());
        if (po.hasFeatureVersion()) builder.setFeatureVersion(po.getFeatureVersion());
        if (po.hasViewUri()) builder.setViewUri(po.getViewUri());
        if (po.hasExternalReferrer()) builder.setExternalReferrer(po.getExternalReferrer());
        if (po.hasReferrerIdentifier()) builder.setReferrerIdentifier(po.getReferrerIdentifier());
        if (po.hasDeviceIdentifier()) builder.setDeviceIdentifier(po.getDeviceIdentifier());

        if (po.getFeatureClassesCount() > 0)
            for (String feature : po.getFeatureClassesList())
                builder.addFeatureClasses(feature);

        return builder.build();
    }


    @NotNull
    public static ContextPlayerOptions jsonToPlayerOptions(@Nullable JsonObject obj, @Nullable ContextPlayerOptions old) {
        ContextPlayerOptions.Builder builder = old == null ? ContextPlayerOptions.newBuilder() : old.toBuilder();

        if (obj != null) {
            Optional.ofNullable(obj.get("repeating_context")).ifPresent(elm -> builder.setRepeatingContext(elm.getAsBoolean()));
            Optional.ofNullable(obj.get("repeating_track")).ifPresent(elm -> builder.setRepeatingTrack(elm.getAsBoolean()));
            Optional.ofNullable(obj.get("shuffling_context")).ifPresent(elm -> builder.setShufflingContext(elm.getAsBoolean()));
        }

        return builder.build();
    }

    @NotNull
    public static Context jsonToContext(@NotNull JsonObject obj) {
        Context.Builder builder = Context.newBuilder();

        Optional.ofNullable(obj.get("uri")).ifPresent(elm -> builder.setUri(elm.getAsString()));
        Optional.ofNullable(obj.get("url")).ifPresent(elm -> builder.setUrl(elm.getAsString()));

        JsonObject metadata = obj.getAsJsonObject("metadata");
        if (metadata != null) {
            for (String key : metadata.keySet())
                builder.putMetadata(key, metadata.get(key).getAsString());
        }

        if (obj.has("pages")) {
            for (JsonElement elm : obj.getAsJsonArray("pages"))
                builder.addPages(jsonToContextPage(elm.getAsJsonObject()));
        }

        return builder.build();
    }

    @Contract("null -> null")
    @Nullable
    public static ContextPlayerOptions convertPlayerOptions(@Nullable ContextPlayerOptionsOuterClass.ContextPlayerOptions options) {
        if (options == null) return null;

        ContextPlayerOptions.Builder builder = ContextPlayerOptions.newBuilder();
        if (options.hasRepeatingContext()) builder.setRepeatingContext(options.getRepeatingContext());
        if (options.hasRepeatingTrack()) builder.setRepeatingTrack(options.getRepeatingTrack());
        if (options.hasShufflingContext()) builder.setShufflingContext(options.getShufflingContext());

        return builder.build();
    }

    public static void copyOverMetadata(@NotNull Context from, @NotNull Player.PlayerState.Builder to) {
        to.putAllContextMetadata(from.getMetadataMap());
    }

    public static void copyOverMetadata(@NotNull JsonObject obj, @NotNull Player.PlayerState.Builder to) {
        for (String key : obj.keySet()) to.putContextMetadata(key, obj.get(key).getAsString());
    }

    public static int indexOfTrackByUid(@NotNull List<ContextTrack> tracks, @NotNull String uid) {
        for (int i = 0; i < tracks.size(); i++) {
            if (Objects.equals(tracks.get(i).getUid(), uid))
                return i;
        }

        return -1;
    }

    public static int indexOfTrackByUri(@NotNull List<ContextTrack> tracks, @NotNull String uri) {
        for (int i = 0; i < tracks.size(); i++) {
            if (Objects.equals(tracks.get(i).getUri(), uri))
                return i;
        }

        return -1;
    }

    public static boolean isQueued(@NotNull ContextTrack track) {
        try {
            return Boolean.parseBoolean(track.getMetadataOrThrow("is_queued"));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static void enrichTrack(@NotNull ContextTrack.Builder subject, @NotNull ContextTrack track) {
        if (subject.hasUri() && track.hasUri() && !subject.getUri().isEmpty() && !track.getUri().isEmpty() && !Objects.equals(subject.getUri(), track.getUri()))
            throw new IllegalArgumentException(subject.getUri() + " is not " + track.getUri());

        if (subject.hasGid() && track.hasGid() && !Objects.equals(subject.getGid(), track.getGid()))
            throw new IllegalArgumentException(Utils.bytesToHex(subject.getGid()) + " is not " + Utils.bytesToHex(track.getGid()));

        subject.putAllMetadata(track.getMetadataMap());
    }

    public static void enrichTrack(@NotNull Player.ProvidedTrack.Builder subject, @NotNull ContextTrack track) {
        if (track.hasUri() && !track.getUri().isEmpty() && !Objects.equals(subject.getUri(), track.getUri()))
            throw new IllegalArgumentException(subject.getUri() + " is not " + track.getUri());

        subject.putAllMetadata(track.getMetadataMap());
    }

    @Nullable
    @Contract("null, _ -> null")
    public static Player.ProvidedTrack toProvidedTrack(@Nullable ContextTrack track, String contextUri) {
        if (track == null) return null;

        Player.ProvidedTrack.Builder builder = Player.ProvidedTrack.newBuilder();
        builder.setProvider("context");
        if (track.hasUid()) builder.setUid(track.getUid());
        if (track.hasUri() && !track.getUri().isEmpty()) {
            builder.setUri(track.getUri());
        } else if (track.hasGid()) {
            String uriPrefix = PlayableId.inferUriPrefix(contextUri);
            builder.setUri(uriPrefix + new String(PlayableId.BASE62.encode(track.getGid().toByteArray(), 22)));
        }

        try {
            builder.setAlbumUri(track.getMetadataOrThrow("album_uri"));
        } catch (IllegalArgumentException ignored) {
        }

        try {
            builder.setArtistUri(track.getMetadataOrThrow("artist_uri"));
        } catch (IllegalArgumentException ignored) {
        }

        builder.putAllMetadata(track.getMetadataMap());

        return builder.build();
    }

    public static void putFilesAsMetadata(@NotNull Player.ProvidedTrack.Builder builder, @NotNull List<Metadata.AudioFile> files) {
        if (files.size() == 0) return;

        JsonArray formats = new JsonArray(files.size());
        for (Metadata.AudioFile file : files) {
            if (file.hasFormat()) formats.add(file.getFormat().name());
        }

        if (formats.size() > 0) builder.putMetadata("available_file_formats", formats.toString());
    }

    public static int getTrackCount(@NotNull Metadata.Album album) {
        int total = 0;
        for (Metadata.Disc disc : album.getDiscList()) total += disc.getTrackCount();
        return total;
    }

    @NotNull
    public static List<ContextTrack> join(@NotNull List<ContextPage> pages) {
        if (pages.isEmpty()) return Collections.emptyList();

        List<ContextTrack> tracks = new ArrayList<>();
        for (ContextPage page : pages)
            tracks.addAll(page.getTracksList());

        return tracks;
    }

    public static void copyOverMetadata(@NotNull ContextTrack from, @NotNull ContextTrack.Builder to) {
        to.putAllMetadata(from.getMetadataMap());
    }

    public static void copyOverMetadata(@NotNull ContextTrack from, @NotNull Player.ProvidedTrack.Builder to) {
        to.putAllMetadata(from.getMetadataMap());
    }

    public static boolean trackEquals(ContextTrack first, ContextTrack second) {
        if (first == null || second == null) return false;
        if (first == second) return true;

        if (first.hasUri() && !first.getUri().isEmpty() && second.hasUri() && !second.getUri().isEmpty())
            return first.getUri().equals(second.getUri());

        if (first.hasGid() && second.hasGid())
            return first.getGid().equals(second.getGid());

        if (first.hasUid() && !first.getUid().isEmpty() && second.hasUid() && !second.getUid().isEmpty())
            return first.getUid().equals(second.getUid());

        return false;
    }

    public static int indexOfTrack(List<ContextTrack> list, ContextTrack track) {
        for (int i = 0; i < list.size(); i++)
            if (trackEquals(list.get(i), track))
                return i;

        return -1;
    }

    public static boolean isTrack(Player.ProvidedTrack track, Metadata.Track metadata) {
        return track.getUri().equals(PlayableId.from(metadata).toSpotifyUri());
    }

    public static boolean isEpisode(Player.ProvidedTrack track, Metadata.Episode metadata) {
        return track.getUri().equals(PlayableId.from(metadata).toSpotifyUri());
    }

    @NotNull
    public static String toString(ContextTrack track) {
        return String.format("ContextTrack{uri: %s, uid: %s, gid: %s}", track.getUri(), track.getUid(), Utils.bytesToHex(track.getGid()));
    }

    @NotNull
    public static String toString(Player.ProvidedTrack track) {
        return String.format("ProvidedTrack{uri: %s, uid: %s}", track.getUri(), track.getUid());
    }

    @NotNull
    public static String toString(Metadata.Track metadata) {
        return String.format("Metadata.Track{%s,%s}", Utils.bytesToHex(metadata.getGid()), PlayableId.from(metadata).toSpotifyUri());
    }

    @NotNull
    public static String toString(Metadata.Episode metadata) {
        return String.format("Metadata.Episode{%s,%s}", Utils.bytesToHex(metadata.getGid()), PlayableId.from(metadata).toSpotifyUri());
    }
}
