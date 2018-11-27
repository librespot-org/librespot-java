package xyz.gianlu.librespot.mercury;

import com.google.protobuf.AbstractMessageLite;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

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
        P process(@NotNull MercuryClient.Response response) throws IOException;
    }
}
