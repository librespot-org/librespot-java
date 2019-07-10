package xyz.gianlu.librespot.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spotify.connectstate.model.Player;
import com.spotify.connectstate.model.Player.ContextPlayerOptions;
import com.spotify.metadata.proto.Metadata;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spotify.player.proto.ContextPageOuterClass.ContextPage;
import spotify.player.proto.ContextPlayerOptionsOuterClass;
import spotify.player.proto.ContextTrackOuterClass.ContextTrack;
import spotify.player.proto.PlayOriginOuterClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static spotify.player.proto.ContextOuterClass.Context;

/**
 * @author Gianlu
 */
public final class ProtoUtils {
    private ProtoUtils() {
    }

    @NotNull
    public static ContextTrack jsonToContextTrack(@NotNull JsonObject obj) {
        ContextTrack.Builder builder = ContextTrack.newBuilder();
        Optional.ofNullable(Utils.optString(obj, "uri", null)).ifPresent(builder::setUri);
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
    public static Player.PlayOrigin convertPlayOrigin(@Nullable PlayOriginOuterClass.PlayOrigin po) {
        if (po == null) return null;

        Player.PlayOrigin.Builder builder = Player.PlayOrigin.newBuilder();

        Optional.ofNullable(po.getFeatureIdentifier()).ifPresent(builder::setFeatureIdentifier);
        Optional.ofNullable(po.getFeatureVersion()).ifPresent(builder::setFeatureVersion);
        Optional.ofNullable(po.getViewUri()).ifPresent(builder::setViewUri);
        Optional.ofNullable(po.getExternalReferrer()).ifPresent(builder::setExternalReferrer);
        Optional.ofNullable(po.getReferrerIdentifier()).ifPresent(builder::setReferrerIdentifier);
        Optional.ofNullable(po.getDeviceIdentifier()).ifPresent(builder::setDeviceIdentifier);

        if (po.getFeatureClassesCount() > 0)
            for (String feature : po.getFeatureClassesList())
                builder.addFeatureClasses(feature);

        return builder.build();
    }


    @NotNull
    public static ContextPlayerOptions jsonToPlayerOptions(@NotNull JsonObject obj) {
        ContextPlayerOptions.Builder builder = ContextPlayerOptions.newBuilder();

        Optional.ofNullable(obj.get("repeating_context")).ifPresent(elm -> builder.setRepeatingContext(elm.getAsBoolean()));
        Optional.ofNullable(obj.get("repeating_track")).ifPresent(elm -> builder.setRepeatingTrack(elm.getAsBoolean()));
        Optional.ofNullable(obj.get("shuffling_context")).ifPresent(elm -> builder.setShufflingContext(elm.getAsBoolean()));

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

        for (JsonElement elm : obj.getAsJsonArray("pages"))
            builder.addPages(jsonToContextPage(elm.getAsJsonObject()));

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

    public static void moveOverMetadata(@NotNull Context from, @NotNull Player.PlayerState.Builder to, @NotNull String... keys) {
        for (String key : keys)
            if (from.containsMetadata(key))
                to.putContextMetadata(key, from.getMetadataOrThrow(key));
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

    public static void enrichTrack(@NotNull ContextTrack.Builder subject, @NotNull ContextTrack track) {
        if (subject.hasUri() && track.hasUri() && !Objects.equals(subject.getUri(), track.getUri()))
            throw new IllegalArgumentException();

        if (subject.hasGid() && track.hasGid() && !Objects.equals(subject.getGid(), track.getGid()))
            throw new IllegalArgumentException();

        subject.putAllMetadata(track.getMetadataMap());
    }

    @Nullable
    @Contract("null -> null")
    public static Player.ProvidedTrack convertToProvidedTrack(@Nullable ContextTrack track) {
        if (track == null) return null;

        Player.ProvidedTrack.Builder builder = Player.ProvidedTrack.newBuilder();
        builder.setProvider("context");
        Optional.ofNullable(track.getUri()).ifPresent(builder::setUri);
        Optional.ofNullable(track.getUid()).ifPresent(builder::setUid);
        Optional.ofNullable(track.getMetadataOrDefault("album_uri", null)).ifPresent(builder::setAlbumUri);
        Optional.ofNullable(track.getMetadataOrDefault("artist_uri", null)).ifPresent(builder::setArtistUri);

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
}
