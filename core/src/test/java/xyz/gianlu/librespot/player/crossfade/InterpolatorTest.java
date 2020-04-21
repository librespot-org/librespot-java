package xyz.gianlu.librespot.player.crossfade;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author devgianlu
 */
public class InterpolatorTest {

    @Test
    public void testLookup() {
        LookupInterpolator interpolator = LookupInterpolator.fromJson(JsonParser.parseString("[{\"x\":0.0,\"y\":1.0},{\"x\":0.0,\"y\":0.4},{\"x\":1.0,\"y\":0.0}]").getAsJsonArray());
        assertEquals(0, interpolator.interpolate(1));
        assertEquals(0.368, interpolator.interpolate(0.08f));
        assertEquals(0.272, interpolator.interpolate(0.32f));
        assertEquals(1, interpolator.interpolate(0));
    }
}
