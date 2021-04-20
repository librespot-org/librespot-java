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

package xyz.gianlu.librespot.metadata;

import org.jetbrains.annotations.NotNull;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * @author devgianlu
 */
public final class LocalId implements PlayableId {
    private final String uri;
    private final String[] data;

    LocalId(@NotNull String uri) {
        this.uri = uri;
        this.data = uri.substring("spotify:local:".length()).split(":");
    }

    @Override
    public boolean hasGid() {
        return false;
    }

    @Override
    @NotNull
    public String toSpotifyUri() {
        return uri;
    }

    @NotNull
    public String artist() {
        try {
            return URLDecoder.decode(data[0], "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            return data[0];
        }
    }

    @NotNull
    public String album() {
        try {
            return URLDecoder.decode(data[1], "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            return data[1];
        }
    }

    @NotNull
    public String name() {
        try {
            return URLDecoder.decode(data[2], "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            return data[2];
        }
    }

    public int duration() {
        return Integer.parseInt(data[3]) * 1000;
    }

    @Override
    public String toString() {
        return "LocalId{" + toSpotifyUri() + "}";
    }
}
