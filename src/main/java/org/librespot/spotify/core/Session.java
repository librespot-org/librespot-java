package org.librespot.spotify.core;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.Version;
import org.librespot.spotify.crypto.ChiperPair;
import org.librespot.spotify.crypto.DiffieHellman;
import org.librespot.spotify.crypto.PBKDF2;
import org.librespot.spotify.crypto.Packet;
import org.librespot.spotify.mercury.MercuryClient;
import org.librespot.spotify.proto.Authentication;
import org.librespot.spotify.proto.Keyexchange;

import javax.crypto.Cipher;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author Gianlu
 */
public class Session implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(Session.class);
    final DiffieHellman keys;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Inner inner;
    private ChiperPair chiperPair;
    private Receiver receiver;
    private Authentication.APWelcome apWelcome = null;
    private MercuryClient mercuryClient;

    private Session(Inner inner, Socket socket) throws IOException {
        this.inner = inner;
        this.socket = socket;
        this.keys = new DiffieHellman(inner.random);

        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());

        LOGGER.info(String.format("Created new session! {deviceId: %s, ap: %s} ", inner.deviceId, socket.getInetAddress()));
    }

    private static int readBlobInt(ByteBuffer buffer) {
        int lo = buffer.get();
        if ((lo & 0x80) == 0) return lo;
        int hi = buffer.get();
        return lo & 0x7f | hi << 7;
    }

    private void connect() throws IOException, GeneralSecurityException, SpotifyAuthenticationException {
        Accumulator acc = new Accumulator();

        // Send ClientHello

        byte[] nonce = new byte[0x10];
        inner.random.nextBytes(nonce);

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

    private void authenticate(@NotNull Authentication.LoginCredentials credentials) throws IOException, GeneralSecurityException, SpotifyAuthenticationException {
        if (chiperPair == null) throw new IllegalStateException("Connection not established!");

        Authentication.ClientResponseEncrypted clientResponseEncrypted = Authentication.ClientResponseEncrypted.newBuilder()
                .setLoginCredentials(credentials)
                .setSystemInfo(Authentication.SystemInfo.newBuilder()
                        .setOs(Authentication.Os.OS_UNKNOWN)
                        .setCpuFamily(Authentication.CpuFamily.CPU_UNKNOWN)
                        .setSystemInformationString(Version.systemInfoString())
                        .setDeviceId(inner.deviceId)
                        .build())
                .setVersionString(Version.versionString())
                .build();

        send(Packet.Type.Login, clientResponseEncrypted.toByteArray());

        Packet packet = chiperPair.receiveEncoded(in);
        if (packet.is(Packet.Type.APWelcome)) {
            apWelcome = Authentication.APWelcome.parseFrom(packet.payload);
            authenticatedSuccessfully();
        } else if (packet.is(Packet.Type.AuthFailure)) {
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

    @NotNull
    public Authentication.APWelcome apWelcome() {
        if (apWelcome == null) throw new IllegalStateException("Session isn't authenticated!");
        return apWelcome;
    }

    @NotNull
    public String deviceId() {
        return inner.deviceId;
    }

    @NotNull
    public DeviceType deviceType() {
        return inner.deviceType;
    }

    public enum DeviceType {
        Unknown(0, "unknown"),
        Computer(1, "computer"),
        Tablet(2, "tablet"),
        Smartphone(3, "smartphone"),
        Speaker(4, "speaker"),
        TV(5, "tv"),
        AVR(6, "avr"),
        STB(7, "stb"),
        AudioDongle(8, "audiodongle");

        public final int val;
        public final String name;

        DeviceType(int val, String name) {
            this.val = val;
            this.name = name;
        }
    }

    static class Inner {
        final DeviceType deviceType;
        final String deviceName;
        final SecureRandom random;

        final String deviceId;

        private Inner(DeviceType deviceType, String deviceName) {
            this.deviceType = deviceType;
            this.deviceName = deviceName;
            this.random = new SecureRandom();
            this.deviceId = UUID.randomUUID().toString();
        }

        @NotNull Authentication.LoginCredentials decryptBlob(String username, byte[] encryptedBlob) throws GeneralSecurityException, IOException {
            encryptedBlob = Base64.getDecoder().decode(encryptedBlob);

            byte[] secret = MessageDigest.getInstance("SHA-1").digest(deviceId.getBytes());
            byte[] baseKey = PBKDF2.HmacSHA1(secret, username.getBytes(), 0x100, 20);

            byte[] key = ByteBuffer.allocate(24)
                    .put(MessageDigest.getInstance("SHA-1").digest(baseKey))
                    .putInt(20)
                    .array();

            Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            byte[] decryptedBlob = aes.doFinal(encryptedBlob);

            int l = decryptedBlob.length;
            for (int i = 0; i < l - 0x10; i++) {
                decryptedBlob[l - i - 1] ^= decryptedBlob[l - i - 0x11];
            }

            ByteBuffer blob = ByteBuffer.wrap(decryptedBlob);
            blob.get();
            int len = readBlobInt(blob);
            blob.get(new byte[len]);
            blob.get();

            int typeInt = readBlobInt(blob);
            Authentication.AuthenticationType type = Authentication.AuthenticationType.forNumber(typeInt);
            if (type == null) {
                throw new IOException(new IllegalArgumentException("Unknown AuthenticationType: " + typeInt));
            }

            blob.get();

            len = readBlobInt(blob);
            byte[] authData = new byte[len];
            blob.get(authData);

            return Authentication.LoginCredentials.newBuilder()
                    .setUsername(username)
                    .setTyp(type)
                    .setAuthData(ByteString.copyFrom(authData))
                    .build();
        }
    }

    public static class Builder {
        private final Inner inner;
        private Authentication.LoginCredentials loginCredentials = null;

        public Builder(@NotNull DeviceType deviceType, @NotNull String deviceName) {
            this.inner = new Inner(deviceType, deviceName);
        }

        public Builder zeroconf() throws InterruptedException, IOException {
            try (ZeroconfAuthenticator authenticator = new ZeroconfAuthenticator(inner)) {
                loginCredentials = authenticator.lockUntilCredentials();
                return this;
            }
        }

        public Builder blob(String username, byte[] blob) throws GeneralSecurityException, IOException {
            loginCredentials = inner.decryptBlob(username, blob);
            return this;
        }

        public Builder userPass(@NotNull String username, @NotNull String password) {
            loginCredentials = Authentication.LoginCredentials.newBuilder()
                    .setUsername(username)
                    .setTyp(Authentication.AuthenticationType.AUTHENTICATION_USER_PASS)
                    .setAuthData(ByteString.copyFromUtf8(password))
                    .build();
            return this;
        }

        @NotNull
        public Session create() throws IOException, GeneralSecurityException, SpotifyAuthenticationException {
            if (loginCredentials == null) throw new IllegalStateException("Missing credentials!");

            Session session = new Session(inner, ApResolver.getSocketFromRandomAccessPoint());
            session.connect();
            session.authenticate(loginCredentials);
            return session;
        }
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
                        case MercurySub:
                        case MercuryUnsub:
                        case MercurySubEvent:
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
