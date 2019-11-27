package xyz.gianlu.librespot.core;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;
import xyz.gianlu.librespot.player.StateWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @author Gianlu
 */
public final class EventServiceHelper {

    private EventServiceHelper() {
    }

    private static void sendEvent(@NotNull Session session, @NotNull EventBuilder builder) throws IOException {
        byte[] body = builder.toArray();
        System.out.println(Utils.bytesToHex(body));

        MercuryClient.Response resp = session.mercury().sendSync(RawMercuryRequest.newBuilder()
                .setUri("hm://event-service/v1/events").setMethod("POST")
                .addUserField("Accept-Language", "en").addUserField("X-Offset", "321" /* FIXME ?? */)
                .addUserField("X-ClientTimeStamp", String.valueOf(TimeProvider.currentTimeMillis()))
                .addPayloadPart(body)
                .build());

        System.out.println(resp.statusCode);
    }

    public static void reportPlayback(@NotNull Session session, @NotNull StateWrapper state, @NotNull String uri, @NotNull PlaybackIntervals intervals) throws IOException {
        EventBuilder event = new EventBuilder();
        event.chars("372");
        event.delimiter().chars('1');
        event.delimiter().chars(state.getPlaybackId());
        event.delimiter().chars(uri);
        event.delimiter().chars('0');
        event.delimiter().chars(intervals.toSend());

        sendEvent(session, event);
    }

    public static void announceNewPlaybackId(@NotNull Session session, @NotNull StateWrapper state) throws IOException {
        EventBuilder event = new EventBuilder();
        event.chars("558");
        event.delimiter().chars('1');
        event.delimiter().chars(state.getPlaybackId());
        event.delimiter().chars(state.getSessionId());
        event.delimiter().chars(String.valueOf(TimeProvider.currentTimeMillis()));

        sendEvent(session, event);
    }

    public static void announceNewSessionId(@NotNull Session session, @NotNull StateWrapper state) throws IOException {
        EventBuilder event = new EventBuilder();
        event.chars("557");
        event.delimiter().chars('3');
        event.delimiter().chars(state.getSessionId());

        String contextUri = state.getContextUri();
        event.delimiter().chars(contextUri);
        event.delimiter().chars(contextUri);
        event.delimiter().chars(String.valueOf(TimeProvider.currentTimeMillis()));
        event.delimiter().delimiter().chars("300"); // FIXME: Number of tracks in context
        event.delimiter().chars("context://" + state.getContextUri()); // FIXME. Might not be this way

        sendEvent(session, event);
    }

    private static class EventBuilder {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream(256);

        EventBuilder() {
        }

        EventBuilder chars(char c) {
            body.write(c);
            return this;
        }

        EventBuilder chars(@NotNull String str) throws IOException {
            body.write(str.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        EventBuilder delimiter() {
            body.write(0x09);
            return this;
        }

        @NotNull
        byte[] toArray() throws IOException {
            byte[] bodyBytes = body.toByteArray();

            ByteArrayOutputStream out = new ByteArrayOutputStream(bodyBytes.length + 2);
            out.write(bodyBytes.length << 8);
            out.write(bodyBytes.length & 0xFF);
            out.write(bodyBytes);
            return out.toByteArray();
        }
    }

    public static class PlaybackIntervals {

        @NotNull
        String toSend() {
            return "[[0,40000]]";
        }
    }
}
