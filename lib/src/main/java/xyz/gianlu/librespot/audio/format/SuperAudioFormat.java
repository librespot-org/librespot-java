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

package xyz.gianlu.librespot.audio.format;

import com.spotify.metadata.Metadata;
import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public enum SuperAudioFormat {
    MP3, VORBIS, AAC;

    @NotNull
    public static SuperAudioFormat get(@NotNull Metadata.AudioFile.Format format) {
        switch (format) {
            case OGG_VORBIS_96:
            case OGG_VORBIS_160:
            case OGG_VORBIS_320:
                return VORBIS;
            case MP3_256:
            case MP3_320:
            case MP3_160:
            case MP3_96:
            case MP3_160_ENC:
                return MP3;
            case AAC_24:
            case AAC_48:
            case AAC_24_NORM:
                return AAC;
            default:
                throw new IllegalArgumentException("Unknown audio format: " + format);
        }
    }
}
