package xyz.gianlu.librespot.core;

import com.google.gson.JsonArray;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * @author Gianlu
 */
public final class TokenProvider {
    private final static Logger LOGGER = LogManager.getLogger(TokenProvider.class);
    private final static int TOKEN_EXPIRE_THRESHOLD = 10;
    private final Session session;
    private final List<StoredToken> tokens = new ArrayList<>();

    TokenProvider(@NotNull Session session) {
        this.session = session;
    }

    @Nullable
    private StoredToken findTokenWithAllScopes(String[] scopes) {
        for (StoredToken token : tokens)
            if (token.hasScopes(scopes))
                return token;

        return null;
    }

    @NotNull
    public synchronized StoredToken getToken(@NotNull String... scopes) throws IOException, MercuryClient.MercuryException {
        if (scopes.length == 0) throw new IllegalArgumentException();

        StoredToken token = findTokenWithAllScopes(scopes);
        if (token != null) {
            if (token.expired()) tokens.remove(token);
            else return token;
        }

        LOGGER.debug("Token expired or not suitable, requesting again. {scopes: {}, oldToken: {}}", Arrays.asList(scopes), token);
        MercuryRequests.KeymasterToken resp = session.mercury().sendSync(MercuryRequests.requestToken(session.deviceId(), String.join(",", scopes)));
        token = new StoredToken(resp);

        LOGGER.debug("Updated token successfully! {scopes: {}, newToken: {}}", Arrays.asList(scopes), token);
        tokens.add(token);

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
                    ", accessToken='" + accessToken.substring(0, 10) + "[truncated]'" +
                    ", scopes=" + Arrays.toString(scopes) +
                    ", timestamp=" + timestamp +
                    '}';
        }

        public boolean hasScope(@NotNull String scope) {
            for (String s : scopes)
                if (Objects.equals(s, scope))
                    return true;

            return false;
        }

        public boolean hasScopes(String[] sc) {
            for (String s : sc)
                if (!hasScope(s))
                    return false;

            return true;
        }
    }
}
