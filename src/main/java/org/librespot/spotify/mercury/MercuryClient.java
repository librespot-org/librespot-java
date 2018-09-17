package org.librespot.spotify.mercury;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.crypto.Packet;
import org.librespot.spotify.proto.Mercury;
import org.librespot.spotify.proto.Pubsub;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Gianlu
 */
public class MercuryClient {
    private static final Logger LOGGER = Logger.getLogger(MercuryClient.class);
    private final Session session;
    private final AtomicInteger seqHolder = new AtomicInteger(1);
    private final Map<Long, Callback> callbacks = new ConcurrentHashMap<>();
    private final List<InternalSubListener> subscriptions = Collections.synchronizedList(new ArrayList<>());

    public MercuryClient(@NotNull Session session) {
        this.session = session;
    }

    @NotNull
    public String username() {
        return session.apWelcome().getCanonicalUsername();
    }

    public <M> void request(@NotNull GeneralMercuryRequest<M> request, @NotNull OnResult<M> listener) {
        try {
            send(request.uri, request.method, request.payload, response -> {
                if (response.statusCode >= 200 && response.statusCode < 300)
                    listener.result(request.processor.process(response));
                else
                    listener.failed(new MercuryException(response));
            });
        } catch (IOException ex) {
            listener.failed(ex);
        }
    }

    public void subscribe(@NotNull String uri, @NotNull SubListener listener) throws IOException, InterruptedException, PubSubException {
        Response response = sendSync(uri, Method.SUB, new byte[0][0]);
        if (response.statusCode != 200) throw new PubSubException(response);

        if (response.payload.length > 0) {
            for (byte[] payload : response.payload) {
                Pubsub.Subscription sub = Pubsub.Subscription.parseFrom(payload);
                subscriptions.add(new InternalSubListener(sub.getUri(), listener));
            }
        } else {
            subscriptions.add(new InternalSubListener(uri, listener));
        }

        LOGGER.trace(String.format("Subscribed successfully to %s!", uri));
    }

    @NotNull
    public Response sendSync(@NotNull String uri, @NotNull Method method, @NotNull byte[][] payload) throws IOException, InterruptedException {
        final AtomicReference<Response> reference = new AtomicReference<>(null);
        send(uri, method, payload, response -> {
            synchronized (reference) {
                reference.set(response);
                reference.notifyAll();
            }
        });

        synchronized (reference) {
            reference.wait();
            return reference.get();
        }
    }

    public void send(@NotNull String uri, @NotNull Method method, @NotNull byte[][] payload, @NotNull Callback callback) throws IOException {
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytesOut);

        int seq;
        synchronized (seqHolder) {
            seq = seqHolder.getAndIncrement();
        }

        LOGGER.trace(String.format("Send Mercury request, seq: %d, uri: %s, method: %s", seq, uri, method.name));

        out.writeShort((short) 4); // Seq length
        out.writeInt(seq); // Seq

        out.writeByte(1); // Flags
        out.writeShort(1 + payload.length); // Parts count

        Mercury.Header header = Mercury.Header.newBuilder()
                .setMethod(method.name)
                .setUri(uri)
                .build();

        byte[] headerBytes = header.toByteArray();
        out.writeShort(headerBytes.length); // Header length
        out.write(headerBytes); // Header

        for (byte[] part : payload) { // Parts
            out.writeShort(part.length);
            out.write(part);
        }

        callbacks.put((long) seq, callback);

