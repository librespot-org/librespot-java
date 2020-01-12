package xyz.gianlu.librespot.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Gianlu
 */
public final class TimeProvider {
    private static final AtomicLong offset = new AtomicLong(0);
    private static final Logger LOGGER = Logger.getLogger(TimeProvider.class);
    private static final JsonParser PARSER = new JsonParser();
    private static Method method = Method.NTP;

    private TimeProvider() {
    }

    public static void init(@NotNull Configuration conf) {
        switch (method = conf.timeSynchronizationMethod()) {
            case NTP:
                try {
                    updateWithNtp();
                } catch (IOException ex) {
                    LOGGER.warn("Failed updating time!", ex);
                }
                break;
            case MANUAL:
                synchronized (offset) {
                    offset.set(conf.timeManualCorrection());
                }
                break;
            default:
            case PING:
            case MELODY:
                break;
        }
    }

    public static void init(@NotNull Session session) {
        if (method != Method.MELODY) return;

        updateMelody(session);
    }

    public static long currentTimeMillis() {
        synchronized (offset) {
            return System.currentTimeMillis() + offset.get();
        }
    }

    private static void updateWithNtp() throws IOException {
        try {
            synchronized (offset) {
                NTPUDPClient client = new NTPUDPClient();
                client.open();
                client.setSoTimeout((int) TimeUnit.SECONDS.toMillis(10));
                TimeInfo info = client.getTime(InetAddress.getByName("time.google.com"));
                info.computeDetails();
                Long offsetValue = info.getOffset();
                LOGGER.debug(String.format("Loaded time offset from NTP: %dms", offsetValue));
                offset.set(offsetValue == null ? 0 : offsetValue);
            }
        } catch (SocketTimeoutException ex) {
            updateWithNtp();
        }
    }

    private static void updateMelody(@NotNull Session session) {
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

            LOGGER.info(String.format("Loaded time offset from melody: %dms", diff));
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.error("Failed requesting time!", ex);
        }
    }

    public static void updateWithPing(byte[] pingPayload) {
        if (method != Method.PING) return;

        synchronized (offset) {
            long diff = ByteBuffer.wrap(pingPayload).getInt() * 1000 - System.currentTimeMillis();
            offset.set(diff);

            LOGGER.debug(String.format("Loaded time offset from ping: %dms", diff));
        }
    }

    public enum Method {
        NTP, PING, MELODY, MANUAL
    }

    public interface Configuration {
        @NotNull Method timeSynchronizationMethod();

        int timeManualCorrection();
    }
}
