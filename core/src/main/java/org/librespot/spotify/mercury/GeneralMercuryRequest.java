package org.librespot.spotify.mercury;

import com.google.protobuf.InvalidProtocolBufferException;
import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public class GeneralMercuryRequest<M> {
    final Processor<M> processor;
    final String uri;
    final MercuryClient.Method method;
    final byte[][] payload;

    GeneralMercuryRequest(String uri, MercuryClient.Method method, byte[][] payload, Processor<M> processor) {
        this.uri = uri;
        this.method = method;
        this.payload = payload;
        this.processor = processor;
    }

    public interface Processor<M> {
        @NotNull
        M process(@NotNull MercuryClient.Response response) throws InvalidProtocolBufferException;
    }
}
