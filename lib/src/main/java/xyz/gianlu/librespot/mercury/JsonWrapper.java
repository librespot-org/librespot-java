package xyz.gianlu.librespot.mercury;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public abstract class JsonWrapper {
    public final JsonObject obj;

    public JsonWrapper(@NotNull JsonObject obj) {
        this.obj = obj;
    }

    @Override
    public String toString() {
        return obj.toString();
    }
}
