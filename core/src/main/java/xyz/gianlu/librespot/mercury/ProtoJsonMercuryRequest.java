package xyz.gianlu.librespot.mercury;

import com.google.gson.JsonElement;
import com.google.protobuf.AbstractMessageLite;
import com.google.protobuf.Parser;
import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public class ProtoJsonMercuryRequest<P extends AbstractMessageLite> extends ProtobufMercuryRequest<P> {
    final JsonConverter<P> converter;

    ProtoJsonMercuryRequest(@NotNull RawMercuryRequest request, @NotNull Parser<P> parser, @NotNull JsonConverter<P> converter) {
        super(request, parser);
        this.converter = converter;
    }

    public interface JsonConverter<P extends AbstractMessageLite> {
        @NotNull
        JsonElement convert(@NotNull P proto);
    }
}
