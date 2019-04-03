package xyz.gianlu.librespot.mercury;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;

/**
 * @author Gianlu
 */
public class JsonMercuryRequest<W extends JsonWrapper> {
    private static final JsonParser PARSER = new JsonParser();
    final RawMercuryRequest request;
    private final Class<W> wrapperClass;

    JsonMercuryRequest(@NotNull RawMercuryRequest request, @NotNull Class<W> wrapperClass) {
        this.request = request;
        this.wrapperClass = wrapperClass;
    }

    @NotNull
    public W instantiate(@NotNull MercuryClient.Response resp) {
        try {
            JsonElement elm = PARSER.parse(new InputStreamReader(resp.payload.stream()));
            return wrapperClass.getConstructor(JsonObject.class).newInstance(elm.getAsJsonObject());
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException ex) {
            throw new RuntimeException(ex);
        }
    }
}
