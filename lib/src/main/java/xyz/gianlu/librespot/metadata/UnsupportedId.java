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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public final class UnsupportedId implements PlayableId {
    private final String uri;

    UnsupportedId(@NotNull String uri) {
        this.uri = uri;
    }

    @Override
    @Contract("-> fail")
    public @NotNull byte[] getGid() {
        throw new UnsupportedOperationException();
    }

    @Override
    @Contract("-> fail")
    public @NotNull String hexId() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull String toSpotifyUri() {
        return uri;
    }

    @NotNull
    @Override
    public String toString() {
        return "UnsupportedId{" + toSpotifyUri() + '}';
    }
}
