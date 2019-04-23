package xyz.gianlu.librespot.core;

import com.google.gson.JsonArray;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;

import javax.jmdns.impl.util.NamedThreadFactory;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author Gianlu
 */
public class TokenProvider {
    private final static Logger LOGGER = Logger.getLogger(TokenProvider.class);
    private final static int TOKEN_EXPIRE_THRESHOLD = 10;
    private final Session session;
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("token-expire-"));
    private final Map<String, StoredToken> tokens = new HashMap<>();

    TokenProvider(@NotNull Session session) {
        this.session = session;
    }

    @NotNull
    public String get(@NotNull String scope, @Nullable ExpireListener expireListener) throws IOException, MercuryClient.MercuryException {
        if (scope.contains(",")) throw new UnsupportedOperationException("Only single scope tokens are supported.");

        StoredToken token = tokens.get(scope);

        if (token != null) {
            if (token.expired()) {
                token.expireListeners.clear();
                tokens.remove(scope);
            } else {
                if (expireListener != null) token.expireListeners.add(expireListener);
                return token.accessToken;
            }
        }

        LOGGER.debug(String.format("Token expired or not suitable, requesting again. {scope: %s, token: %s}", scope, token));
        MercuryRequests.KeymasterToken resp = session.mercury().sendSync(MercuryRequests.requestToken(session.deviceId(), scope));
        token = new StoredToken(resp);

        tokens.put(scope, token);

        if (expireListener != null)
            token.expireListeners.add(expireListener);

        executorService.schedule(new ExpiredRunnable(token), token.expiresIn - TOKEN_EXPIRE_THRESHOLD, TimeUnit.SECONDS);
        return token.accessToken;
    }

    public interface ExpireListener {
        void tokenExpired();
    }

    private static class ExpiredRunnable implements Runnable {
        private final StoredToken token;

        ExpiredRunnable(@NotNull StoredToken token) {
            this.token = token;
        }

        @Override
        public void run() {
            for (ExpireListener listener : new ArrayList<>(token.expireListeners))
                listener.tokenExpired();
        }
    }

    private static class StoredToken {
        final int expiresIn;
        final String accessToken;
        final String[] scopes;
        final long timestamp;
        final Set<ExpireListener> expireListeners = new HashSet<>();

        private StoredToken(@NotNull MercuryRequests.KeymasterToken token) {
            timestamp = TimeProvider.currentTimeMillis();
            expiresIn = token.obj.get("expiresIn").getAsInt();
            accessToken = token.obj.get("accessToken").getAsString();

            JsonArray scopesArray = token.obj.getAsJsonArray("scope");
            scopes = new String[scopesArray.size()];
            for (int i = 0; i < scopesArray.size(); i++)
                scopes[i] = scopesArray.get(i).getAsString();
        }

        private boolean expired() {
            return timestamp + (expiresIn - TOKEN_EXPIRE_THRESHOLD) * 1000 < TimeProvider.currentTimeMillis();
        }

        @Override
        public String toString() {
            return "StoredToken{" +
                    "expiresIn=" + expiresIn +
                    ", accessToken='" + accessToken + '\'' +
                    ", scopes=" + Arrays.toString(scopes) +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }
}
