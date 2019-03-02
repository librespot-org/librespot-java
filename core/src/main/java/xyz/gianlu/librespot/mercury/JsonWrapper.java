package xyz.gianlu.librespot.mercury;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public abstract class JsonWrapper {
    private final JsonElement elm;

    public JsonWrapper(@NotNull JsonElement elm) {
        this.elm = elm;
    }

    @NotNull
    public final JsonObject obj() {
        return elm.getAsJsonObject();
    }

    @NotNull
    public final JsonArray array() {
        return elm.getAsJsonArray();
    }

    @Override
    public String toString() {
        return elm.toString();
    }
}
