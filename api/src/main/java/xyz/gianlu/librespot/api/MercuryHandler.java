package xyz.gianlu.librespot.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.server.AbsApiHandler;
import xyz.gianlu.librespot.api.server.ApiServer;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;

import java.io.IOException;
import java.util.Base64;

/**
 * @author Gianlu
 */
public class MercuryHandler extends AbsApiHandler {
    private final Session session;

    public MercuryHandler(@NotNull Session session) {
        super("mercury");
        this.session = session;
    }

    @Override
    protected @NotNull JsonElement handleRequest(ApiServer.@NotNull Request request) throws HandlingException, ApiServer.PredefinedJsonRpcException {
        if (request.getSuffix().equals("request")) {
            JsonObject params = request.params.getAsJsonObject();

            RawMercuryRequest.Builder builder = RawMercuryRequest.newBuilder()
                    .setMethod(params.get("method").getAsString())
                    .setUri(params.get("uri").getAsString());

            if (params.has("headers")) {
                JsonArray headers = params.getAsJsonArray("headers");
                for (JsonElement element : headers) {
                    JsonObject header = element.getAsJsonObject();
                    builder.addUserField(header.get("key").getAsString(), header.get("value").getAsString());
                }
            }

            try {
                MercuryClient.Response response = session.mercury().sendSync(builder.build());

                JsonArray payloads = new JsonArray(response.payload.size());
                for (byte[] bytes : response.payload)
                    payloads.add(Base64.getEncoder().encodeToString(bytes));

                JsonObject obj = new JsonObject();
                obj.addProperty("code", response.statusCode);
                obj.addProperty("uri", response.uri);
                obj.add("payloads", payloads);
                return obj;
            } catch (IOException ex) {
                throw new HandlingException(ex, ErrorCode.IO_EXCEPTION);
            }
        } else {
            throw ApiServer.PredefinedJsonRpcException.from(request, ApiServer.PredefinedJsonRpcError.METHOD_NOT_FOUND);
        }
    }

    @Override
    protected void handleNotification(ApiServer.@NotNull Request request) {

    }
}
