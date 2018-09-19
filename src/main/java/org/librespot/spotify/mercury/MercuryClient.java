package org.librespot.spotify.mercury;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.Utils;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.crypto.Packet;
import org.librespot.spotify.proto.Mercury;
import org.librespot.spotify.proto.Pubsub;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Gianlu
 */
public class MercuryClient implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(MercuryClient.class);
    private final Session session;
    private final AtomicInteger seqHolder = new AtomicInteger(1);
    private final Map<Long, Callback> callbacks = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final List<InternalSubListener> subscriptions = Collections.synchronizedList(new ArrayList<>());
    private final BlockingQueue<Packet> packetsQueue = new LinkedBlockingQueue<>();
    private final Looper looper;

    public MercuryClient(@NotNull Session session) {
        this.session = session;

        this.looper = new Looper();
        new Thread(looper).start();
    }

    @NotNull
    public String username() {
        return session.apWelcome().getCanonicalUsername();
    }

    @NotNull
    public <M> M requestSync(@NotNull GeneralMercuryRequest<M> request) throws IOException, MercuryException {
        Response response = sendSync(request.uri, request.method, request.payload);
        if (response.statusCode >= 200 && response.statusCode < 300) return request.processor.process(response);
        else throw new MercuryException(response);
    }

    public void subscribe(@NotNull String uri, @NotNull SubListener listener) throws IOException, PubSubException {
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
    public Response sendSync(@NotNull String uri, @NotNull Method method, @NotNull byte[][] payload) throws IOException {
        AtomicReference<Response> reference = new AtomicReference<>(null);
        send(uri, method, payload, response -> {
            synchronized (reference) {
                reference.set(response);
                reference.notifyAll();
            }
        });

        return Utils.wait(reference);
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

        Packet.Type cmd = method.command();
        session.send(cmd, bytesOut.toByteArray());

        callbacks.put((long) seq, callback);
    }

    public void handle(@NotNull Packet packet) {
        packetsQueue.add(packet);
    }

    @Override
    public void close() {
        looper.stop();
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
        void response(@NotNull Response response);
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

        void dispatch(@NotNull Response resp) {
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

    private class Looper implements Runnable {
        private volatile boolean shouldStop = false;

        void stop() {
            shouldStop = true;
        }

        private void handle(@NotNull Packet packet) throws InvalidProtocolBufferException {
            ByteBuffer payload = ByteBuffer.wrap(packet.payload);
            int seqLength = payload.getShort();
            long seq;
            if (seqLength == 2) seq = payload.getShort();
            else if (seqLength == 4) seq = payload.getInt();
            else if (seqLength == 8) seq = payload.getLong();
            else throw new IllegalArgumentException("Unknown seq length: " + seqLength);

            byte flags = payload.get();
            short parts = payload.getShort();

            LOGGER.trace(String.format("Handling packet, cmd: %s, seq: %d, parts: %d", packet.type(), seq, parts));

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
                        dispatch(sub, resp);
                        dispatched = true;
                    }
                }

                if (!dispatched)
                    LOGGER.warn(String.format("Couldn't dispatch Mercury sub event, seq: %d, uri: %s, code %d", seq, header.getUri(), header.getStatusCode()));
            } else if (packet.is(Packet.Type.MercuryReq) || packet.is(Packet.Type.MercurySub)) {
                Callback callback = callbacks.remove(seq);
                if (callback != null) {
                    call(callback, resp);
                } else {
                    LOGGER.warn(String.format("Skipped Mercury response, seq: %d, uri: %s, code %d", seq, header.getUri(), header.getStatusCode()));
                }
            } else {
                LOGGER.warn(String.format("Couldn't handle packet, seq: %d, uri: %s, code %d", seq, header.getUri(), header.getStatusCode()));
            }
        }

        private void call(Callback callback, Response resp) {
            executorService.execute(() -> callback.response(resp));
        }

        private void dispatch(InternalSubListener listener, Response resp) {
            executorService.execute(() -> listener.dispatch(resp));
        }

        @Override
        public void run() {
            while (!shouldStop) {
                try {
                    Packet packet = packetsQueue.take();
                    handle(packet);
                } catch (InterruptedException | InvalidProtocolBufferException ex) {
                    LOGGER.fatal("Failed handling packet!", ex);
                }
            }
        }
    }
}
