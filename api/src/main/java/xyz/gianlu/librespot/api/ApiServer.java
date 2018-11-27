package xyz.gianlu.librespot.api;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;

import java.io.*;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static xyz.gianlu.librespot.common.Utils.EOL;

/**
 * @author Gianlu
 */
public class ApiServer implements Closeable {
    private final static Logger LOGGER = Logger.getLogger(ApiServer.class);
    private static final byte[] EMPTY = new byte[0];
    private final Looper looper;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public ApiServer(int port) throws IOException {
        executorService.execute(looper = new Looper(port));
    }

    private static byte[] getPayload(DataInputStream in, boolean mask, int length) throws IOException {
        byte[] decoded = new byte[length];

        if (mask) {
            byte[] key = new byte[4];
            in.readFully(key);

            byte[] encoded = new byte[length];
            in.readFully(encoded);
            for (int i = 0; i < encoded.length; i++)
                decoded[i] = (byte) (encoded[i] ^ key[i & 0x3]);
        } else {
            in.readFully(decoded);
        }

        return decoded;
    }

    @Override
    public void close() throws IOException {
        looper.stop();
    }

    public static class HandshakeFailedException extends IOException {
        HandshakeFailedException(String message) {
            super(message);
        }
    }

    private static class Frame {
        final byte opcode;
        final boolean fin;
        final byte[] payload;

        Frame(byte opcode, boolean fin, byte[] payload) {
            this.opcode = opcode;
            this.fin = fin;
            this.payload = payload;
        }
    }

    private class ClientRunner implements Runnable, Closeable {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private Random random;

        ClientRunner(@NotNull Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = new DataOutputStream(socket.getOutputStream());
        }

        void send(byte opcode, byte[] payload) throws IOException {
            if (random == null) throw new IllegalStateException();

            out.write(0b10000000 | (opcode & 0b00001111));
            if (payload.length >= 126) {
                if (payload.length > Short.MAX_VALUE * 2 + 1) {
                    out.write(0b00000000 | (127 & 0b01111111));
                    out.writeLong(payload.length);
                } else {
                    out.write(0b00000000 | (126 & 0b01111111));
                    out.writeShort(payload.length);
                }
            }

            out.write(payload);
            out.flush();
        }

        @Override
        public void run() {
            try {
                handshake();

                while (!socket.isClosed()) {
                    Frame frame = readMessage();

                    switch (frame.opcode) {
                        case 0x0:
                            LOGGER.warn("Received continuation frame out of sync.");
                            break;
                        case 0x1:
                            send((byte) 0x1, frame.payload); // FIXME
                            break;
                        case 0x2:
                            send((byte) 0x2, frame.payload); // FIXME
                            break;
                        case 0x8:
                            handleClose(frame.payload);
                            return;
                        case 0x9:
                            send((byte) 0xa, EMPTY);
                            break;
                        case 0xa:
                            LOGGER.trace("Pong ACK.");
                            break;

                    }
                }
            } catch (IOException | GeneralSecurityException ex) {
                LOGGER.fatal("Failed handling message!", ex);
            }
        }

        private void handleClose(byte[] payload) throws IOException {
            if (payload.length == 0) {
                LOGGER.info("WebSocket connection closed.");
            } else {
                ByteBuffer buffer = ByteBuffer.wrap(payload);

                short code = buffer.getShort();
                String reason = null;
                if (buffer.remaining() > 0) {
                    byte[] b = new byte[buffer.remaining()];
                    buffer.get(b);
                    reason = new String(b);
                }

                LOGGER.info(String.format("WebSocket connection closed, code: %d, reason: %s", code, reason));
            }

            close();
        }

        @NotNull
        private Frame readFrame() throws IOException {
            byte b = in.readByte();
            boolean fin = (b >> 7 & 0b1) != 0;
            byte opcode = (byte) (b & 0b00001111);

            b = in.readByte();
            boolean mask = (b >> 7 & 0b1) != 0;
            int length = b & 0b01111111;
            if (length == 126) {
                length = in.readUnsignedShort();
            } else if (length == 127) {
                long tmp = in.readLong();
                if (tmp <= Integer.MAX_VALUE) length = (int) tmp;
                else throw new UnsupportedOperationException("Unsupported message length: " + tmp);
            }

            return new Frame(opcode, fin, getPayload(in, mask, length));
        }

        @NotNull
        private Frame readMessage() throws IOException {
            Frame original = readFrame();
            if (original.fin) return original;

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(original.payload);

            Frame frame;
            while ((frame = readFrame()).opcode == 0x0) {
                bytes.write(frame.payload);

                if (frame.fin)
                    return new Frame(original.opcode, true, bytes.toByteArray());
            }

            throw new IllegalStateException("Illegal Websocket fragmentation sequence.");
        }

        private void handshake() throws IOException, GeneralSecurityException {
            String sl = Utils.readLine(in);
            if (sl.startsWith("GET")) {
                String websocketKey = null;
                String line;
                while (!(line = Utils.readLine(in)).isEmpty()) {
                    if (line.startsWith("Sec-WebSocket-Key"))
                        websocketKey = line.substring(19);
                }

                if (websocketKey == null) throw new HandshakeFailedException("Missing `Sec-WebSocket-Key` header!");

                byte[] websocketKeyDigest = MessageDigest.getInstance("SHA-1")
                        .digest((websocketKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes());

                byte[] websocketAccept = Base64.getEncoder().encode(websocketKeyDigest);

                out.write("HTTP/1.1 101 Switching Protocols".getBytes());
                out.write(EOL);
                out.write("Connection: Upgrade".getBytes());
                out.write(EOL);
                out.write("Upgrade: websocket".getBytes());
                out.write(EOL);
                out.write("Sec-WebSocket-Accept: ".getBytes());
                out.write(websocketAccept);
                out.write(EOL);
                out.write(EOL);
                out.flush();

                this.random = new Random(new BigInteger(websocketKeyDigest).longValue());
            } else {
                throw new HandshakeFailedException(sl);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private class Looper implements Runnable {
        private final ServerSocket serverSocket;
        private volatile boolean shouldStop = false;

        Looper(int port) throws IOException {
            serverSocket = new ServerSocket(port);
        }

        @Override
        public void run() {
            while (!shouldStop && !serverSocket.isClosed()) {
                try {
                    executorService.execute(new ClientRunner(serverSocket.accept()));
                } catch (IOException ex) {
                    LOGGER.fatal("Failed accepting connection!", ex);
                }
            }
        }

        private void stop() throws IOException {
            shouldStop = true;
            serverSocket.close();
        }
    }
}
