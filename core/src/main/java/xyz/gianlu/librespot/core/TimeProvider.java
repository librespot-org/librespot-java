package xyz.gianlu.librespot.core;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Gianlu
 */
public final class TimeProvider {
    private static final AtomicLong offset = new AtomicLong(0);
    private static final Logger LOGGER = Logger.getLogger(TimeProvider.class);

    private TimeProvider() {
    }

    public static void init() throws IOException {
        synchronized (offset) {
            NTPUDPClient client = new NTPUDPClient();
            client.open();
            TimeInfo info = client.getTime(InetAddress.getByName("time.google.com"));
            info.computeDetails();
            Long offsetValue = info.getOffset();
            LOGGER.debug(String.format("Loaded time offset from NTP: %dms", offsetValue));
            offset.set(offsetValue == null ? 0 : offsetValue);
        }
    }

    public static long currentTimeMillis() {
        synchronized (offset) {
            return System.currentTimeMillis() + offset.get();
        }
    }
}
