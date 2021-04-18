/*
 * Copyright 2021 devgianlu
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

package xyz.gianlu.librespot.mercury;

import com.google.protobuf.AbstractMessageLite;
import com.google.protobuf.ByteString;
import com.spotify.Mercury;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.BytesArrayList;

/**
 * @author Gianlu
 */
public class RawMercuryRequest {
    final Mercury.Header header;
    final byte[][] payload;

    private RawMercuryRequest(@NotNull Mercury.Header header, byte[][] payload) {
        this.header = header;
        this.payload = payload;
    }

    @NotNull
    public static RawMercuryRequest sub(@NotNull String uri) {
        return RawMercuryRequest.newBuilder().setUri(uri).setMethod("SUB").build();
    }

    @NotNull
    public static RawMercuryRequest unsub(@NotNull String uri) {
        return RawMercuryRequest.newBuilder().setUri(uri).setMethod("UNSUB").build();
    }

    @NotNull
    public static RawMercuryRequest get(@NotNull String uri) {
        return RawMercuryRequest.newBuilder().setUri(uri).setMethod("GET").build();
    }

    @NotNull
    public static RawMercuryRequest send(String uri, byte[] part) {
        return RawMercuryRequest.newBuilder().setUri(uri).setMethod("SEND").addPayloadPart(part).build();
    }

    @NotNull
    public static RawMercuryRequest post(String uri, byte[] part) {
        return RawMercuryRequest.newBuilder().setUri(uri).setMethod("POST").addPayloadPart(part).build();
    }

    @NotNull
    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private final Mercury.Header.Builder header;
        private final BytesArrayList payload;

        private Builder() {
            header = Mercury.Header.newBuilder();
            payload = new BytesArrayList();
        }

        public Builder setUri(@NotNull String uri) {
            this.header.setUri(uri);
            return this;
        }

        public Builder setContentType(@NotNull String contentType) {
            this.header.setContentType(contentType);
            return this;
        }

        public Builder setMethod(@NotNull String method) {
            this.header.setMethod(method);
            return this;
        }

        public Builder addUserField(@NotNull Mercury.UserField field) {
            this.header.addUserFields(field);
            return this;
        }

        public Builder addUserField(@NotNull String key, @NotNull String value) {
            return addUserField(Mercury.UserField.newBuilder().setKey(key).setValue(ByteString.copyFromUtf8(value)).build());
        }

        public Builder addPayloadPart(@NotNull byte[] part) {
            this.payload.add(part);
            return this;
        }

        public Builder addProtobufPayload(@NotNull AbstractMessageLite<?, ?> msg) {
            return addPayloadPart(msg.toByteArray());
        }

        @NotNull
        public RawMercuryRequest build() {
            return new RawMercuryRequest(header.build(), payload.toArray());
        }
    }
}
