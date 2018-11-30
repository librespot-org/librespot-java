package xyz.gianlu.librespot.mercury;

import com.google.protobuf.AbstractMessageLite;
import com.google.protobuf.Parser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class ProtobufMercuryRequest<P extends AbstractMessageLite> {
    final RawMercuryRequest request;
    final Parser<P> parser;

    ProtobufMercuryRequest(@NotNull RawMercuryRequest request, @NotNull Parser<P> parser) {
        this.request = request;
        this.parser = parser;
    }
}
