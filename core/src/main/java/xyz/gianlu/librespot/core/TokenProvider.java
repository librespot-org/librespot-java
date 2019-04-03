package xyz.gianlu.librespot.core;

import com.google.gson.JsonArray;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;

import java.io.IOException;
import java.util.Objects;

/**
 * @author Gianlu
 */
public class TokenProvider {
    private final static Logger LOGGER = Logger.getLogger(TokenProvider.class);
    private final Session session;
    private StoredToken token;

    TokenProvider(@NotNull Session session) {
        this.session = session;
    }

    @NotNull
    public String get(@NotNull String scope) throws IOException, MercuryClient.MercuryException {
        if (token != null && token.timestamp + token.expiresIn * 1000 > TimeProvider.currentTimeMillis()) {
            for (String avScope : token.scopes)
                if (Objects.equals(avScope, scope))
                    return token.accessToken;
        }

        LOGGER.debug("Token expired or not suitable, requesting again.");
        MercuryRequests.KeymasterToken resp = session.mercury().sendSync(MercuryRequests.requestToken(session.deviceId(), scope));
        token = new StoredToken(resp);

        return token.accessToken;
    }

    private static class StoredToken {
        final int expiresIn;
        final String accessToken;
        final String[] scopes;
        final long timestamp;

        private StoredToken(@NotNull MercuryRequests.KeymasterToken token) {
            timestamp = TimeProvider.currentTimeMillis();
            expiresIn = token.obj.get("expiresIn").getAsInt();
            accessToken = token.obj.get("accessToken").getAsString();

            JsonArray scopesArray = token.obj.getAsJsonArray("scope");
            scopes = new String[scopesArray.size()];
            for (int i = 0; i < scopesArray.size(); i++)
                scopes[i] = scopesArray.get(i).getAsString();
        }
    }
}
