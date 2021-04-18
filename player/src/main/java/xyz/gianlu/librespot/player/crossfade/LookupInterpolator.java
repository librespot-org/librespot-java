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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import java.util.Arrays;

/**
 * Lookup interpolator based on <a href="https://www.embeddedrelated.com/showcode/345.php>this snippet</a>.
 */
class LookupInterpolator implements GainInterpolator {
    private final float[] tx;
    private final float[] ty;

    private LookupInterpolator(float[] x, float[] y) {
        this.tx = x;
        this.ty = y;
    }

    /**
     * Used to parse the 'fade_curve' JSON array
     */
    @NotNull
    static LookupInterpolator fromJson(@NotNull JsonArray curve) {
        float[] x = new float[curve.size()];
        float[] y = new float[curve.size()];
        for (int i = 0; i < curve.size(); i++) {
            JsonObject obj = curve.get(i).getAsJsonObject();
            x[i] = obj.get("x").getAsFloat();
            y[i] = obj.get("y").getAsFloat();
        }

        return new LookupInterpolator(x, y);
    }

    @Override
    public String toString() {
        return "LookupInterpolator{" +
                "tx=" + Arrays.toString(tx) +
                ", ty=" + Arrays.toString(ty) +
                '}';
    }

    @Override
    public float interpolate(@Range(from = 0, to = 1) float ix) {
        if (ix >= tx[tx.length - 1]) return ty[tx.length - 1];
        else if (ix <= tx[0]) return ty[0];

        for (int i = 0; i < tx.length - 1; i++) {
            if (ix >= tx[i] && ix <= tx[i + 1]) {
                float o_low = ty[i]; // Output (table) low value
                float i_low = tx[i]; // Input (X-axis) low value
                float i_delta = tx[i + 1] - tx[i]; // Spread between the two adjacent input values
                float o_delta = ty[i + 1] - ty[i]; // Spread between the two adjacent table output values

                if (o_delta == 0) return o_low;
                else return o_low + ((ix - i_low) * o_delta) / i_delta;
            }
        }

        throw new IllegalArgumentException(String.format("Could not interpolate! {ix: %f, tx: %s, ty: %s}", ix, Arrays.toString(tx), Arrays.toString(ty)));
    }

    @Override
    public float last() {
        return ty[tx.length - 1];
    }
}
