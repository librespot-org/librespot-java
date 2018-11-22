package org.librespot.spotify.mercury;

import com.google.protobuf.AbstractMessageLite;
import com.google.protobuf.ByteString;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.BytesArrayList;
import org.librespot.spotify.proto.Mercury;

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
    public static RawMercuryRequest get(@NotNull String uri) {
        return RawMercuryRequest.newBuilder().setUri(uri).setMethod("GET").build();
    }

    @NotNull
    public static RawMercuryRequest send(String uri, byte[] part) {
        return RawMercuryRequest.newBuilder().setUri(uri).setMethod("SEND").addPayloadPart(part).build();
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

        public Builder addProtobufPayload(@NotNull AbstractMessageLite msg) {
            return addPayloadPart(msg.toByteArray());
        }

        @NotNull
        public RawMercuryRequest build() {
            return new RawMercuryRequest(header.build(), payload.toArray());
        }
    }
}
