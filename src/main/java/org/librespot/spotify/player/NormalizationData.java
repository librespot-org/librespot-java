package org.librespot.spotify.player;


import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * @author Gianlu
 */
public class NormalizationData {
    private static final Logger LOGGER = Logger.getLogger(NormalizationData.class);
    public final float track_gain_db;
    public final float track_peak;
    public final float album_gain_db;
    public final float album_peak;

    private NormalizationData(float track_gain_db, float track_peak, float album_gain_db, float album_peak) {
        this.track_gain_db = track_gain_db;
        this.track_peak = track_peak;
        this.album_gain_db = album_gain_db;
        this.album_peak = album_peak;
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

    public float getFactor(@NotNull Player.PlayerConfiguration config) {
        float normalisationFactor = (float) Math.pow(10, (track_gain_db + config.normalisationPregain()) / 20);
        if (normalisationFactor * track_peak > 1) {
            LOGGER.warn("Reducing normalisation factor to prevent clipping. Please add negative pregain to avoid.");
            normalisationFactor = 1 / track_peak;
        }

        return normalisationFactor;
    }
}
