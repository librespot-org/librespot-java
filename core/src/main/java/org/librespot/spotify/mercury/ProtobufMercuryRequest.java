package org.librespot.spotify.mercury;

import com.google.protobuf.AbstractMessageLite;
import com.google.protobuf.InvalidProtocolBufferException;
import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public class ProtobufMercuryRequest<P extends AbstractMessageLite> {
    final RawMercuryRequest request;
    final Processor<P> processor;

    ProtobufMercuryRequest(@NotNull RawMercuryRequest request, @NotNull Processor<P> processor) {
        this.request = request;
        this.processor = processor;
    }

    public interface Processor<P extends AbstractMessageLite> {
        @NotNull
        P process(@NotNull MercuryClient.Response response) throws InvalidProtocolBufferException;
    }
}
