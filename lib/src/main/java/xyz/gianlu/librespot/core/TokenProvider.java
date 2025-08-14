/*
 * Copyright 2022 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.gianlu.librespot.core;

import com.google.gson.JsonObject;
import com.google.protobuf.ByteString;
import com.spotify.login5v3.Credentials;
import com.spotify.login5v3.Login5;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.common.Utils;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

/**
 * @author Gianlu
 */
public final class TokenProvider {
    private final static Logger LOGGER = LoggerFactory.getLogger(TokenProvider.class);
    private final static int TOKEN_EXPIRE_THRESHOLD = 10;
    private final Session session;
    private StoredToken token = null;

    TokenProvider(@NotNull Session session) {
        this.session = session;
    }

    @NotNull
    public synchronized StoredToken getToken() throws IOException, TokenException {
        if (this.token != null) {
            if (this.token.expired()) this.token = null;
            else return this.token;
        }

        LOGGER.debug("Token expired or not suitable, requesting again. {oldToken: {}}", this.token);

        try {
            Login5Api api = new Login5Api(session);
            Login5.LoginResponse resp = api.login5(
                    Login5.LoginRequest.newBuilder()
                            .setStoredCredential(Credentials.StoredCredential.newBuilder()
                                    .setUsername(session.username())
                                    .setData(ByteString.copyFrom(session.apWelcome().getReusableAuthCredentials().toByteArray()))
                                    .build())
                            .build()
            );
            if (!resp.hasOk()) throw new TokenException(resp.getError().getNumber());
            Login5.LoginOk okResponse = resp.getOk();

            JsonObject tokenBuilder = new JsonObject();
            tokenBuilder.addProperty("accessToken", okResponse.getAccessToken());
            tokenBuilder.addProperty("expiresIn", okResponse.getAccessTokenExpiresIn());
            tokenBuilder.addProperty("tokenType", "Bearer");

            this.token = new StoredToken(tokenBuilder);

            LOGGER.debug("Updated token successfully! {newToken: {}}", this.token);

            return this.token;
        }catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    @NotNull
    public String get() throws IOException, TokenException {
        return getToken().accessToken;
    }

    public static class TokenException extends Exception {
        private final int code;

        private TokenException(int code) {
            super("Error while requesting token! Code: " + code);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    public static class StoredToken {
        public final int expiresIn;
        public final String accessToken;
        public final long timestamp;

        private StoredToken(@NotNull JsonObject obj) {
            timestamp = TimeProvider.currentTimeMillis();
            expiresIn = obj.get("expiresIn").getAsInt();
            accessToken = obj.get("accessToken").getAsString();
        }

        public boolean expired() {
            return timestamp + (expiresIn - TOKEN_EXPIRE_THRESHOLD) * 1000L < TimeProvider.currentTimeMillis();
        }

        @Override
        public String toString() {
            return "StoredToken{" +
                    "expiresIn=" + expiresIn +
                    ", accessToken='" + Utils.truncateMiddle(accessToken, 12) +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }
}
