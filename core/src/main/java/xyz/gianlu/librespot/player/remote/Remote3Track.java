package xyz.gianlu.librespot.player.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.ByteString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.mercury.model.TrackId;

/**
 * @author Gianlu
 */
public class Remote3Track {
    public final String uri;
    public final String uid;
    public final JsonObject metadata;
    private TrackId id;

    Remote3Track(@NotNull JsonObject obj) {
        uri = Utils.optString(obj, "uri", null);
        uid = Utils.optString(obj, "uid", null);
        metadata = obj.getAsJsonObject("metadata");
    }

    @Nullable
    public static Remote3Track opt(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement elm = obj.get(key);
        if (elm == null || !elm.isJsonObject()) return null;
        return new Remote3Track(elm.getAsJsonObject());
    }

    @NotNull
    public Spirc.TrackRef toTrackRef() {
        return Spirc.TrackRef.newBuilder().setGid(ByteString.copyFrom(id().getGid())).setUri(uri).build();
    }

    @NotNull
    public TrackId id() {
        if (id == null) id = TrackId.fromUri(uri);
        return id;
    }
}
