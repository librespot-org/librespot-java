package xyz.gianlu.librespot.api.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * @author Gianlu
 */
public class ApiServer implements Receiver {
    private static final JsonParser PARSER = new JsonParser();
    private static final Logger LOGGER = Logger.getLogger(ApiServer.class);
    private final WebsocketServer server;
    private final Map<String, AbsApiHandler> handlers = new HashMap<>();

    public ApiServer(int port) throws IOException {
        server = new WebsocketServer(port, this);

        LOGGER.info(String.format("Server started on port %d!", port));
    }

    private static boolean validateVersion(@NotNull JsonObject obj) {
        JsonElement elm = obj.get("jsonrpc");
        if (elm == null) return false;
        return Objects.equals(elm.getAsString(), "2.0");
    }

    @NotNull
    private static Request parseRequest(@NotNull Sender sender, @NotNull JsonObject obj) throws GeneralJsonException {
        if (!validateVersion(obj))
            throw new JsonRpcSpecificationException("Wrong `jsonrpc` member.");

        JsonElement id = obj.get("id");
        JsonElement params = obj.get("params");

        JsonElement method = obj.get("method");
        if (method == null) throw JsonRpcSpecificationException.missingMember("method");

        return new Request(sender, id, method.getAsString(), params);
    }

    private static void sendError(@NotNull Sender sender, @Nullable JsonElement id, @NotNull JsonRpcError error) {
        JsonObject errorObj = new JsonObject();
        errorObj.addProperty("code", error.code());
        errorObj.addProperty("message", error.msg());

        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        resp.add("error", errorObj);
        sender.sendText(resp.toString());
    }

    public void registerHandler(@NotNull AbsApiHandler handler) {
        handlers.put(handler.prefix, handler);
    }

    private void handleRequest(@NotNull Request request) throws PredefinedJsonRpcException {
        int index = request.method.indexOf('.');
        if (index == -1)
            throw new PredefinedJsonRpcException(request.id, "Missing dot in method: " + request.method, PredefinedJsonRpcError.INVALID_REQUEST);

        if (index == request.method.length() - 1)
            throw new PredefinedJsonRpcException(request.id, "Missing suffix: " + request.method, PredefinedJsonRpcError.METHOD_NOT_FOUND);

        String prefix = request.method.substring(0, index);
        AbsApiHandler handler = handlers.get(prefix);
        if (handler == null)
            throw new PredefinedJsonRpcException(request.id, "Unknown prefix: " + prefix, PredefinedJsonRpcError.METHOD_NOT_FOUND);

        handler.handle(request);
    }

    @Override
    public void onReceivedText(@NotNull Sender sender, @NotNull String payload) {
        try {
            Request req = parseRequest(sender, PARSER.parse(payload).getAsJsonObject());
            handleRequest(req);
        } catch (JsonSyntaxException ex) {
            sendError(sender, null, PredefinedJsonRpcError.PARSE_ERROR);
        } catch (PredefinedJsonRpcException ex) {
            sendError(sender, ex.id, ex.error);
        } catch (GeneralJsonException ex) {
            sendError(sender, null, PredefinedJsonRpcError.INVALID_REQUEST);
        }
    }

    @Override
    public void onReceivedBytes(@NotNull Sender sender, @NotNull byte[] payload) {
    }

    public enum PredefinedJsonRpcError implements JsonRpcError {
        PARSE_ERROR(-32700, "Parse error"),
        INVALID_REQUEST(-32600, "Invalid Request"),
        METHOD_NOT_FOUND(-32601, "Method not found"),
        INVALID_PARAMS(-32602, "Invalid params"),
        INTERNAL_ERROR(-32603, "Internal error");

        private final int code;
        private final String msg;

        PredefinedJsonRpcError(int code, @NotNull String msg) {
            this.code = code;
            this.msg = msg;
        }

        @Override
        public int code() {
            return code;
        }

        @Override
        public @NotNull String msg() {
            return msg;
        }
    }

    public interface JsonRpcError {
        int code();

        @NotNull
        String msg();
    }

    public static class Request {
        public final String method;
        public final JsonElement params;
        private final Sender sender;
        private final JsonElement id;

        Request(@NotNull Sender sender, @Nullable JsonElement id, @NotNull String method, @Nullable JsonElement params) {
            this.sender = sender;
            this.method = method;
            this.params = params;
            this.id = id;
        }

        @NotNull
        public String getSuffix() {
            return method.substring(method.indexOf('.') + 1);
        }

        private void answer(boolean error, @NotNull JsonElement obj) {
            if (id == null) throw new IllegalStateException("Cannot send response to a notification!");

            JsonObject resp = new JsonObject();
            resp.addProperty("jsonrpc", "2.0");
            resp.add("id", id);
            if (error) resp.add("error", obj);
            else resp.add("result", obj);
            sender.sendText(resp.toString());
        }

        void answerError(int code, @NotNull String msg, @Nullable JsonElement data) {
            JsonObject error = new JsonObject();
            error.addProperty("code", code);
            error.addProperty("message", msg);
            if (data != null) error.add("data", data);
            answer(true, error);
        }

        void answerError(@NotNull PredefinedJsonRpcException ex) {
            answerError(ex.error.code(), ex.error.msg(), null);
        }

        void answerResult(@NotNull JsonElement result) {
            answer(false, result);
        }

        boolean isNotification() {
            return id == null;
        }
    }

    static abstract class GeneralJsonException extends Exception {
        GeneralJsonException() {
        }

        GeneralJsonException(String message) {
            super(message);
        }

        GeneralJsonException(String message, Throwable cause) {
            super(message, cause);
        }

        GeneralJsonException(Throwable cause) {
            super(cause);
        }
    }

    public static class PredefinedJsonRpcException extends GeneralJsonException {
        final JsonElement id;
        final JsonRpcError error;

        PredefinedJsonRpcException(@Nullable JsonElement id, @NotNull String msg, @NotNull JsonRpcError error) {
            super(msg);
            this.id = id;
            this.error = error;
        }

        PredefinedJsonRpcException(@Nullable JsonElement id, @NotNull JsonRpcError error) {
            this.id = id;
            this.error = error;
        }

        @NotNull
        public static PredefinedJsonRpcException from(@NotNull Request request, @NotNull JsonRpcError error) {
            return new PredefinedJsonRpcException(request.id, error);
        }
    }

    public static class JsonRpcSpecificationException extends GeneralJsonException {
        JsonRpcSpecificationException(@NotNull String message) {
            super(message);
        }

        @NotNull
        static JsonRpcSpecificationException missingMember(@NotNull String name) {
            return new JsonRpcSpecificationException("Missing `" + name + "` member.");
        }
    }
}