        Packet.Type cmd = method.command();
        session.send(cmd, bytesOut.toByteArray());
    }

    public void handle(@NotNull Packet packet) throws InvalidProtocolBufferException {
        ByteBuffer payload = ByteBuffer.wrap(packet.payload);
        int seqLength = payload.getShort();
        long seq;
        if (seqLength == 2) seq = payload.getShort();
        else if (seqLength == 4) seq = payload.getInt();
        else if (seqLength == 8) seq = payload.getLong();
        else throw new IllegalArgumentException("Unknown seq length: " + seqLength);

        byte flags = payload.get();
        short parts = payload.getShort();

        LOGGER.trace(String.format("Handling packet, cmd: %s, seq: %d, parts: %d",  packet.type(), seq, parts));

        byte[][] payloadParts = new byte[parts][];
        for (int i = 0; i < parts; i++) {
            /*
            part, err := parsePart(reader)
		    if err != nil {
			    fmt.Println("read part")
			    return nil, err
		    }

		    if pending.partial != nil {
			    part = append(pending.partial, part...)
			    pending.partial = nil
		    }

		    if i == count-1 && (flags == 2) {
			    pending.partial = part
		    } else {
			    pending.parts = append(pending.parts, part)
		    }
             */

            short size = payload.getShort();
            byte[] buffer = new byte[size];
            payload.get(buffer);
            payloadParts[i] = buffer;
        }

        Mercury.Header header = Mercury.Header.parseFrom(payloadParts[0]);
        Response resp = new Response(header, payloadParts);

        if (packet.is(Packet.Type.MercurySubEvent)) {
            boolean dispatched = false;
            for (InternalSubListener sub : subscriptions) {
                if (sub.matches(header.getUri())) {
                    sub.dispatch(resp);
                    dispatched = true;
                }
            }

            if (!dispatched)
                LOGGER.warn(String.format("Couldn't dispatch Mercury sub event, seq: %d, uri: %s, code %d", seq, header.getUri(), header.getStatusCode()));
        } else if (packet.is(Packet.Type.MercuryReq) || packet.is(Packet.Type.MercurySub)) {
            Callback callback = callbacks.remove(seq);
            if (callback != null) {
                callback.response(resp);
            } else {
                LOGGER.warn(String.format("Skipped Mercury response, seq: %d, uri: %s, code %d", seq, header.getUri(), header.getStatusCode()));
            }
        } else {
            LOGGER.warn(String.format("Couldn't handle packet, seq: %d, uri: %s, code %d", seq, header.getUri(), header.getStatusCode()));
        }
    }

    @NotNull
    public String deviceId() {
        return session.deviceId();
    }

    @NotNull
    public Session.DeviceType deviceType() {
        return session.deviceType();
    }

    public enum Method {
        GET("GET"),
        SEND("SEND"),
        SUB("SUB"),
        UNSUB("UNSUB");

        private final String name;

        Method(String name) {
            this.name = name;
        }

        @NotNull
        public static Method parse(String method) {
            for (Method m : values())
                if (m.name.equals(method))
                    return m;

            throw new IllegalStateException("Unknown method: " + method);
        }

        @NotNull
        public Packet.Type command() {
            switch (this) {
                case SEND:
                case GET:
                    return Packet.Type.MercuryReq;
                case SUB:
                    return Packet.Type.MercurySub;
                case UNSUB:
                    return Packet.Type.MercuryUnsub;
                default:
                    throw new IllegalStateException("Unknown method: " + this);
            }
        }
    }

    public interface Callback {
        void response(@NotNull Response response) throws InvalidProtocolBufferException;
    }

    public static class PubSubException extends MercuryException {
        private PubSubException(Response response) {
            super(response);
        }
    }

    private static class InternalSubListener {
        private final String uri;
        private final SubListener listener;

        InternalSubListener(@NotNull String uri, @NotNull SubListener listener) {
            this.uri = uri;
            this.listener = listener;
        }

        boolean matches(String uri) {
            return uri.startsWith(this.uri);
        }

        synchronized void dispatch(@NotNull Response resp) {
            listener.event(resp);
        }
    }

    public static class MercuryException extends Exception {
        private MercuryException(Response response) {
            super(String.format("status: %d", response.statusCode));
        }
    }

    public static class Response {
        public final String uri;
        public final byte[][] payload;
        public final int statusCode;

        private Response(Mercury.Header header, byte[][] payload) {
            this.uri = header.getUri();
            this.statusCode = header.getStatusCode();
            this.payload = Arrays.copyOfRange(payload, 1, payload.length);
        }
    }
}
