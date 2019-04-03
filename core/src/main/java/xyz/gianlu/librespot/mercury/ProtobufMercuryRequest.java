package xyz.gianlu.librespot.mercury;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public class ProtobufMercuryRequest<P extends Message> {
    final RawMercuryRequest request;
    final Parser<P> parser;

    ProtobufMercuryRequest(@NotNull RawMercuryRequest request, @NotNull Parser<P> parser) {
        this.request = request;
        this.parser = parser;
    }
}
