package xyz.gianlu.librespot.mercury;

import com.google.gson.JsonElement;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.BytesArrayList;
import xyz.gianlu.librespot.common.ProtobufToJson;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Mercury;
import xyz.gianlu.librespot.common.proto.Pubsub;
import xyz.gianlu.librespot.core.PacketsManager;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.crypto.Packet;

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
public class MercuryClient extends PacketsManager {
    private static final Logger LOGGER = Logger.getLogger(MercuryClient.class);
    private final AtomicInteger seqHolder = new AtomicInteger(1);
    private final Map<Long, Callback> callbacks = new ConcurrentHashMap<>();
    private final List<InternalSubListener> subscriptions = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, BytesArrayList> partials = new HashMap<>();

    public MercuryClient(@NotNull Session session) {
        super(session);
    }

    public void subscribe(@NotNull String uri, @NotNull SubListener listener) throws IOException, PubSubException {
        Response response = sendSync(RawMercuryRequest.sub(uri));
        if (response.statusCode != 200) throw new PubSubException(response);

        if (response.payload.size() > 0) {
            for (byte[] payload : response.payload) {
                Pubsub.Subscription sub = Pubsub.Subscription.parseFrom(payload);
                subscriptions.add(new InternalSubListener(sub.getUri(), listener, true));
            }
        } else {
            subscriptions.add(new InternalSubListener(uri, listener, true));
        }

        LOGGER.trace(String.format("Subscribed successfully to %s!", uri));
    }

    public void unsubscribe(@NotNull String uri) throws IOException, PubSubException {
        Response response = sendSync(RawMercuryRequest.unsub(uri));
        if (response.statusCode != 200) throw new PubSubException(response);

        subscriptions.removeIf(l -> l.matches(uri));
        LOGGER.trace(String.format("Unsubscribed successfully from %s!", uri));
    }

    @NotNull
    public Response sendSync(@NotNull RawMercuryRequest request) throws IOException {
        AtomicReference<Response> reference = new AtomicReference<>(null);
        send(request, response -> {
            synchronized (reference) {
                reference.set(response);
                reference.notifyAll();
            }
        });

        return Utils.wait(reference);
    }

    @NotNull
    public <W extends JsonWrapper> W sendSync(@NotNull JsonMercuryRequest<W> request) throws IOException, MercuryException {
        Response resp = sendSync(request.request);
        if (resp.statusCode >= 200 && resp.statusCode < 300) return request.instantiate(resp);
        else throw new MercuryException(resp);
    }

    @NotNull
    public <P extends Message> ProtoWrapperResponse<P> sendSync(@NotNull ProtobufMercuryRequest<P> request) throws IOException, MercuryException {
        Response resp = sendSync(request.request);
        if (resp.statusCode >= 200 && resp.statusCode < 300)
            return new ProtoWrapperResponse<>(request.parser.parseFrom(resp.payload.stream()));
        else
            throw new MercuryException(resp);
    }

    public void send(@NotNull RawMercuryRequest request, @NotNull Callback callback) throws IOException {
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytesOut);

        int seq;
        synchronized (seqHolder) {
            seq = seqHolder.getAndIncrement();
        }

        LOGGER.trace(String.format("Send Mercury request, seq: %d, uri: %s, method: %s", seq, request.header.getUri(), request.header.getMethod()));

        out.writeShort((short) 4); // Seq length
        out.writeInt(seq); // Seq

        out.writeByte(1); // Flags
        out.writeShort(1 + request.payload.length); // Parts count

        byte[] headerBytes = request.header.toByteArray();
        out.writeShort(headerBytes.length); // Header length
        out.write(headerBytes); // Header

        for (byte[] part : request.payload) { // Parts
            out.writeShort(part.length);
            out.write(part);
        }

        Packet.Type cmd = Packet.Type.forMethod(request.header.getMethod());
        session.send(cmd, bytesOut.toByteArray());

