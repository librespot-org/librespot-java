package org.librespot.spotify.mercury;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.crypto.Packet;
import org.librespot.spotify.proto.Mercury;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Gianlu
 */
public class MercuryClient {
    private static final Logger LOGGER = Logger.getLogger(MercuryClient.class);
    private final Session session;
    private final AtomicInteger seqHolder = new AtomicInteger(1);
    private final Map<Integer, Callback> callbacks = new ConcurrentHashMap<>();

    public MercuryClient(@NotNull Session session) {
        this.session = session;
    }

    public <M> void request(@NotNull GeneralMercuryRequest<M> request, @NotNull OnResult<M> listener) {
        try {
            send(request.uri, request.method, request.payload, response -> {
                if (response.statusCode >= 200 && response.statusCode < 300)
                    listener.result(request.processor.process(response));
                else
                    listener.failed(new MercuryException(response.statusCode));
            });
        } catch (IOException ex) {
            listener.failed(ex);
        }
    }

    public void send(@NotNull String uri, @NotNull Method method, @NotNull byte[][] payload, @NotNull Callback callback) throws IOException {
        LOGGER.trace(String.format("Send Mercury request, uri: %s, method: %s", uri, method.name));

        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytesOut);

        int seq;
        synchronized (seqHolder) {
            seq = seqHolder.getAndIncrement();
        }

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

        callbacks.put(seq, callback);

        Packet.Type cmd = method.command();
        session.send(cmd, bytesOut.toByteArray());
    }

    public void handle(@NotNull Packet packet) throws InvalidProtocolBufferException {
        ByteBuffer payload = ByteBuffer.wrap(packet.payload);
        if (payload.getShort() != 4) throw new IllegalStateException("seqHolder length must be 4!");
        int seq = payload.getInt();
        byte flags = payload.get();
        short parts = payload.getShort();

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

        Callback callback = callbacks.remove(seq);
        if (callback != null) {
            callback.response(new Response(header, payloadParts));
        } else {
            LOGGER.warn(String.format("Skipped Mercury response, seq: %d, uri: %s, code %d", seq, header.getUri(), header.getStatusCode()));
        }
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

    public static class MercuryException extends Exception {
        public MercuryException(int statusCode) {
            super("Status code: " + statusCode);
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
