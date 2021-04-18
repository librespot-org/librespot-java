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

package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.state.RestrictionsManager;

import java.util.Objects;

/**
 * @author Gianlu
 */
public abstract class AbsSpotifyContext {
    public final RestrictionsManager restrictions;
    protected final String context;

    AbsSpotifyContext(@NotNull String context) {
        this.context = context;
        this.restrictions = new RestrictionsManager(this);
    }

    public static boolean isCollection(@NotNull Session session, @NotNull String uri) {
        return Objects.equals(uri, "spotify:user:" + session.username() + ":collection");
    }

    @NotNull
    public static AbsSpotifyContext from(@NotNull String context) {
        if (context.startsWith("spotify:dailymix:") || context.startsWith("spotify:station:"))
            return new GeneralInfiniteContext(context);
        else if (context.startsWith("spotify:search:"))
            return new SearchContext(context, context.substring(15));
        else
            return new GeneralFiniteContext(context);
    }

    @Override
    public String toString() {
        return "AbsSpotifyContext{context='" + context + "'}";
    }

    public abstract boolean isFinite();

    public final @NotNull String uri() {
        return context;
    }

    public static class UnsupportedContextException extends Exception {
        UnsupportedContextException(@NotNull String message) {
            super(message);
        }

        @NotNull
        public static UnsupportedContextException cannotPlayAnything() {
            return new UnsupportedContextException("Nothing from this context can or should be played!");
        }
    }
}
