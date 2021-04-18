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

package xyz.gianlu.librespot.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.ZeroconfServer;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.ShellEvents;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Gianlu
 */
public class SessionWrapper {
    protected final AtomicReference<Session> sessionRef = new AtomicReference<>(null);
    protected final ShellEvents shellEvents;
    private Listener listener = null;

    protected SessionWrapper(@NotNull ShellEvents.Configuration shellConf) {
        shellEvents = shellConf.enabled ? new ShellEvents(shellConf) : null;
    }

    /**
     * Convenience method to create an instance of {@link SessionWrapper} that is updated by {@link ZeroconfServer}
     *
     * @param server The {@link ZeroconfServer}
     * @return A wrapper that holds a changing session
     */
    @NotNull
    public static SessionWrapper fromZeroconf(@NotNull ZeroconfServer server, @NotNull ShellEvents.Configuration shellConf) {
        SessionWrapper wrapper = new SessionWrapper(shellConf);
        server.addSessionListener(new ZeroconfServer.SessionListener() {
            @Override
            public void sessionClosing(@NotNull Session session) {
                if (wrapper.getSession() == session)
                    wrapper.clear();
            }

            @Override
            public void sessionChanged(@NotNull Session session) {
                wrapper.set(session);
            }
        });
        return wrapper;
    }

    /**
     * Convenience method to create an instance of {@link SessionWrapper} that holds a static session
     *
     * @param session The static session
     * @return A wrapper that holds a never-changing session
     */
    @NotNull
    public static SessionWrapper fromSession(@NotNull Session session, @NotNull ShellEvents.Configuration shellConf) {
        SessionWrapper wrapper = new SessionWrapper(shellConf);
        wrapper.sessionRef.set(session);
        return wrapper;
    }

    public void setListener(@NotNull Listener listener) {
        this.listener = listener;

        Session s;
        if ((s = sessionRef.get()) != null) listener.onNewSession(s);
    }

    protected void set(@NotNull Session session) {
        sessionRef.set(session);
        session.addCloseListener(this::clear);
        if (shellEvents != null) session.addReconnectionListener(shellEvents);
        if (listener != null) listener.onNewSession(session);
    }

    protected void clear() {
        Session old = sessionRef.get();
        sessionRef.set(null);
        if (old != null) {
            try {
                old.close();
            } catch (IOException ignored) {
            }

            if (listener != null) listener.onSessionCleared(old);
        }
    }

    @Nullable
    public Session getSession() {
        Session s = sessionRef.get();
        if (s != null) {
            if (s.isValid()) return s;
            else clear();
        }

        return null;
    }

    public interface Listener {
        void onSessionCleared(@NotNull Session old);

        void onNewSession(@NotNull Session session);
    }
}
