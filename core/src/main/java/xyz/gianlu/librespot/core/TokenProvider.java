package xyz.gianlu.librespot.core;

import com.google.gson.JsonArray;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Gianlu
 */
public class TokenProvider {
    private final static Logger LOGGER = Logger.getLogger(TokenProvider.class);
    private final static int TOKEN_EXPIRE_THRESHOLD = 10;
    private final Session session;
    private final Map<String, StoredToken> tokens = new HashMap<>();

    TokenProvider(@NotNull Session session) {
        this.session = session;
    }

    @NotNull
    public StoredToken getToken(@NotNull String scope) throws IOException, MercuryClient.MercuryException {
        if (scope.contains(",")) throw new UnsupportedOperationException("Only single scope tokens are supported.");

        StoredToken token = tokens.get(scope);
        if (token != null) {
            if (token.expired()) tokens.remove(scope);
            else return token;
        }

        LOGGER.debug(String.format("Token expired or not suitable, requesting again. {scope: %s, oldToken: %s}", scope, token));
        MercuryRequests.KeymasterToken resp = session.mercury().sendSync(MercuryRequests.requestToken(session.deviceId(), scope));
        token = new StoredToken(resp);

        LOGGER.debug(String.format("Updated token successfully! {scope: %s, newToken: %s}", scope, token));

        tokens.put(scope, token);
        return token;
    }

    @NotNull
    public String get(@NotNull String scope) throws IOException, MercuryClient.MercuryException {
        return getToken(scope).accessToken;
    }

    public static class StoredToken {
        public final int expiresIn;
        public final String accessToken;
        public final String[] scopes;
        public final long timestamp;

        private StoredToken(@NotNull MercuryRequests.KeymasterToken token) {
            timestamp = TimeProvider.currentTimeMillis();
            expiresIn = token.obj.get("expiresIn").getAsInt();
            accessToken = token.obj.get("accessToken").getAsString();

            JsonArray scopesArray = token.obj.getAsJsonArray("scope");
            scopes = new String[scopesArray.size()];
            for (int i = 0; i < scopesArray.size(); i++)
                scopes[i] = scopesArray.get(i).getAsString();
        }

        public boolean expired() {
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