        callbacks.put((long) seq, callback);
    }

    @Override
    protected void handle(@NotNull Packet packet) throws InvalidProtocolBufferException {
        ByteBuffer payload = ByteBuffer.wrap(packet.payload);
        int seqLength = payload.getShort();
        long seq;
        if (seqLength == 2) seq = payload.getShort();
        else if (seqLength == 4) seq = payload.getInt();
        else if (seqLength == 8) seq = payload.getLong();
        else throw new IllegalArgumentException("Unknown seq length: " + seqLength);

        byte flags = payload.get();
        short parts = payload.getShort();

        BytesArrayList partial = partials.get(seq);
        if (partial == null || flags == 0) {
            partial = new BytesArrayList();
            partials.put(seq, partial);
        }

        LOGGER.trace(String.format("Handling packet, cmd: %s, seq: %d, flags: %d, parts: %d", packet.type(), seq, flags, parts));

        for (int i = 0; i < parts; i++) {
            short size = payload.getShort();
            byte[] buffer = new byte[size];
            payload.get(buffer);
            partial.add(buffer);
        }

        if (flags != 1) return;

        partials.remove(seq);

        Mercury.Header header;
        try {
            header = Mercury.Header.parseFrom(partial.get(0));
        } catch (InvalidProtocolBufferException ex) {
            LOGGER.fatal(String.format("Couldn't parse header! {bytes: %s}", Utils.bytesToHex(partial.get(0))));
            throw ex;
        }

        Response resp = new Response(header, partial);

        if (packet.is(Packet.Type.MercuryEvent)) {
            boolean dispatched = false;
            for (InternalSubListener sub : subscriptions) {
                if (sub.matches(header.getUri())) {
                    sub.dispatch(resp);
                    dispatched = true;
                }
            }

            if (!dispatched)
                LOGGER.warn(String.format("Couldn't dispatch Mercury event {seq: %d, uri: %s, code: %d, payload: %s}", seq, header.getUri(), header.getStatusCode(), Utils.bytesToHex(resp.payload.get(0))));
        } else if (packet.is(Packet.Type.MercuryReq) || packet.is(Packet.Type.MercurySub) || packet.is(Packet.Type.MercuryUnsub)) {
            Callback callback = callbacks.remove(seq);
            if (callback != null) {
                callback.response(resp);
            } else {
                LOGGER.warn(String.format("Skipped Mercury response, seq: %d, uri: %s, code %d", seq, header.getUri(), header.getStatusCode()));
            }

            synchronized (callbacks) {
                callbacks.notifyAll();
            }
        } else {
            LOGGER.warn(String.format("Couldn't handle packet, seq: %d, uri: %s, code %d", seq, header.getUri(), header.getStatusCode()));
        }
    }

    @Override
    protected void exception(@NotNull Exception ex) {
        LOGGER.fatal("Failed handling packet!", ex);
    }

    public void interestedIn(@NotNull String uri, @NotNull SubListener listener) {
        subscriptions.add(new InternalSubListener(uri, listener, false));
    }

    public void notInterested(@NotNull SubListener listener) {
        synchronized (subscriptions) {
            Iterator<InternalSubListener> iter = subscriptions.iterator();
            while (iter.hasNext())
                if (iter.next().listener == listener)
                    iter.remove();
        }
    }

    @Override
    public void close() {
        if (!subscriptions.isEmpty()) {
            for (InternalSubListener listener : new ArrayList<>(subscriptions)) {
                try {
                    if (listener.isSub) unsubscribe(listener.uri);
                    else notInterested(listener.listener);
                } catch (IOException | PubSubException ex) {
                    LOGGER.debug("Failed unsubscribing.", ex);
                }
            }
        }

        while (true) {
            if (callbacks.isEmpty()) {
                break;
            } else {
                synchronized (callbacks) {
                    try {
                        callbacks.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        super.close();
    }

    public interface Callback {
        void response(@NotNull Response response);
    }

    public static class ProtoWrapperResponse<P extends Message> {
        private final P proto;
        private JsonElement json;

        ProtoWrapperResponse(@NotNull P proto) {
            this.proto = proto;
        }

        @NotNull
        public P proto() {
            return proto;
        }

        @NotNull
        public JsonElement json() {
            if (json == null) json = ProtobufToJson.convert(proto);
            return json;
        }
    }

    public static class PubSubException extends MercuryException {
        private PubSubException(Response response) {
            super(response);
        }
    }

    private static class InternalSubListener {
        private final String uri;
        private final SubListener listener;
        private final boolean isSub;

        InternalSubListener(@NotNull String uri, @NotNull SubListener listener, boolean isSub) {
            this.uri = uri;
            this.listener = listener;
            this.isSub = isSub;
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
        public final BytesArrayList payload;
        public final int statusCode;

        private Response(@NotNull Mercury.Header header, @NotNull BytesArrayList payload) {
            this.uri = header.getUri();
            this.statusCode = header.getStatusCode();
            this.payload = payload.copyOfRange(1, payload.size());
        }
    }
}
