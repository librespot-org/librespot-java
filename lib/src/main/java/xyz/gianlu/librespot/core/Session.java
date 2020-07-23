package xyz.gianlu.librespot.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import com.spotify.Authentication;
import com.spotify.Keyexchange;
import com.spotify.connectstate.Connect;
import com.spotify.explicit.ExplicitContentPubsub;
import com.spotify.explicit.ExplicitContentPubsub.UserAttributesUpdate;
import okhttp3.Authenticator;
import okhttp3.*;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import xyz.gianlu.librespot.Version;
import xyz.gianlu.librespot.audio.AudioKeyManager;
import xyz.gianlu.librespot.audio.PlayableContentFeeder;
import xyz.gianlu.librespot.audio.cdn.CdnManager;
import xyz.gianlu.librespot.audio.storage.ChannelManager;
import xyz.gianlu.librespot.cache.CacheManager;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.crypto.CipherPair;
import xyz.gianlu.librespot.crypto.DiffieHellman;
import xyz.gianlu.librespot.crypto.PBKDF2;
import xyz.gianlu.librespot.crypto.Packet;
import xyz.gianlu.librespot.dealer.ApiClient;
import xyz.gianlu.librespot.dealer.DealerClient;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.SubListener;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Gianlu
 */
public final class Session implements Closeable, SubListener, DealerClient.MessageListener {
    private static final Logger LOGGER = LogManager.getLogger(Session.class);
    private static final byte[] serverKey = new byte[]{
            (byte) 0xac, (byte) 0xe0, (byte) 0x46, (byte) 0x0b, (byte) 0xff, (byte) 0xc2, (byte) 0x30, (byte) 0xaf, (byte) 0xf4, (byte) 0x6b, (byte) 0xfe, (byte) 0xc3,
            (byte) 0xbf, (byte) 0xbf, (byte) 0x86, (byte) 0x3d, (byte) 0xa1, (byte) 0x91, (byte) 0xc6, (byte) 0xcc, (byte) 0x33, (byte) 0x6c, (byte) 0x93, (byte) 0xa1,
            (byte) 0x4f, (byte) 0xb3, (byte) 0xb0, (byte) 0x16, (byte) 0x12, (byte) 0xac, (byte) 0xac, (byte) 0x6a, (byte) 0xf1, (byte) 0x80, (byte) 0xe7, (byte) 0xf6,
            (byte) 0x14, (byte) 0xd9, (byte) 0x42, (byte) 0x9d, (byte) 0xbe, (byte) 0x2e, (byte) 0x34, (byte) 0x66, (byte) 0x43, (byte) 0xe3, (byte) 0x62, (byte) 0xd2,
            (byte) 0x32, (byte) 0x7a, (byte) 0x1a, (byte) 0x0d, (byte) 0x92, (byte) 0x3b, (byte) 0xae, (byte) 0xdd, (byte) 0x14, (byte) 0x02, (byte) 0xb1, (byte) 0x81,
            (byte) 0x55, (byte) 0x05, (byte) 0x61, (byte) 0x04, (byte) 0xd5, (byte) 0x2c, (byte) 0x96, (byte) 0xa4, (byte) 0x4c, (byte) 0x1e, (byte) 0xcc, (byte) 0x02,
            (byte) 0x4a, (byte) 0xd4, (byte) 0xb2, (byte) 0x0c, (byte) 0x00, (byte) 0x1f, (byte) 0x17, (byte) 0xed, (byte) 0xc2, (byte) 0x2f, (byte) 0xc4, (byte) 0x35,
            (byte) 0x21, (byte) 0xc8, (byte) 0xf0, (byte) 0xcb, (byte) 0xae, (byte) 0xd2, (byte) 0xad, (byte) 0xd7, (byte) 0x2b, (byte) 0x0f, (byte) 0x9d, (byte) 0xb3,
            (byte) 0xc5, (byte) 0x32, (byte) 0x1a, (byte) 0x2a, (byte) 0xfe, (byte) 0x59, (byte) 0xf3, (byte) 0x5a, (byte) 0x0d, (byte) 0xac, (byte) 0x68, (byte) 0xf1,
            (byte) 0xfa, (byte) 0x62, (byte) 0x1e, (byte) 0xfb, (byte) 0x2c, (byte) 0x8d, (byte) 0x0c, (byte) 0xb7, (byte) 0x39, (byte) 0x2d, (byte) 0x92, (byte) 0x47,
            (byte) 0xe3, (byte) 0xd7, (byte) 0x35, (byte) 0x1a, (byte) 0x6d, (byte) 0xbd, (byte) 0x24, (byte) 0xc2, (byte) 0xae, (byte) 0x25, (byte) 0x5b, (byte) 0x88,
            (byte) 0xff, (byte) 0xab, (byte) 0x73, (byte) 0x29, (byte) 0x8a, (byte) 0x0b, (byte) 0xcc, (byte) 0xcd, (byte) 0x0c, (byte) 0x58, (byte) 0x67, (byte) 0x31,
            (byte) 0x89, (byte) 0xe8, (byte) 0xbd, (byte) 0x34, (byte) 0x80, (byte) 0x78, (byte) 0x4a, (byte) 0x5f, (byte) 0xc9, (byte) 0x6b, (byte) 0x89, (byte) 0x9d,
            (byte) 0x95, (byte) 0x6b, (byte) 0xfc, (byte) 0x86, (byte) 0xd7, (byte) 0x4f, (byte) 0x33, (byte) 0xa6, (byte) 0x78, (byte) 0x17, (byte) 0x96, (byte) 0xc9,
            (byte) 0xc3, (byte) 0x2d, (byte) 0x0d, (byte) 0x32, (byte) 0xa5, (byte) 0xab, (byte) 0xcd, (byte) 0x05, (byte) 0x27, (byte) 0xe2, (byte) 0xf7, (byte) 0x10,
            (byte) 0xa3, (byte) 0x96, (byte) 0x13, (byte) 0xc4, (byte) 0x2f, (byte) 0x99, (byte) 0xc0, (byte) 0x27, (byte) 0xbf, (byte) 0xed, (byte) 0x04, (byte) 0x9c,
            (byte) 0x3c, (byte) 0x27, (byte) 0x58, (byte) 0x04, (byte) 0xb6, (byte) 0xb2, (byte) 0x19, (byte) 0xf9, (byte) 0xc1, (byte) 0x2f, (byte) 0x02, (byte) 0xe9,
            (byte) 0x48, (byte) 0x63, (byte) 0xec, (byte) 0xa1, (byte) 0xb6, (byte) 0x42, (byte) 0xa0, (byte) 0x9d, (byte) 0x48, (byte) 0x25, (byte) 0xf8, (byte) 0xb3,
            (byte) 0x9d, (byte) 0xd0, (byte) 0xe8, (byte) 0x6a, (byte) 0xf9, (byte) 0x48, (byte) 0x4d, (byte) 0xa1, (byte) 0xc2, (byte) 0xba, (byte) 0x86, (byte) 0x30,
            (byte) 0x42, (byte) 0xea, (byte) 0x9d, (byte) 0xb3, (byte) 0x08, (byte) 0x6c, (byte) 0x19, (byte) 0x0e, (byte) 0x48, (byte) 0xb3, (byte) 0x9d, (byte) 0x66,
            (byte) 0xeb, (byte) 0x00, (byte) 0x06, (byte) 0xa2, (byte) 0x5a, (byte) 0xee, (byte) 0xa1, (byte) 0x1b, (byte) 0x13, (byte) 0x87, (byte) 0x3c, (byte) 0xd7,
            (byte) 0x19, (byte) 0xe6, (byte) 0x55, (byte) 0xbd
    };
    private final DiffieHellman keys;
    private final Inner inner;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new NameThreadFactory(r -> "session-scheduler-" + r.hashCode()));
    private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory(r -> "handle-packet-" + r.hashCode()));
    private final AtomicBoolean authLock = new AtomicBoolean(false);
    private final OkHttpClient client;
    private final List<CloseListener> closeListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<ReconnectionListener> reconnectionListeners = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> userAttributes = Collections.synchronizedMap(new HashMap<>());
    private ConnectionHolder conn;
    private volatile CipherPair cipherPair;
    private Receiver receiver;
    private Authentication.APWelcome apWelcome = null;
    private MercuryClient mercuryClient;
    private AudioKeyManager audioKeyManager;
    private ChannelManager channelManager;
    private TokenProvider tokenProvider;
    private CdnManager cdnManager;
    private CacheManager cacheManager;
    private DealerClient dealer;
    private ApiClient api;
    private SearchManager search;
    private PlayableContentFeeder contentFeeder;
    private EventService eventService;
    private String countryCode = null;
    private volatile boolean closed = false;
    private volatile boolean closing = false;
    private volatile ScheduledFuture<?> scheduledReconnect = null;

    private Session(@NotNull Inner inner, @NotNull String addr) throws IOException {
        this.inner = inner;
        this.keys = new DiffieHellman(inner.random);
        this.conn = ConnectionHolder.create(addr, inner.conf);
        this.client = createClient(inner.conf);

        LOGGER.info("Created new session! {deviceId: {}, ap: {}, proxy: {}} ", inner.deviceId, addr, inner.conf.proxyEnabled);
    }

    @NotNull
    private static OkHttpClient createClient(@NotNull Configuration conf) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.retryOnConnectionFailure(true);

        if (conf.proxyEnabled && conf.proxyType != Proxy.Type.DIRECT) {
            builder.proxy(new Proxy(conf.proxyType, new InetSocketAddress(conf.proxyAddress, conf.proxyPort)));
            if (conf.proxyAuth) {
                builder.proxyAuthenticator(new Authenticator() {
                    final String username = conf.proxyUsername;
                    final String password = conf.proxyPassword;

                    @Override
                    public Request authenticate(Route route, @NotNull Response response) {
                        String credential = Credentials.basic(username, password);
                        return response.request().newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build();
                    }
                });
            }
        }

        builder.addInterceptor(chain -> {
            Request original = chain.request();
            RequestBody body;
            if ((body = original.body()) == null || original.header("Content-Encoding") != null)
                return chain.proceed(original);

            Request compressedRequest = original.newBuilder()
                    .header("Content-Encoding", "gzip")
                    .method(original.method(), new RequestBody() {
                        @Override
                        public MediaType contentType() {
                            return body.contentType();
                        }

                        @Override
                        public long contentLength() {
                            return -1;
                        }

                        @Override
                        public void writeTo(@NotNull BufferedSink sink) throws IOException {
                            try (BufferedSink gzipSink = Okio.buffer(new GzipSink(sink))) {
                                body.writeTo(gzipSink);
                            }
                        }
                    }).build();

            return chain.proceed(compressedRequest);
        });

        return builder.build();
    }

    private static int readBlobInt(ByteBuffer buffer) {
        int lo = buffer.get();
        if ((lo & 0x80) == 0) return lo;
        int hi = buffer.get();
        return lo & 0x7f | hi << 7;
    }

    @NotNull
    public OkHttpClient client() {
        return client;
    }

    private void connect() throws IOException, GeneralSecurityException, SpotifyAuthenticationException {
        Accumulator acc = new Accumulator();

        // Send ClientHello

        byte[] nonce = new byte[0x10];
        inner.random.nextBytes(nonce);

        Keyexchange.ClientHello clientHello = Keyexchange.ClientHello.newBuilder()
                .setBuildInfo(Version.standardBuildInfo())
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


        // Check gs_signature

        KeyFactory factory = KeyFactory.getInstance("RSA");
        PublicKey publicKey = factory.generatePublic(new RSAPublicKeySpec(new BigInteger(1, serverKey), BigInteger.valueOf(65537)));

        Signature sig = Signature.getInstance("SHA1withRSA");
        sig.initVerify(publicKey);
        sig.update(apResponseMessage.getChallenge().getLoginCryptoChallenge().getDiffieHellman().getGs().toByteArray());
        if (!sig.verify(apResponseMessage.getChallenge().getLoginCryptoChallenge().getDiffieHellman().getGsSignature().toByteArray()))
            throw new GeneralSecurityException("Failed signature check!");


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
                                .setHmac(ByteString.copyFrom(challenge)).build())
                        .build())
                .setPowResponse(Keyexchange.PoWResponseUnion.newBuilder().build())
                .setCryptoResponse(Keyexchange.CryptoResponseUnion.newBuilder().build())
                .build();

        byte[] clientResponsePlaintextBytes = clientResponsePlaintext.toByteArray();
        length = 4 + clientResponsePlaintextBytes.length;
        conn.out.writeInt(length);
        conn.out.write(clientResponsePlaintextBytes);
        conn.out.flush();

        try {
            byte[] scrap = new byte[4];
            conn.socket.setSoTimeout(300);
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

        synchronized (authLock) {
            // Init Shannon cipher
            cipherPair = new CipherPair(Arrays.copyOfRange(data.toByteArray(), 0x14, 0x34),
                    Arrays.copyOfRange(data.toByteArray(), 0x34, 0x54));

            authLock.set(true);
        }

        LOGGER.info("Connected successfully!");
    }

    /**
     * Authenticates with the server and creates all the necessary components.
     * All of them should be initialized inside the synchronized block and MUST NOT call any method on this {@link Session} object.
     */
    private void authenticate(@NotNull Authentication.LoginCredentials credentials) throws IOException, GeneralSecurityException, SpotifyAuthenticationException, MercuryClient.MercuryException {
        authenticatePartial(credentials, false);

        synchronized (authLock) {
            mercuryClient = new MercuryClient(this);
            tokenProvider = new TokenProvider(this);
            audioKeyManager = new AudioKeyManager(this);
            channelManager = new ChannelManager(this);
            api = new ApiClient(this);
            cdnManager = new CdnManager(this);
            contentFeeder = new PlayableContentFeeder(this);
            cacheManager = new CacheManager(inner.conf);
            dealer = new DealerClient(this);
            search = new SearchManager(this);
            eventService = new EventService(this);

            authLock.set(false);
            authLock.notifyAll();
        }

        eventService.language(inner.preferredLocale);
        TimeProvider.init(this);
        dealer.connect();

        LOGGER.info("Authenticated as {}!", apWelcome.getCanonicalUsername());
        mercury().interestedIn("spotify:user:attributes:update", this);
        dealer().addMessageListener(this, "hm://connect-state/v1/connect/logout");
    }

    /**
     * Authenticates with the server. Does not create all the components unlike {@link Session#authenticate(Authentication.LoginCredentials)}.
     *
     * @param removeLock Whether {@link Session#authLock} should be released or not.
     *                   {@code false} for {@link Session#authenticate(Authentication.LoginCredentials)},
     *                   {@code true} for {@link Session#reconnect()}.
     */
    private void authenticatePartial(@NotNull Authentication.LoginCredentials credentials, boolean removeLock) throws IOException, GeneralSecurityException, SpotifyAuthenticationException {
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


            byte[] bytes0x0f = new byte[20];
            random().nextBytes(bytes0x0f);
            sendUnchecked(Packet.Type.Unknown_0x0f, bytes0x0f);

            ByteBuffer preferredLocale = ByteBuffer.allocate(18 + 5);
            preferredLocale.put((byte) 0x0).put((byte) 0x0).put((byte) 0x10).put((byte) 0x0).put((byte) 0x02);
            preferredLocale.put("preferred-locale".getBytes());
            preferredLocale.put(inner.preferredLocale.getBytes());
            sendUnchecked(Packet.Type.PreferredLocale, preferredLocale.array());

            if (removeLock) {
                synchronized (authLock) {
                    authLock.set(false);
                    authLock.notifyAll();
                }
            }

            if (inner.conf.storeCredentials) {
                ByteString reusable = apWelcome.getReusableAuthCredentials();
                Authentication.AuthenticationType reusableType = apWelcome.getReusableAuthCredentialsType();

                JsonObject obj = new JsonObject();
                obj.addProperty("username", apWelcome.getCanonicalUsername());
                obj.addProperty("credentials", Utils.toBase64(reusable));
                obj.addProperty("type", reusableType.name());

                if (inner.conf.storedCredentialsFile == null) throw new IllegalArgumentException();
                try (FileOutputStream out = new FileOutputStream(inner.conf.storedCredentialsFile)) {
                    out.write(obj.toString().getBytes());
                }
            }
        } else if (packet.is(Packet.Type.AuthFailure)) {
            throw new SpotifyAuthenticationException(Keyexchange.APLoginFailed.parseFrom(packet.payload));
        } else {
            throw new IllegalStateException("Unknown CMD 0x" + Integer.toHexString(packet.cmd));
        }
    }

    @Override
    public void close() throws IOException {
        LOGGER.info("Closing session. {deviceId: {}}", inner.deviceId);

        closing = true;

        scheduler.shutdownNow();

        if (dealer != null) {
            dealer.close();
            dealer = null;
        }

        if (audioKeyManager != null) {
            audioKeyManager.close();
            audioKeyManager = null;
        }

        if (channelManager != null) {
            channelManager.close();
            channelManager = null;
        }

        if (eventService != null) {
            eventService.close();
            eventService = null;
        }

        if (mercuryClient != null) {
            mercuryClient.close();
            mercuryClient = null;
        }

        if (receiver != null) {
            receiver.stop();
            receiver = null;
        }

        executorService.shutdown();

        client.dispatcher().executorService().shutdownNow();
        client.connectionPool().evictAll();

        if (conn != null) {
            conn.socket.close();
            conn = null;
        }

        synchronized (authLock) {
            apWelcome = null;
            cipherPair = null;
            closed = true;
        }

        synchronized (closeListeners) {
            Iterator<CloseListener> i = closeListeners.iterator();
            while (i.hasNext()) {
                i.next().onClosed();
                i.remove();
            }
        }

        reconnectionListeners.clear();

        LOGGER.info("Closed session. {deviceId: {}} ", inner.deviceId);
    }

    private void sendUnchecked(Packet.Type cmd, byte[] payload) throws IOException {
        cipherPair.sendEncoded(conn.out, cmd.val, payload);
    }

    private void waitAuthLock() {
        if (closing && conn == null) {
            LOGGER.debug("Connection was broken while Session.close() has been called");
            return;
        }

        if (closed) throw new IllegalStateException("Session is closed!");

        synchronized (authLock) {
            if (cipherPair == null || authLock.get()) {
                try {
                    authLock.wait();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
    }

    public void send(Packet.Type cmd, byte[] payload) throws IOException {
        if (closing && conn == null) {
            LOGGER.debug("Connection was broken while Session.close() has been called");
            return;
        }

        if (closed) throw new IllegalStateException("Session is closed!");

        synchronized (authLock) {
            if (cipherPair == null || authLock.get()) {
                try {
                    authLock.wait();
                } catch (InterruptedException ex) {
                    return;
                }
            }

            sendUnchecked(cmd, payload);
        }
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
    public DealerClient dealer() {
        waitAuthLock();
        if (dealer == null) throw new IllegalStateException("Session isn't authenticated!");
        return dealer;
    }

    @NotNull
    public ApiClient api() {
        waitAuthLock();
        if (api == null) throw new IllegalStateException("Session isn't authenticated!");
        return api;
    }

    @NotNull
    public PlayableContentFeeder contentFeeder() {
        waitAuthLock();
        if (contentFeeder == null) throw new IllegalStateException("Session isn't authenticated!");
        return contentFeeder;
    }

    @NotNull
    public SearchManager search() {
        waitAuthLock();
        if (search == null) throw new IllegalStateException("Session isn't authenticated!");
        return search;
    }

    @NotNull
    public EventService eventService() {
        waitAuthLock();
        if (eventService == null) throw new IllegalStateException("Session isn't authenticated!");
        return eventService;
    }

    @NotNull
    public String username() {
        return apWelcome().getCanonicalUsername();
    }

    @NotNull
    public Authentication.APWelcome apWelcome() {
        waitAuthLock();
        if (apWelcome == null) throw new IllegalStateException("Session isn't authenticated!");
        return apWelcome;
    }

    public boolean isValid() {
        if (closed) return false;

        waitAuthLock();
        return apWelcome != null && conn != null && !conn.socket.isClosed();
    }

    public boolean reconnecting() {
        return !closing && !closed && conn == null;
    }

    @NotNull
    ExecutorService executor() {
        return executorService;
    }

    @Nullable
    public String countryCode() {
        return countryCode;
    }

    @NotNull
    public String deviceId() {
        return inner.deviceId;
    }

    @NotNull
    public String preferredLocale() {
        return inner.preferredLocale;
    }

    @NotNull
    public Connect.DeviceType deviceType() {
        return inner.deviceType;
    }

    @NotNull
    public String deviceName() {
        return inner.deviceName;
    }

    @NotNull
    public Random random() {
        return inner.random;
    }

    @NotNull
    public Configuration configuration() {
        return inner.conf;
    }

    private void reconnect() {
        synchronized (reconnectionListeners) {
            reconnectionListeners.forEach(ReconnectionListener::onConnectionDropped);
        }

        try {
            if (conn != null) {
                conn.socket.close();
                receiver.stop();
            }

            conn = ConnectionHolder.create(ApResolver.getRandomAccesspoint(), inner.conf);
            connect();
            authenticatePartial(Authentication.LoginCredentials.newBuilder()
                    .setUsername(apWelcome.getCanonicalUsername())
                    .setTyp(apWelcome.getReusableAuthCredentialsType())
                    .setAuthData(apWelcome.getReusableAuthCredentials())
                    .build(), true);

            LOGGER.info("Re-authenticated as {}!", apWelcome.getCanonicalUsername());

            synchronized (reconnectionListeners) {
                reconnectionListeners.forEach(ReconnectionListener::onConnectionEstablished);
            }
        } catch (IOException | GeneralSecurityException | SpotifyAuthenticationException ex) {
            conn = null;
            LOGGER.error("Failed reconnecting, retrying in 10 seconds...", ex);

            try {
                scheduler.schedule(this::reconnect, 10, TimeUnit.SECONDS);
            } catch (RejectedExecutionException exx) {
                LOGGER.info("Scheduler already shutdown, stopping reconnection", exx);
            }
        }
    }

    public void addCloseListener(@NotNull CloseListener listener) {
        if (!closeListeners.contains(listener)) closeListeners.add(listener);
    }

    public void addReconnectionListener(@NotNull ReconnectionListener listener) {
        if (!reconnectionListeners.contains(listener)) reconnectionListeners.add(listener);
    }

    public void removeReconnectionListener(@NotNull ReconnectionListener listener) {
        reconnectionListeners.remove(listener);
    }

    private void parseProductInfo(@NotNull InputStream in) throws IOException, SAXException, ParserConfigurationException {
        DocumentBuilder dBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = dBuilder.parse(in);

        Node products = doc.getElementsByTagName("products").item(0);
        if (products == null) return;

        Node product = products.getChildNodes().item(0);
        if (product == null) return;

        NodeList properties = product.getChildNodes();
        for (int i = 0; i < properties.getLength(); i++) {
            Node node = properties.item(i);
            userAttributes.put(node.getNodeName(), node.getTextContent());
        }

        LOGGER.trace("Parsed product info: " + userAttributes);
    }

    @Nullable
    public String getUserAttribute(@NotNull String key) {
        return userAttributes.get(key);
    }

    @Contract("_, !null -> !null")
    public String getUserAttribute(@NotNull String key, @NotNull String fallback) {
        return userAttributes.getOrDefault(key, fallback);
    }

    @Override
    public void event(@NotNull MercuryClient.Response resp) {
        if (resp.uri.equals("spotify:user:attributes:update")) {
            UserAttributesUpdate attributesUpdate;
            try {
                attributesUpdate = UserAttributesUpdate.parseFrom(resp.payload.stream());
            } catch (IOException ex) {
                LOGGER.warn("Failed parsing user attributes update.", ex);
                return;
            }

            for (ExplicitContentPubsub.KeyValuePair pair : attributesUpdate.getPairsList()) {
                userAttributes.put(pair.getKey(), pair.getValue());
                LOGGER.trace("Updated user attribute: {} -> {}", pair.getKey(), pair.getValue());
            }
        }
    }

    @Override
    public void onMessage(@NotNull String uri, @NotNull Map<String, String> headers, @NotNull byte[] payload) {
        if (uri.equals("hm://connect-state/v1/connect/logout")) {
            try {
                close();
            } catch (IOException ex) {
                LOGGER.error("Failed closing session due to logout.", ex);
            }
        }
    }

    public interface ReconnectionListener {
        void onConnectionDropped();

        void onConnectionEstablished();
    }

    public interface CloseListener {
        void onClosed();
    }

    private static class Inner {
        final Connect.DeviceType deviceType;
        final String deviceName;
        final SecureRandom random;
        final String deviceId;
        final Configuration conf;
        final String preferredLocale;

        private Inner(@NotNull Connect.DeviceType deviceType, @NotNull String deviceName, @Nullable String deviceId, @NotNull String preferredLocale, @NotNull Configuration conf) {
            this.random = new SecureRandom();
            this.preferredLocale = preferredLocale;
            this.conf = conf;
            this.deviceType = deviceType;
            this.deviceName = deviceName;
            this.deviceId = (deviceId == null || deviceId.isEmpty()) ? Utils.randomHexString(random, 40).toLowerCase() : deviceId;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static abstract class AbsBuilder<T extends AbsBuilder> {
        protected final Configuration conf;
        protected String deviceId = null;
        protected String deviceName = "librespot-java";
        protected Connect.DeviceType deviceType = Connect.DeviceType.COMPUTER;
        protected String preferredLocale = "en";

        public AbsBuilder(@NotNull Configuration conf) {
            this.conf = conf;
        }

        public AbsBuilder() {
            this(new Configuration.Builder().build());
        }

        /**
         * Sets the preferred locale for the user.
         *
         * @param locale A 2 chars locale code
         */
        public T setPreferredLocale(@NotNull String locale) {
            if (locale.length() != 2)
                throw new IllegalArgumentException("Invalid locale: " + locale);

            this.preferredLocale = locale;
            return (T) this;
        }

        /**
         * Sets the device name that will appear on Spotify Connect.
         *
         * @param deviceName The device name
         */
        public T setDeviceName(@NotNull String deviceName) {
            this.deviceName = deviceName;
            return (T) this;
        }

        /**
         * Sets the device ID. If not provided or empty will be generated randomly.
         *
         * @param deviceId A 40 chars string
         */
        public T setDeviceId(@Nullable String deviceId) {
            if (deviceId != null && deviceId.length() != 40)
                throw new IllegalArgumentException("Device ID must be 40 chars long.");

            this.deviceId = deviceId;
            return (T) this;
        }

        /**
         * Sets the device type.
         *
         * @param deviceType The {@link com.spotify.connectstate.Connect.DeviceType}
         */
        public T setDeviceType(@NotNull Connect.DeviceType deviceType) {
            this.deviceType = deviceType;
            return (T) this;
        }
    }

    /**
     * Builder for setting up a {@link Session} object.
     */
    public static class Builder extends AbsBuilder<Builder> {
        private Authentication.LoginCredentials loginCredentials = null;

        public Builder(@NotNull Configuration conf) {
            super(conf);
        }

        public Builder() {
        }

        private static @NotNull Authentication.LoginCredentials decryptBlob(@NotNull String deviceId, @NotNull String username, byte[] encryptedBlob) throws GeneralSecurityException, IOException {
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

        /**
         * Gets the current credentials initialised for this {@link Builder}.
         *
         * @return A {@link com.spotify.Authentication.LoginCredentials} object or {@code null}
         */
        @Nullable
        public Authentication.LoginCredentials getCredentials() {
            return loginCredentials;
        }

        /**
         * Authenticates with stored credentials. Tries to read the file specified in the configuration.
         */
        public Builder stored() throws IOException {
            if (!conf.storeCredentials) throw new IllegalStateException("Credentials storing not enabled!");
            return stored(conf.storedCredentialsFile);
        }

        /**
         * Authenticates with stored credentials. The file must exist and be readable.
         *
         * @param storedCredentials The file where the JSON credentials are stored
         */
        public Builder stored(@NotNull File storedCredentials) throws IOException {
            try (FileReader reader = new FileReader(storedCredentials)) {
                JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                loginCredentials = Authentication.LoginCredentials.newBuilder()
                        .setTyp(Authentication.AuthenticationType.valueOf(obj.get("type").getAsString()))
                        .setUsername(obj.get("username").getAsString())
                        .setAuthData(Utils.fromBase64(obj.get("credentials").getAsString()))
                        .build();
            }

            return this;
        }

        /**
         * Authenticates with your Facebook account, will prompt to open a link in the browser. This locks until completion.
         */
        @NotNull
        public Builder facebook() throws IOException {
            try (FacebookAuthenticator authenticator = new FacebookAuthenticator()) {
                loginCredentials = authenticator.lockUntilCredentials();
            } catch (InterruptedException ignored) {
            }

            return this;
        }

        /**
         * Authenticates with a saved credentials blob.
         *
         * @param username Your Spotify username
         * @param blob     The Base64-decoded blob
         */
        @NotNull
        public Builder blob(@NotNull String username, byte[] blob) throws GeneralSecurityException, IOException {
            if (deviceId == null)
                throw new IllegalStateException("You must specify the device ID first.");

            loginCredentials = decryptBlob(deviceId, username, blob);
            return this;
        }

        /**
         * Authenticates with username and password. The credentials won't be saved.
         *
         * @param username Your Spotify username
         * @param password Your Spotify password
         */
        @NotNull
        public Builder userPass(@NotNull String username, @NotNull String password) {
            loginCredentials = Authentication.LoginCredentials.newBuilder()
                    .setUsername(username)
                    .setTyp(Authentication.AuthenticationType.AUTHENTICATION_USER_PASS)
                    .setAuthData(ByteString.copyFromUtf8(password))
                    .build();
            return this;
        }

        /**
         * Creates a connected and fully authenticated {@link Session} object.
         */
        @NotNull
        public Session create() throws IOException, GeneralSecurityException, SpotifyAuthenticationException, MercuryClient.MercuryException {
            if (loginCredentials == null)
                throw new IllegalStateException("You must select an authentication method.");

            ApResolver.fillPool();
            TimeProvider.init(conf);

            Session session = new Session(new Inner(deviceType, deviceName, deviceId, preferredLocale, conf), ApResolver.getRandomAccesspoint());
            session.connect();
            session.authenticate(loginCredentials);
            return session;
        }
    }

    public final static class Configuration {
        // Proxy
        public final boolean proxyEnabled;
        public final Proxy.Type proxyType;
        public final String proxyAddress;
        public final int proxyPort;
        public final boolean proxyAuth;
        public final String proxyUsername;
        public final String proxyPassword;

        // Time sync
        public final TimeProvider.Method timeSynchronizationMethod;
        public final int timeManualCorrection;

        // Cache
        public final boolean cacheEnabled;
        public final File cacheDir;
        public final boolean doCacheCleanUp;

        // Stored credentials
        public final boolean storeCredentials;
        public final File storedCredentialsFile;

        // Fetching
        public final boolean retryOnChunkError;

        private Configuration(boolean proxyEnabled, Proxy.Type proxyType, String proxyAddress, int proxyPort, boolean proxyAuth, String proxyUsername, String proxyPassword,
                              TimeProvider.Method timeSynchronizationMethod, int timeManualCorrection,
                              boolean cacheEnabled, File cacheDir, boolean doCacheCleanUp,
                              boolean storeCredentials, File storedCredentialsFile,
                              boolean retryOnChunkError) {
            this.proxyEnabled = proxyEnabled;
            this.proxyType = proxyType;
            this.proxyAddress = proxyAddress;
            this.proxyPort = proxyPort;
            this.proxyAuth = proxyAuth;
            this.proxyUsername = proxyUsername;
            this.proxyPassword = proxyPassword;
            this.timeSynchronizationMethod = timeSynchronizationMethod;
            this.timeManualCorrection = timeManualCorrection;
            this.cacheEnabled = cacheEnabled;
            this.cacheDir = cacheDir;
            this.doCacheCleanUp = doCacheCleanUp;
            this.storeCredentials = storeCredentials;
            this.storedCredentialsFile = storedCredentialsFile;
            this.retryOnChunkError = retryOnChunkError;
        }

        public static final class Builder {
            // Proxy
            private boolean proxyEnabled = false;
            private Proxy.Type proxyType;
            private String proxyAddress;
            private int proxyPort;
            private boolean proxyAuth;
            private String proxyUsername;
            private String proxyPassword;

            // Time sync
            private TimeProvider.Method timeSynchronizationMethod = TimeProvider.Method.NTP;
            private int timeManualCorrection;

            // Cache
            private boolean cacheEnabled = true;
            private File cacheDir = new File("cache");
            private boolean doCacheCleanUp;

            // Stored credentials
            private boolean storeCredentials = true;
            private File storedCredentialsFile = new File("credentials.json");

            // Fetching
            private boolean retryOnChunkError;

            public Builder() {
            }

            public Builder setProxyEnabled(boolean proxyEnabled) {
                this.proxyEnabled = proxyEnabled;
                return this;
            }

            public Builder setProxyType(Proxy.Type proxyType) {
                this.proxyType = proxyType;
                return this;
            }

            public Builder setProxyAddress(String proxyAddress) {
                this.proxyAddress = proxyAddress;
                return this;
            }

            public Builder setProxyPort(int proxyPort) {
                this.proxyPort = proxyPort;
                return this;
            }

            public Builder setProxyAuth(boolean proxyAuth) {
                this.proxyAuth = proxyAuth;
                return this;
            }

            public Builder setProxyUsername(String proxyUsername) {
                this.proxyUsername = proxyUsername;
                return this;
            }

            public Builder setProxyPassword(String proxyPassword) {
                this.proxyPassword = proxyPassword;
                return this;
            }

            public Builder setTimeSynchronizationMethod(TimeProvider.Method timeSynchronizationMethod) {
                this.timeSynchronizationMethod = timeSynchronizationMethod;
                return this;
            }

            public Builder setTimeManualCorrection(int timeManualCorrection) {
                this.timeManualCorrection = timeManualCorrection;
                return this;
            }

            public Builder setCacheEnabled(boolean cacheEnabled) {
                this.cacheEnabled = cacheEnabled;
                return this;
            }

            public Builder setCacheDir(File cacheDir) {
                this.cacheDir = cacheDir;
                return this;
            }

            public Builder setDoCacheCleanUp(boolean doCacheCleanUp) {
                this.doCacheCleanUp = doCacheCleanUp;
                return this;
            }

            public Builder setStoreCredentials(boolean storeCredentials) {
                this.storeCredentials = storeCredentials;
                return this;
            }

            public Builder setStoredCredentialsFile(File storedCredentialsFile) {
                this.storedCredentialsFile = storedCredentialsFile;
                return this;
            }

            public Builder setRetryOnChunkError(boolean retryOnChunkError) {
                this.retryOnChunkError = retryOnChunkError;
                return this;
            }

            @NotNull
            public Configuration build() {
                return new Configuration(proxyEnabled, proxyType, proxyAddress, proxyPort, proxyAuth, proxyUsername, proxyPassword,
                        timeSynchronizationMethod, timeManualCorrection,
                        cacheEnabled, cacheDir, doCacheCleanUp,
                        storeCredentials, storedCredentialsFile,
                        retryOnChunkError);
            }
        }
    }

    public static class SpotifyAuthenticationException extends Exception {
        private SpotifyAuthenticationException(Keyexchange.@NotNull APLoginFailed loginFailed) {
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

    private static class ConnectionHolder {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;

        private ConnectionHolder(@NotNull Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = new DataOutputStream(socket.getOutputStream());
        }

        @NotNull
        static ConnectionHolder create(@NotNull String addr, @NotNull Configuration conf) throws IOException {
            int colon = addr.indexOf(':');
            String apAddr = addr.substring(0, colon);
            int apPort = Integer.parseInt(addr.substring(colon + 1));
            if (!conf.proxyEnabled || conf.proxyType == Proxy.Type.DIRECT)
                return new ConnectionHolder(new Socket(apAddr, apPort));

            switch (conf.proxyType) {
                case HTTP:
                    Socket sock = new Socket(conf.proxyAddress, conf.proxyPort);
                    OutputStream out = sock.getOutputStream();
                    DataInputStream in = new DataInputStream(sock.getInputStream());

                    out.write(String.format("CONNECT %s:%d HTTP/1.0\n", apAddr, apPort).getBytes());
                    if (conf.proxyAuth) {
                        out.write("Proxy-Authorization: Basic ".getBytes());
                        out.write(Base64.getEncoder().encodeToString(String.format("%s:%s\n", conf.proxyUsername, conf.proxyPassword).getBytes()).getBytes());
                    }

                    out.write('\n');
                    out.flush();

                    String sl = Utils.readLine(in);
                    if (!sl.contains("200")) throw new IOException("Failed connecting: " + sl);

                    //noinspection StatementWithEmptyBody
                    while (!Utils.readLine(in).isEmpty()) {
                        // Read all headers
                    }

                    LOGGER.info("Successfully connected to the HTTP proxy.");
                    return new ConnectionHolder(sock);
                case SOCKS:
                    if (conf.proxyAuth) {
                        java.net.Authenticator.setDefault(new java.net.Authenticator() {
                            final String username = conf.proxyUsername;
                            final String password = conf.proxyPassword;

                            @Override
                            protected PasswordAuthentication getPasswordAuthentication() {
                                if (Objects.equals(getRequestingProtocol(), "SOCKS5") && Objects.equals(getRequestingPrompt(), "SOCKS authentication"))
                                    return new PasswordAuthentication(username, password.toCharArray());

                                return super.getPasswordAuthentication();
                            }
                        });
                    }

                    Proxy proxy = new Proxy(conf.proxyType, new InetSocketAddress(conf.proxyAddress, conf.proxyPort));
                    Socket proxySocket = new Socket(proxy);
                    proxySocket.connect(new InetSocketAddress(apAddr, apPort));
                    LOGGER.info("Successfully connected to the SOCKS proxy.");
                    return new ConnectionHolder(proxySocket);
                default:
                    throw new UnsupportedOperationException();
            }
        }
    }

    private class Receiver implements Runnable {
        private final Thread thread;
        private volatile boolean running = true;

        private Receiver() {
            thread = new Thread(this, "session-packet-receiver");
            thread.start();
        }

        void stop() {
            running = false;
            thread.interrupt();
        }

        @Override
        public void run() {
            LOGGER.trace("Session.Receiver started");

            while (running) {
                Packet packet;
                Packet.Type cmd;
                try {
                    packet = cipherPair.receiveEncoded(conn.in);
                    cmd = Packet.Type.parse(packet.cmd);
                    if (cmd == null) {
                        LOGGER.info("Skipping unknown command {cmd: 0x{}, payload: {}}", Integer.toHexString(packet.cmd), Utils.bytesToHex(packet.payload));
                        continue;
                    }
                } catch (IOException | GeneralSecurityException ex) {
                    if (running) {
                        LOGGER.fatal("Failed reading packet!", ex);
                        reconnect();
                    }

                    break;
                }

                if (!running) break;

                switch (cmd) {
                    case Ping:
                        if (scheduledReconnect != null) scheduledReconnect.cancel(true);
                        scheduledReconnect = scheduler.schedule(() -> {
                            LOGGER.warn("Socket timed out. Reconnecting...");
                            reconnect();
                        }, 2 * 60 + 5, TimeUnit.SECONDS);

                        TimeProvider.updateWithPing(packet.payload);

                        try {
                            send(Packet.Type.Pong, packet.payload);
                        } catch (IOException ex) {
                            LOGGER.fatal("Failed sending Pong!", ex);
                        }
                        break;
                    case PongAck:
                        // Silent
                        break;
                    case CountryCode:
                        countryCode = new String(packet.payload);
                        LOGGER.info("Received CountryCode: " + countryCode);
                        break;
                    case LicenseVersion:
                        ByteBuffer licenseVersion = ByteBuffer.wrap(packet.payload);
                        short id = licenseVersion.getShort();
                        if (id != 0) {
                            byte[] buffer = new byte[licenseVersion.get()];
                            licenseVersion.get(buffer);
                            LOGGER.info("Received LicenseVersion: {}, {}", id, new String(buffer));
                        } else {
                            LOGGER.info("Received LicenseVersion: {}", id);
                        }
                        break;
                    case Unknown_0x10:
                        LOGGER.debug("Received 0x10: " + Utils.bytesToHex(packet.payload));
                        break;
                    case MercurySub:
                    case MercuryUnsub:
                    case MercuryEvent:
                    case MercuryReq:
                        mercury().dispatch(packet);
                        break;
                    case AesKey:
                    case AesKeyError:
                        audioKey().dispatch(packet);
                        break;
                    case ChannelError:
                    case StreamChunkRes:
                        channel().dispatch(packet);
                        break;
                    case ProductInfo:
                        try {
                            parseProductInfo(new ByteArrayInputStream(packet.payload));
                        } catch (IOException | ParserConfigurationException | SAXException ex) {
                            LOGGER.warn("Failed parsing prodcut info!", ex);
                        }
                        break;
                    default:
                        LOGGER.info("Skipping " + cmd.name());
                        break;
                }
            }

            LOGGER.trace("Session.Receiver stopped");
        }
    }
}
