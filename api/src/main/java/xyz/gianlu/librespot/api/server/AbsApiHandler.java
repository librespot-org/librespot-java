package xyz.gianlu.librespot.api.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Gianlu
 */
public abstract class AbsApiHandler {
    final String prefix;

    public AbsApiHandler(@NotNull String prefix) {
        this.prefix = prefix;
    }

    @NotNull
    protected static JsonElement string(@NotNull String str) {
        return new JsonPrimitive(str);
    }

    @NotNull
    protected static JsonElement number(@NotNull Number num) {
        return new JsonPrimitive(num);
    }

    final void handle(@NotNull ApiServer.Request request) {
        if (request.isNotification()) {
            handleNotification(request);
            return;
        }

        try {
            JsonElement result = handleRequest(request);
            request.answerResult(result);
        } catch (HandlingException ex) {
            request.answerError(ex.code.code, ex.msg, ex.data);
        } catch (ApiServer.PredefinedJsonRpcException ex) {
            request.answerError(ex);
        }
    }

    @NotNull
    protected abstract JsonElement handleRequest(@NotNull ApiServer.Request request) throws HandlingException, ApiServer.PredefinedJsonRpcException;

    protected abstract void handleNotification(@NotNull ApiServer.Request request);

    public enum ErrorCode {
        MERCURY_EXCEPTION(1001),
        IO_EXCEPTION(1002);

        public final int code;

        ErrorCode(int code) {
            this.code = code;
        }
    }

    protected static class HandlingException extends Exception {
        private final ErrorCode code;
        private final String msg;
        private final JsonElement data;

        public HandlingException(@NotNull ErrorCode code, @NotNull String msg, @Nullable JsonElement data) {
            super(msg);
            this.code = code;
            this.msg = msg;
            this.data = data;
        }

        public HandlingException(@NotNull ErrorCode code, @NotNull String msg) {
            this(code, msg, null);
        }

        public HandlingException(@NotNull Throwable cause, @NotNull ErrorCode code, @Nullable JsonElement data) {
            super(cause);
            this.code = code;
            this.msg = cause.getMessage();
            this.data = data;
        }

        public HandlingException(@NotNull Throwable cause, @NotNull ErrorCode code) {
            this(cause, code, null);
        }
    }
}
