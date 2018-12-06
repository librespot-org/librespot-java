package xyz.gianlu.librespot.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.api.server.ApiServer;
import xyz.gianlu.librespot.mercury.model.SpotifyId;

/**
 * @author Gianlu
 */
public final class ApiUtils {

    private ApiUtils() {
    }

    @NotNull
    public static <I extends SpotifyId> I extractId(@NotNull Class<I> clazz, @NotNull ApiServer.Request request, @Nullable JsonElement params) throws ApiServer.PredefinedJsonRpcException {
        if (params == null || !params.isJsonObject())
            throw ApiServer.PredefinedJsonRpcException.from(request, ApiServer.PredefinedJsonRpcError.INVALID_PARAMS);

        try {
            JsonObject obj = params.getAsJsonObject();
            if (obj.has("gid")) {
                return SpotifyId.fromHex(clazz, obj.get("gid").getAsString());
            } else if (obj.has("uri")) {
                return SpotifyId.fromUri(clazz, obj.get("uri").getAsString());
            } else if (obj.has("base62")) {
                return SpotifyId.fromBase62(clazz, obj.get("gid").getAsString());
            } else {
                throw ApiServer.PredefinedJsonRpcException.from(request, ApiServer.PredefinedJsonRpcError.INVALID_REQUEST);
            }
        } catch (SpotifyId.SpotifyIdParsingException ex) {
            throw ApiServer.PredefinedJsonRpcException.from(request, ApiServer.PredefinedJsonRpcError.INVALID_REQUEST);
        }
    }
}
