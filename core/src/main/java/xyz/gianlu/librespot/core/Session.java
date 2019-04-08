package xyz.gianlu.librespot.core;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.AbsConfiguration;
import xyz.gianlu.librespot.Version;
import xyz.gianlu.librespot.cache.CacheManager;
import xyz.gianlu.librespot.cdn.CdnManager;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Authentication;
import xyz.gianlu.librespot.common.proto.Keyexchange;
import xyz.gianlu.librespot.crypto.CipherPair;
import xyz.gianlu.librespot.crypto.DiffieHellman;
import xyz.gianlu.librespot.crypto.PBKDF2;
import xyz.gianlu.librespot.crypto.Packet;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.player.AudioKeyManager;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.feeders.storage.ChannelManager;
import xyz.gianlu.librespot.spirc.SpotifyIrc;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.math.BigInteger;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Gianlu
 */
public class Session implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(Session.class);
    private static final String PREFERRED_LOCALE = "en";
    private final DiffieHellman keys;
    private final Inner inner;
    private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory(r -> "handle-packet-" + r.hashCode()));
    private final AtomicBoolean authLock = new AtomicBoolean(false);
    private ConnectionHolder conn;
    private CipherPair cipherPair;
    private Receiver receiver;
    private Authentication.APWelcome apWelcome = null;
    private MercuryClient mercuryClient;
    private SpotifyIrc spirc;
    private Player player;
    private AudioKeyManager audioKeyManager;
    private ChannelManager channelManager;
    private TokenProvider tokenProvider;
    private CdnManager cdnManager;
    private CacheManager cacheManager;
    private String countryCode = null;

    private Session(Inner inner, Socket socket) throws IOException {
        this.inner = inner;
        this.keys = new DiffieHellman(inner.random);
        this.conn = new ConnectionHolder(socket);

        LOGGER.info(String.format("Created new session! {deviceId: %s, ap: %s} ", inner.deviceId, socket.getInetAddress()));
    }

    private static int readBlobInt(ByteBuffer buffer) {
        int lo = buffer.get();
        if ((lo & 0x80) == 0) return lo;
        int hi = buffer.get();
        return lo & 0x7f | hi << 7;
    }

    @NotNull
    static Session from(@NotNull Inner inner) throws IOException {
        return new Session(inner, ApResolver.getSocketFromRandomAccessPoint());
    }

    void connect() throws IOException, GeneralSecurityException, SpotifyAuthenticationException {
        Accumulator acc = new Accumulator();

        // Send ClientHello

        byte[] nonce = new byte[0x10];
        inner.random.nextBytes(nonce);

        Keyexchange.ClientHello clientHello = Keyexchange.ClientHello.newBuilder()
                .setBuildInfo(Keyexchange.BuildInfo.newBuilder()
                        .setProduct(Keyexchange.Product.PRODUCT_PARTNER)
                        .setPlatform(Keyexchange.Platform.PLATFORM_LINUX_X86)
                        .setVersion(110100348)
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
        conn.out.writeByte(0);
        conn.out.writeByte(4);
        conn.out.writeInt(length);
        conn.out.write(clientHelloBytes);
        conn.out.flush();

        acc.writeByte(0);
        acc.writeByte(4);
        acc.writeInt(length);
        acc.write(clientHelloBytes);


        // Read APResponseMessage

        length = conn.in.readInt();
        acc.writeInt(length);
        byte[] buffer = new byte[length - 4];
        conn.in.readFully(buffer);
        acc.write(buffer);
        acc.dump();

        Keyexchange.APResponseMessage apResponseMessage = Keyexchange.APResponseMessage.parseFrom(buffer);
        byte[] sharedKey = Utils.toByteArray(keys.computeSharedKey(apResponseMessage.getChallenge().getLoginCryptoChallenge().getDiffieHellman().getGs().toByteArray()));


        // Solve challenge

        ByteArrayOutputStream data = new ByteArrayOutputStream(0x64);

        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(sharedKey, "HmacSHA1"));
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
        conn.out.writeInt(length);
        conn.out.write(clientResponsePlaintextBytes);
        conn.out.flush();

        try {
            byte[] scrap = new byte[4];
            conn.socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(1));
            int read = conn.in.read(scrap);
            if (read == scrap.length) {
                length = (scrap[0] << 24) | (scrap[1] << 16) | (scrap[2] << 8) | (scrap[3] & 0xFF);
                byte[] payload = new byte[length - 4];
                conn.in.readFully(payload);
                Keyexchange.APLoginFailed failed = Keyexchange.APResponseMessage.parseFrom(payload).getLoginFailed();
                throw new SpotifyAuthenticationException(failed);
            } else if (read > 0) {
                throw new IllegalStateException("Read unknown data!");
            }
        } catch (SocketTimeoutException ignored) {
        } finally {
            conn.socket.setSoTimeout(0);
        }


        // Init Shannon cipher

        cipherPair = new CipherPair(Arrays.copyOfRange(data.toByteArray(), 0x14, 0x34),
                Arrays.copyOfRange(data.toByteArray(), 0x34, 0x54));

        synchronized (authLock) {
            authLock.set(true);
        }

        LOGGER.info("Connected successfully!");
    }

    void authenticate(@NotNull Authentication.LoginCredentials credentials) throws IOException, GeneralSecurityException, SpotifyAuthenticationException, SpotifyIrc.IrcException {
        authenticatePartial(credentials);

        mercuryClient = new MercuryClient(this);
        tokenProvider = new TokenProvider(this);
        audioKeyManager = new AudioKeyManager(this);
        channelManager = new ChannelManager(this);
        cdnManager = new CdnManager(this);
        cacheManager = new CacheManager(inner.configuration);
        spirc = new SpotifyIrc(this);
        spirc.sayHello();
        player = new Player(inner.configuration, this);

        LOGGER.info(String.format("Authenticated as %s!", apWelcome.getCanonicalUsername()));
    }

    private void authenticatePartial(@NotNull Authentication.LoginCredentials credentials) throws IOException, GeneralSecurityException, SpotifyAuthenticationException {
        if (cipherPair == null) throw new IllegalStateException("Connection not established!");

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

        sendUnchecked(Packet.Type.Login, clientResponseEncrypted.toByteArray());

        Packet packet = cipherPair.receiveEncoded(conn.in);
        if (packet.is(Packet.Type.APWelcome)) {
            apWelcome = Authentication.APWelcome.parseFrom(packet.payload);

            receiver = new Receiver();
            new Thread(receiver, "session-packet-receiver").start();

            byte[] bytes0x0f = new byte[20];
            random().nextBytes(bytes0x0f);
            sendUnchecked(Packet.Type.Unknown_0x0f, bytes0x0f);

            ByteBuffer preferredLocale = ByteBuffer.allocate(18 + 5);
            preferredLocale.put((byte) 0x0).put((byte) 0x0).put((byte) 0x10).put((byte) 0x0).put((byte) 0x02);
            preferredLocale.put("preferred-locale".getBytes());
            preferredLocale.put(PREFERRED_LOCALE.getBytes());
            sendUnchecked(Packet.Type.PreferredLocale, preferredLocale.array());

            synchronized (authLock) {
                authLock.set(false);
                authLock.notifyAll();
            }
        } else if (packet.is(Packet.Type.AuthFailure)) {
            throw new SpotifyAuthenticationException(Keyexchange.APLoginFailed.parseFrom(packet.payload));
        } else {
            throw new IllegalStateException("Unknown CMD 0x" + Integer.toHexString(packet.cmd));
        }
    }

    @Override
    public void close() throws IOException {
        receiver.stop();
        receiver = null;

        player.close();
        player = null;

        audioKeyManager.close();
        audioKeyManager = null;

        channelManager.close();
        channelManager = null;

        spirc.close();
        spirc = null;

        mercuryClient.close();
        mercuryClient = null;

        executorService.shutdown();
        conn.socket.close();

        apWelcome = null;
        cipherPair = null;

        LOGGER.info(String.format("Closed session. {deviceId: %s, ap: %s} ", inner.deviceId, conn.socket.getInetAddress()));
    }

    private void sendUnchecked(Packet.Type cmd, byte[] payload) throws IOException {
        cipherPair.sendEncoded(conn.out, cmd.val, payload);
    }

    private void waitAuthLock() {
        synchronized (authLock) {
            if (cipherPair == null || authLock.get()) {
                try {
                    authLock.wait();
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    public void send(Packet.Type cmd, byte[] payload) throws IOException {
        waitAuthLock();
        sendUnchecked(cmd, payload);
    }

    @NotNull
    public MercuryClient mercury() {
        waitAuthLock();
        if (mercuryClient == null) throw new IllegalStateException("Session isn't authenticated!");
        return mercuryClient;
    }

    @NotNull
    public AudioKeyManager audioKey() {
        waitAuthLock();
        if (audioKeyManager == null) throw new IllegalStateException("Session isn't authenticated!");
        return audioKeyManager;
    }

    @NotNull
    public CacheManager cache() {
        waitAuthLock();
        if (cacheManager == null) throw new IllegalStateException("Session isn't authenticated!");
        return cacheManager;
    }

    @NotNull
    public CdnManager cdn() {
        waitAuthLock();
        if (cdnManager == null) throw new IllegalStateException("Session isn't authenticated!");
        return cdnManager;
    }

    @NotNull
    public ChannelManager channel() {
        waitAuthLock();
        if (channelManager == null) throw new IllegalStateException("Session isn't authenticated!");
        return channelManager;
    }

    @NotNull
    public TokenProvider tokens() {
        waitAuthLock();
        if (tokenProvider == null) throw new IllegalStateException("Session isn't authenticated!");
        return tokenProvider;
    }

    @NotNull
    public SpotifyIrc spirc() {
        waitAuthLock();
        if (spirc == null) throw new IllegalStateException("Session isn't authenticated!");
        return spirc;
    }

    @NotNull
    public Player player() {
        waitAuthLock();
        if (player == null) throw new IllegalStateException("Session isn't authenticated!");
        return player;
    }

    @NotNull
    public Authentication.APWelcome apWelcome() {
        waitAuthLock();
        if (apWelcome == null) throw new IllegalStateException("Session isn't authenticated!");
        return apWelcome;
    }

    public boolean valid() {
        waitAuthLock();
        return apWelcome != null && conn != null && !conn.socket.isClosed();
    }

    @NotNull
    public String deviceId() {
        return inner.deviceId;
    }

    @NotNull
    public DeviceType deviceType() {
        return inner.deviceType;
    }

    @NotNull
    ExecutorService executor() {
        return executorService;
    }

    @NotNull
    public String deviceName() {
        return inner.deviceName;
    }

    @NotNull
    public Random random() {
        return inner.random;
    }

    private void reconnect() {
        try {
            if (conn != null) {
                conn.socket.close();
                receiver.stop();
            }

            conn = new ConnectionHolder(ApResolver.getSocketFromRandomAccessPoint());
            connect();
            authenticatePartial(Authentication.LoginCredentials.newBuilder()
                    .setUsername(apWelcome.getCanonicalUsername())
                    .setTyp(apWelcome.getReusableAuthCredentialsType())
                    .setAuthData(apWelcome.getReusableAuthCredentials())
                    .build());

            spirc.sayHello();

            LOGGER.info(String.format("Re-authenticated as %s!", apWelcome.getCanonicalUsername()));
        } catch (IOException | GeneralSecurityException | SpotifyAuthenticationException | SpotifyIrc.IrcException ex) {
            throw new RuntimeException("Failed reconnecting!", ex);
        }
    }

    @NotNull
    public AbsConfiguration conf() {
        return inner.configuration;
    }

    @Nullable
    public String countryCode() {
        return countryCode;
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
        final AbsConfiguration configuration;

        private Inner(DeviceType deviceType, String deviceName, AbsConfiguration configuration) {
            this.deviceType = deviceType;
            this.deviceName = deviceName;
            this.configuration = configuration;
            this.random = new SecureRandom();
            this.deviceId = UUID.randomUUID().toString();
        }

        @NotNull
        static Inner from(@NotNull AbsConfiguration configuration) {
            String deviceName = configuration.deviceName();
            if (deviceName == null || deviceName.isEmpty())
                throw new IllegalArgumentException("Device name required: " + deviceName);

            DeviceType deviceType = configuration.deviceType();
            if (deviceType == null)
                throw new IllegalArgumentException("Device type required!");

            return new Inner(deviceType, deviceName, configuration);
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
            for (int i = 0; i < l - 0x10; i++)
                decryptedBlob[l - i - 1] ^= decryptedBlob[l - i - 0x11];

            ByteBuffer blob = ByteBuffer.wrap(decryptedBlob);
            blob.get();
            int len = readBlobInt(blob);
            blob.get(new byte[len]);
            blob.get();

            int typeInt = readBlobInt(blob);
            Authentication.AuthenticationType type = Authentication.AuthenticationType.forNumber(typeInt);
            if (type == null)
                throw new IOException(new IllegalArgumentException("Unknown AuthenticationType: " + typeInt));

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
        private AuthConfiguration authConf;

        public Builder(@NotNull DeviceType deviceType, @NotNull String deviceName, @NotNull AbsConfiguration configuration) {
            this.inner = new Inner(deviceType, deviceName, configuration);
            this.authConf = configuration;
        }

        public Builder(@NotNull AbsConfiguration configuration) {
            this.inner = Inner.from(configuration);
            this.authConf = configuration;
        }

        @NotNull
        public Builder facebook() throws IOException {
            try (FacebookAuthenticator authenticator = new FacebookAuthenticator()) {
                loginCredentials = authenticator.lockUntilCredentials();
                return this;
            } catch (InterruptedException ex) {
                throw new IOException(ex);
            }
        }

        @NotNull
        public Builder blob(String username, byte[] blob) throws GeneralSecurityException, IOException {
            loginCredentials = inner.decryptBlob(username, blob);
            return this;
        }

        @NotNull
        public Builder userPass(@NotNull String username, @NotNull String password) {
            loginCredentials = Authentication.LoginCredentials.newBuilder()
                    .setUsername(username)
                    .setTyp(Authentication.AuthenticationType.AUTHENTICATION_USER_PASS)
                    .setAuthData(ByteString.copyFromUtf8(password))
                    .build();
            return this;
        }

        @NotNull
        public Session create() throws IOException, GeneralSecurityException, SpotifyAuthenticationException, SpotifyIrc.IrcException {
            if (loginCredentials == null) {
                if (authConf != null) {
                    String blob = authConf.authBlob();
                    String username = authConf.authUsername();
                    String password = authConf.authPassword();

                    switch (authConf.authStrategy()) {
                        case FACEBOOK:
                            facebook();
                            break;
                        case BLOB:
                            if (username == null) throw new IllegalArgumentException("Missing authUsername!");
                            if (blob == null) throw new IllegalArgumentException("Missing authBlob!");
                            blob(username, Base64.getDecoder().decode(blob));
                            break;
                        case USER_PASS:
                            if (username == null) throw new IllegalArgumentException("Missing authUsername!");
                            if (password == null) throw new IllegalArgumentException("Missing authPassword!");
                            userPass(username, password);
                            break;
                        case ZEROCONF:
                            throw new IllegalStateException("Cannot handle ZEROCONF! Use ZeroconfServer.");
                        default:
                            throw new IllegalStateException("Unknown auth authStrategy: " + authConf.authStrategy());
                    }
                } else {
                    throw new IllegalStateException("Missing credentials!");
                }
            }

            Session session = Session.from(inner);
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
            this.close();
        }

        @NotNull
        byte[] array() {
            return bytes;
        }
    }

    private class ConnectionHolder {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;

        ConnectionHolder(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = new DataOutputStream(socket.getOutputStream());
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
            while (!shouldStop) {
                Packet packet;
                Packet.Type cmd;
                try {
                    packet = cipherPair.receiveEncoded(conn.in);
                    cmd = Packet.Type.parse(packet.cmd);
                    if (cmd == null) {
                        LOGGER.info(String.format("Skipping unknown command {cmd: 0x%s, payload: %s}", Integer.toHexString(packet.cmd), Utils.bytesToHex(packet.payload)));
                        continue;
                    }
                } catch (IOException | GeneralSecurityException ex) {
                    if (!shouldStop) {
                        LOGGER.fatal("Failed reading packet!", ex);
                        reconnect();
                    }

                    return;
                }

                switch (cmd) {
                    case Ping:
                        try {
                            long serverTime = new BigInteger(packet.payload).longValue();
                            TimeProvider.init((int) (serverTime - System.currentTimeMillis() / 1000));
                            send(Packet.Type.Pong, packet.payload);
                            LOGGER.trace(String.format("Handled Ping {payload: %s}", Utils.bytesToHex(packet.payload)));
                        } catch (IOException ex) {
                            LOGGER.fatal("Failed sending Pong!", ex);
                        }
                        break;
                    case PongAck:
                        LOGGER.trace(String.format("Handled PongAck {payload: %s}", Utils.bytesToHex(packet.payload)));
                        break;
                    case CountryCode:
                        countryCode = new String(packet.payload);
                        LOGGER.info("Received CountryCode: " + countryCode);
                        break;
                    case LicenseVersion:
                        ByteBuffer licenseVersion = ByteBuffer.wrap(packet.payload);
                        short id = licenseVersion.getShort();
                        byte[] buffer = new byte[licenseVersion.get()];
                        licenseVersion.get(buffer);
                        LOGGER.info(String.format("Received LicenseVersion: %d, %s", id, new String(buffer)));
                        break;
                    case Unknown_0x10:
                        LOGGER.debug("Received 0x10: " + Utils.bytesToHex(packet.payload));
                        break;
                    case MercurySub:
                    case MercuryUnsub:
                    case MercuryEvent:
                    case MercuryReq:
                        mercuryClient.dispatch(packet);
                        break;
                    case AesKey:
                    case AesKeyError:
                        audioKeyManager.dispatch(packet);
                        break;
                    case ChannelError:
                    case StreamChunkRes:
                        channelManager.dispatch(packet);
                        break;
                    default:
                        LOGGER.info("Skipping " + cmd.name());
                        break;
                }
            }
        }
    }
}
