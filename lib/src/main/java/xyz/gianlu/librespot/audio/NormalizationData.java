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

package xyz.gianlu.librespot.audio;


import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * @author Gianlu
 */
public class NormalizationData {
    private static final Logger LOGGER = LoggerFactory.getLogger(NormalizationData.class);
    public final float track_gain_db;
    public final float track_peak;
    public final float album_gain_db;
    public final float album_peak;

    private NormalizationData(float track_gain_db, float track_peak, float album_gain_db, float album_peak) {
        this.track_gain_db = track_gain_db;
        this.track_peak = track_peak;
        this.album_gain_db = album_gain_db;
        this.album_peak = album_peak;

        LOGGER.trace("Loaded normalization data, track_gain: {}, track_peak: {}, album_gain: {}, album_peak: {}",
                track_gain_db, track_peak, album_gain_db, album_peak);
    }

    @NotNull
    public static NormalizationData read(@NotNull InputStream in) throws IOException {
        DataInputStream dataIn = new DataInputStream(in);
        dataIn.mark(16);
        if (dataIn.skipBytes(144) != 144) throw new IOException();

        byte[] data = new byte[4 * 4];
        dataIn.readFully(data);
        dataIn.reset();

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return new NormalizationData(buffer.getFloat(), buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
    }

    public float getFactor(float normalisationPregain, boolean useAlbumGain) {
        float gain = useAlbumGain? album_gain_db : track_gain_db;
        LOGGER.trace("Using gain: {}", gain);
        float normalisationFactor = (float) Math.pow(10, (gain + normalisationPregain) / 20);
        if (normalisationFactor * track_peak > 1) {
            LOGGER.warn("Reducing normalisation factor to prevent clipping. Please add negative pregain to avoid.");
            normalisationFactor = 1 / track_peak;
        }

        return normalisationFactor;
    }

    public float getFactor(float normalisationPregain) {
        return getFactor(normalisationPregain, false);
    }
}
