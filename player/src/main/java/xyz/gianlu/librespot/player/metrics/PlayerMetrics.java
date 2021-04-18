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

package xyz.gianlu.librespot.player.metrics;

import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.audio.PlayableContentFeeder;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.codecs.Mp3Codec;
import xyz.gianlu.librespot.player.codecs.VorbisCodec;
import xyz.gianlu.librespot.player.crossfade.CrossfadeController;
import xyz.gianlu.librespot.player.mixing.output.OutputAudioFormat;

/**
 * @author devgianlu
 */
public final class PlayerMetrics {
    public final PlayableContentFeeder.Metrics contentMetrics;
    public int decodedLength = 0;
    public int size = 0;
    public int bitrate = 0;
    public float sampleRate = 0;
    public int duration = 0;
    public String encoding = null;
    public int fadeOverlap = 0;
    public String transition = "none";
    public int decryptTime = 0;

    public PlayerMetrics(@Nullable PlayableContentFeeder.Metrics contentMetrics, @Nullable CrossfadeController crossfade, @Nullable Codec codec) {
        this.contentMetrics = contentMetrics;

        if (codec != null) {
            size = codec.size();
            duration = codec.duration();
            decodedLength = codec.decodedLength();
            decryptTime = codec.decryptTimeMs();

            OutputAudioFormat format = codec.getAudioFormat();
            bitrate = (int) (format.getFrameRate() * format.getFrameSize());
            sampleRate = format.getSampleRate();

            if (codec instanceof VorbisCodec) encoding = "vorbis";
            else if (codec instanceof Mp3Codec) encoding = "mp3";
        }

        if (crossfade != null) {
            transition = "crossfade";
            fadeOverlap = crossfade.fadeOverlap();
        }
    }
}
