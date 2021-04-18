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
        assertEquals(0.368, interpolator.interpolate(0.08f), 0.0001);
        assertEquals(0.272, interpolator.interpolate(0.32f), 0.0001);
        assertEquals(1, interpolator.interpolate(0));
    }

    @Test
    public void testLinear() {
        GainInterpolator interpolator = new LinearIncreasingInterpolator();
        for (float i = 0; i < 1; i += 0.1)
            assertEquals(i, interpolator.interpolate(i));

        interpolator = new LinearDecreasingInterpolator();
        for (float i = 0; i < 1; i += 0.1)
            assertEquals(1 - i, interpolator.interpolate(i));
    }
}
