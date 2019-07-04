package xyz.gianlu.librespot.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spotify.connectstate.model.Player;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spotify.player.proto.ContextOuterClass;
import spotify.player.proto.ContextPageOuterClass.ContextPage;
import spotify.player.proto.ContextTrackOuterClass.ContextTrack;
import spotify.player.proto.PlayOriginOuterClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    public static void moveOverMetadata(@NotNull ContextOuterClass.Context from, @NotNull Player.PlayerState.Builder to, @NotNull String... keys) {
        for (String key : keys)
            if (from.containsMetadata(key))
                to.putContextMetadata(key, from.getMetadataOrThrow(key));
    }
}
