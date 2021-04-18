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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * @author Gianlu
 */
public interface SpotifyId {
    String STATIC_FROM_URI = "fromUri";
    String STATIC_FROM_BASE62 = "fromBase62";
    String STATIC_FROM_HEX = "fromHex";

    @NotNull
    static <I extends SpotifyId> I fromBase62(Class<I> clazz, String base62) throws SpotifyIdParsingException {
        return callReflection(clazz, STATIC_FROM_BASE62, base62);
    }

    @NotNull
    static <I extends SpotifyId> I fromHex(Class<I> clazz, String hex) throws SpotifyIdParsingException {
        return callReflection(clazz, STATIC_FROM_HEX, hex);
    }

    @NotNull
    static <I extends SpotifyId> I fromUri(Class<I> clazz, String uri) throws SpotifyIdParsingException {
        return callReflection(clazz, STATIC_FROM_URI, uri);
    }

    @SuppressWarnings("unchecked")
    @NotNull
    static <I extends SpotifyId> I callReflection(@NotNull Class<I> clazz, @NotNull String name, @NotNull String arg) throws SpotifyIdParsingException {
        try {
            Method method = clazz.getDeclaredMethod(name, String.class);
            return (I) method.invoke(null, arg);
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException ex) {
            throw new SpotifyIdParsingException(ex);
        }
    }

    @NotNull String toSpotifyUri();

    class SpotifyIdParsingException extends Exception {
        SpotifyIdParsingException(Throwable cause) {
            super(cause);
        }
    }
}
