package org.librespot.spotify;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.crypto.ChiperPair;
import org.librespot.spotify.crypto.DiffieHellman;
import org.librespot.spotify.crypto.Packet;
import org.librespot.spotify.mercury.MercuryClient;
import org.librespot.spotify.proto.Authentication;
import org.librespot.spotify.proto.Keyexchange;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author Gianlu
 */
public class Session implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(Session.class);
    private final Socket socket;
    private final DiffieHellman keys;
    private final SecureRandom random;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final String deviceId;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private ChiperPair chiperPair;
    private Receiver receiver;
    private Authentication.APWelcome apWelcome = null;
    private MercuryClient mercuryClient;

    private Session(Socket socket) throws IOException {
        this.socket = socket;
        this.random = new SecureRandom();
        this.keys = new DiffieHellman(random);

        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());

        this.deviceId = UUID.randomUUID().toString();

        LOGGER.info(String.format("Created new session! {deviceId: %s, ap: %s} ", deviceId, socket.getInetAddress()));
    }

    @NotNull
    public static Session create() throws IOException {
        return new Session(ApResolver.getSocketFromRandomAccessPoint());
    }

    public void connectAsync(OnSuccess listener) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    connect();
                    listener.success();
                } catch (IOException | GeneralSecurityException | SpotifyAuthenticationException ex) {
                    listener.failed(ex);
                }
            }
        });
    }

    public void connect() throws IOException, GeneralSecurityException, SpotifyAuthenticationException {
        Accumulator acc = new Accumulator();

        // Send ClientHello

        byte[] nonce = new byte[0x10];
        random.nextBytes(nonce);

        Keyexchange.ClientHello clientHello = Keyexchange.ClientHello.newBuilder()
                .setBuildInfo(Keyexchange.BuildInfo.newBuilder()
                        .setProduct(Keyexchange.Product.PRODUCT_PARTNER)
                        .setPlatform(Keyexchange.Platform.PLATFORM_LINUX_X86)
                        .setVersion(0x10800000000L)
                        .build())
                .addCryptosuitesSupported(Keyexchange.Cryptosuite.CRYPTO_SUITE_SHANNON)
                .setLoginCryptoHello(Keyexchange.LoginCryptoHelloUnion.newBuilder()
                        .setDiffieHellman(Keyexchange.LoginCryptoDiffieHellmanHello.newBuilder()
                                .setGc(ByteString.copyFrom(keys.publicKeyArray()))
                                .setServerKeysKnown(1)
                                .build())
                        .build())
                .setClientNonce(ByteString.copyFrom(nonce))
                .setPadding(ByteString.copyFrom(new byte[]{0x1e}))
                .build();

        byte[] clientHelloBytes = clientHello.toByteArray();
        int length = 2 + 4 + clientHelloBytes.length;
        out.writeByte(0);
        out.writeByte(4);
        out.writeInt(length);
        out.write(clientHelloBytes);
        out.flush();

        acc.writeByte(0);
        acc.writeByte(4);
        acc.writeInt(length);
        acc.write(clientHelloBytes);


        // Read APResponseMessage

        length = in.readInt();
        acc.writeInt(length);
        byte[] buffer = new byte[length - 4];
        in.readFully(buffer);
        acc.write(buffer);
        acc.dump();

        Keyexchange.APResponseMessage apResponseMessage = Keyexchange.APResponseMessage.parseFrom(buffer);
        keys.computeSharedKey(apResponseMessage.getChallenge().getLoginCryptoChallenge().getDiffieHellman().getGs().toByteArray());


        // Solve challenge

        ByteArrayOutputStream data = new ByteArrayOutputStream(0x64);

        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(keys.sharedKeyArray(), "HmacSHA1"));
        for (int i = 1; i < 6; i++) {
            mac.update(acc.array());
            mac.update(new byte[]{(byte) i});
            data.write(mac.doFinal());
            mac.reset();
        }

        byte[] dataArray = data.toByteArray();
        mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(Arrays.copyOfRange(dataArray, 0, 0x14), "HmacSHA1"));
        mac.update(acc.array());

        byte[] challenge = mac.doFinal();
        Keyexchange.ClientResponsePlaintext clientResponsePlaintext = Keyexchange.ClientResponsePlaintext.newBuilder()
                .setLoginCryptoResponse(Keyexchange.LoginCryptoResponseUnion.newBuilder()
                        .setDiffieHellman(Keyexchange.LoginCryptoDiffieHellmanResponse.newBuilder()
                                .setHmac(ByteString.copyFrom(challenge))
                                .build())
                        .build())
                .setPowResponse(Keyexchange.PoWResponseUnion.newBuilder()
                        .build())
                .setCryptoResponse(Keyexchange.CryptoResponseUnion.newBuilder()
                        .build())
                .build();

        byte[] clientResponsePlaintextBytes = clientResponsePlaintext.toByteArray();
        length = 4 + clientResponsePlaintextBytes.length;
        out.writeInt(length);
        out.write(clientResponsePlaintextBytes);
        out.flush();

        try {
            byte[] scrap = new byte[4];
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(1));
            int read = in.read(scrap);
            if (read == scrap.length) {
                length = (scrap[0] << 24) | (scrap[1] << 16) | (scrap[2] << 8) | (scrap[3] & 0xFF);
                byte[] payload = new byte[length - 4];
                in.readFully(payload);
                Keyexchange.APLoginFailed failed = Keyexchange.APResponseMessage.parseFrom(payload).getLoginFailed();
                throw new SpotifyAuthenticationException(failed);
            } else if (read > 0) {
                throw new IllegalStateException("Read unknown data!");
            }
        } catch (SocketTimeoutException ignored) {
        } finally {
            socket.setSoTimeout(0);
        }


        // Init Shannon chiper

        chiperPair = new ChiperPair(Arrays.copyOfRange(data.toByteArray(), 0x14, 0x34),
                Arrays.copyOfRange(data.toByteArray(), 0x34, 0x54));

        LOGGER.info("Connected successfully!");
    }

    @NotNull
    public Authentication.APWelcome authenticateUserPass(@NotNull String username, @NotNull String password) throws SpotifyAuthenticationException, GeneralSecurityException, IOException {
        return authenticate(Authentication.LoginCredentials.newBuilder()
                .setUsername(username)
                .setTyp(Authentication.AuthenticationType.AUTHENTICATION_USER_PASS)
                .setAuthData(ByteString.copyFromUtf8(password))
                .build());
    }

    public void authenticateUserPassAsync(@NotNull String username, @NotNull String password, OnResult<Authentication.APWelcome> listener) {
        authenticateAsync(Authentication.LoginCredentials.newBuilder()
                .setUsername(username)
                .setTyp(Authentication.AuthenticationType.AUTHENTICATION_USER_PASS)
                .setAuthData(ByteString.copyFromUtf8(password))
                .build(), listener);
    }

    public void authenticateAsync(@NotNull Authentication.LoginCredentials credentials, OnResult<Authentication.APWelcome> listener) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.result(authenticate(credentials));
                } catch (IOException | GeneralSecurityException | SpotifyAuthenticationException ex) {
                    listener.failed(ex);
                }
            }
        });
    }

    @NotNull
    public Authentication.APWelcome authenticate(@NotNull Authentication.LoginCredentials credentials) throws IOException, GeneralSecurityException, SpotifyAuthenticationException {
        if (chiperPair == null) throw new IllegalStateException("Connection not established!");

        Authentication.ClientResponseEncrypted clientResponseEncrypted = Authentication.ClientResponseEncrypted.newBuilder()
                .setLoginCredentials(credentials)
                .setSystemInfo(Authentication.SystemInfo.newBuilder()
                        .setOs(Authentication.Os.OS_UNKNOWN)
                        .setCpuFamily(Authentication.CpuFamily.CPU_UNKNOWN)
                        .setSystemInformationString(Version.systemInfoString())
                        .setDeviceId(deviceId)
                        .build())
                .setVersionString(Version.versionString())
                .build();

        send(Packet.Type.Login, clientResponseEncrypted.toByteArray());

        Packet packet = chiperPair.receiveEncoded(in);
        if (packet.type() == Packet.Type.APWelcome) {
            apWelcome = Authentication.APWelcome.parseFrom(packet.payload);
            authenticatedSuccessfully();
            return apWelcome;
        } else if (packet.type() == Packet.Type.AuthFailure) {
            throw new SpotifyAuthenticationException(Keyexchange.APLoginFailed.parseFrom(packet.payload));
        } else {
            throw new IllegalStateException("Unknown CMD 0x" + Integer.toHexString(packet.cmd));
        }
    }

    private void authenticatedSuccessfully() {
        LOGGER.info(String.format("Authenticated as %s!", apWelcome.getCanonicalUsername()));

        mercuryClient = new MercuryClient(this);

        receiver = new Receiver();
        new Thread(receiver).start();
    }

    @Override
    public void close() throws Exception {
        receiver.stop();
        socket.close();
    }

    public void send(Packet.Type cmd, byte[] payload) throws IOException {
        chiperPair.sendEncoded(out, cmd.val, payload);
    }

    @NotNull
    public MercuryClient mercury() {
        if (mercuryClient == null) throw new IllegalStateException("Session isn't authenticated!");
        return mercuryClient;
    }

    public interface OnResult<R> {
        void result(@NotNull R result);

        void failed(@NotNull Exception ex);
    }

    public interface OnSuccess {
        void success();

        void failed(@NotNull Exception ex);
    }

    public static class SpotifyAuthenticationException extends Exception {
        private SpotifyAuthenticationException(Keyexchange.APLoginFailed loginFailed) {
            super(loginFailed.getErrorCode().name());
        }
    }

    private static class Accumulator extends DataOutputStream {
        private byte[] bytes;

        Accumulator() {
            super(new ByteArrayOutputStream());
        }

        void dump() throws IOException {
            bytes = ((ByteArrayOutputStream) this.out).toByteArray();
            close();
        }

        @NotNull
        byte[] array() {
            return bytes;
        }
    }

    private class Receiver implements Runnable {
        private volatile boolean shouldStop = false;

        private Receiver() {
        }

        void stop() {
            shouldStop = true;
        }

        @Override
        public void run() {
            try {
                while (!shouldStop) {
                    Packet packet = chiperPair.receiveEncoded(in);
                    Packet.Type cmd = Packet.Type.parse(packet.cmd);
                    if (cmd == null) {
                        LOGGER.info("Skipping unknown CMD 0x" + Integer.toHexString(packet.cmd));
                        continue;
                    }

                    switch (cmd) {
                        case Ping:
                            send(Packet.Type.Pong, packet.payload);
                            LOGGER.trace("Handled Ping");
                            break;
                        case PongAck:
                            LOGGER.trace("Handled PongAck");
                            break;
                        case CountryCode:
                            LOGGER.info("Received CountryCode: " + new String(packet.payload));
                            break;
                        case LicenseVersion:
                            ByteBuffer licenseVersion = ByteBuffer.wrap(packet.payload);
                            short id = licenseVersion.getShort();
                            byte[] buffer = new byte[licenseVersion.get()];
                            licenseVersion.get(buffer);
                            LOGGER.info(String.format("Received LicenseVersion: %d, %s", id, new String(buffer)));
                            break;
                        case MercuryReq:
                            mercuryClient.handle(packet);
                            break;
                        default:
                            LOGGER.info("Skipping " + cmd.name());
                            break;
                    }
                }
            } catch (IOException | GeneralSecurityException ex) {
                LOGGER.fatal("Failed handling packet!", ex);
            }
        }
    }
}
