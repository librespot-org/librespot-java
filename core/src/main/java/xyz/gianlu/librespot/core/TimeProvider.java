package xyz.gianlu.librespot.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Gianlu
 */
public final class TimeProvider {
    private static final AtomicLong offset = new AtomicLong(0);
    private static final Logger LOGGER = Logger.getLogger(TimeProvider.class);
    private static final JsonParser PARSER = new JsonParser();

    private TimeProvider() {
    }

    public static long currentTimeMillis() {
        synchronized (offset) {
            return System.currentTimeMillis() + offset.get();
        }
    }

    public static void update(@NotNull Session session) {
        try (Response resp = session.api().send("OPTIONS", "/melody/v1/time", null, null)) {
            if (resp.code() != 200) {
                LOGGER.error(String.format("Failed notifying server of time request! {code: %d, msg: %s}", resp.code(), resp.message()));
                return;
            }
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.error("Failed notifying server of time request!", ex);
            return;
        }

        try (Response resp = session.api().send("GET", "/melody/v1/time", null, null)) {
            if (resp.code() != 200) {
                LOGGER.error(String.format("Failed requesting time! {code: %d, msg: %s}", resp.code(), resp.message()));
                return;
            }

            ResponseBody body = resp.body();
            if (body == null) throw new IllegalStateException();

            JsonObject obj = PARSER.parse(body.string()).getAsJsonObject();
            long diff = obj.get("timestamp").getAsLong() - System.currentTimeMillis();
            synchronized (offset) {
                offset.set(diff);
            }

            LOGGER.info(String.format("Updated time! {diff: %dms}", diff));
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.error("Failed requesting time!", ex);
        }
    }
}
