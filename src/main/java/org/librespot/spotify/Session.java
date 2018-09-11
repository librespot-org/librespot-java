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

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.RC2ParameterSpec;
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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author Gianlu
 */
public class Session implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(Session.class);
    private final DeviceType deviceType;
    private final String deviceName;
    private final Socket socket;
    private final DiffieHellman keys;
    private final SecureRandom random;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final String deviceId;
    private ChiperPair chiperPair;
    private Receiver receiver;
    private Authentication.APWelcome apWelcome = null;
    private MercuryClient mercuryClient;

    private Session(DeviceType deviceType, String deviceName, Socket socket) throws IOException {
        this.deviceType = deviceType;
        this.deviceName = deviceName;
        this.socket = socket;
        this.random = new SecureRandom();
        this.keys = new DiffieHellman(random);

        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());

        this.deviceId = UUID.randomUUID().toString();

        LOGGER.info(String.format("Created new session! {deviceId: %s, ap: %s} ", deviceId, socket.getInetAddress()));
    }

    @NotNull
    public static Session create(@NotNull DeviceType deviceType, @NotNull String deviceName) throws IOException {
        return new Session(deviceType, deviceName, ApResolver.getSocketFromRandomAccessPoint());
    }

    @NotNull
    public String deviceName() {
        return deviceName;
    }

    @NotNull
    public DeviceType deviceType() {
        return deviceType;
    }

    @NotNull
    public DiffieHellman keys() {
        return keys;
    }

    @NotNull
    public ZeroconfAuthenticator authenticateZeroconf() throws IOException {
        return new ZeroconfAuthenticator(this);
    }

    @NotNull
    public String deviceId() {
        return deviceId;
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


    public void authenticateBlob(@NotNull String username, @NotNull byte[] encryptedBlob) throws GeneralSecurityException {
        encryptedBlob = Base64.getDecoder().decode(encryptedBlob);

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(deviceId.getBytes());
        byte[] secret = sha1.digest();

        String secretStr = new String(secret);
        char[] secretChars = new char[secretStr.length()];
        secretStr.getChars(0, secretStr.length(), secretChars, 0);

        byte[] pbkdf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(new PBEKeySpec(secretChars, username.getBytes(), 0x100, 20 * 8))
                .getEncoded();

        assert pbkdf.length == 20;

        sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(pbkdf);

        ByteBuffer keyBuffer = ByteBuffer.allocate(24);
        keyBuffer.put(sha1.digest())
                .putInt(20);
        byte[] key = keyBuffer.array();


        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new RC2ParameterSpec(192));
        byte[] decryptedBlob = aes.doFinal(encryptedBlob);

        assert decryptedBlob.length == encryptedBlob.length;

        int l = decryptedBlob.length;
        for (int i = 0; i < l - 0x10; i++) {
            decryptedBlob[l - i - 1] ^= decryptedBlob[l - i - 0x11];
        }

        ByteBuffer blob = ByteBuffer.wrap(decryptedBlob);
        blob.get();
        int len = readIntBlob(blob);
        System.out.println("LEN: " + len);
        blob.get(new byte[len]);
        blob.get();

        Authentication.AuthenticationType type = Authentication.AuthenticationType.forNumber(readIntBlob(blob));
        System.out.println("TYPE: " + type);
        blob.get();

        len = readIntBlob(blob);
        System.out.println("LEN: " + len);
        byte[] authData = new byte[len];
        blob.get(authData);

        System.out.println("GOT HERE");
    }

    private int readIntBlob(ByteBuffer buffer) {
        int lo = buffer.get();
        if ((lo & 0x80) == 0) return lo;
        int hi = buffer.get();
        return lo & 0x7f | hi << 7;
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

    @NotNull
    public Authentication.APWelcome apWelcome() {
        if (apWelcome == null) throw new IllegalStateException("Session isn't authenticated!");
        return apWelcome;
    }

    @NotNull
    public Random random() {
        return random;
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

        public final String name;

        DeviceType(int i, String name) {
            this.name = name;
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
