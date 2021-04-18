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
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.PlayerConfiguration;
import xyz.gianlu.librespot.player.ShellEvents;

import java.util.concurrent.atomic.AtomicReference;

/**
 * @author devgianlu
 */
public class PlayerWrapper extends SessionWrapper {
    private final AtomicReference<Player> playerRef = new AtomicReference<>(null);
    private final PlayerConfiguration conf;
    private Listener listener = null;

    private PlayerWrapper(@NotNull PlayerConfiguration conf, @NotNull ShellEvents.Configuration shellConf) {
        super(shellConf);
        this.conf = conf;
    }

    /**
     * Convenience method to create an instance of {@link PlayerWrapper} that is updated by {@link ZeroconfServer}
     *
     * @param server The {@link ZeroconfServer}
     * @param conf   The player configuration
     * @return A wrapper that holds a changing session-player tuple
     */
    @NotNull
    public static PlayerWrapper fromZeroconf(@NotNull ZeroconfServer server, @NotNull PlayerConfiguration conf, @NotNull ShellEvents.Configuration shellConf) {
        PlayerWrapper wrapper = new PlayerWrapper(conf, shellConf);
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
     * Convenience method to create an instance of {@link PlayerWrapper} that holds a static session and player
     *
     * @param session The static session
     * @param conf    The player configuration
     * @return A wrapper that holds a never-changing session-player tuple
     */
    @NotNull
    public static PlayerWrapper fromSession(@NotNull Session session, @NotNull PlayerConfiguration conf, @NotNull ShellEvents.Configuration shellConf) {
        PlayerWrapper wrapper = new PlayerWrapper(conf, shellConf);
        wrapper.sessionRef.set(session);
        wrapper.playerRef.set(new Player(conf, session));
        return wrapper;
    }

    public void setListener(@NotNull Listener listener) {
        super.setListener(listener);
        this.listener = listener;

        Player p;
        if ((p = playerRef.get()) != null) listener.onNewPlayer(p);
    }

    @Override
    protected void set(@NotNull Session session) {
        super.set(session);

        Player player = new Player(conf, session);
        playerRef.set(player);

        if (shellEvents != null) player.addEventsListener(shellEvents);
        if (listener != null) listener.onNewPlayer(player);
    }

    @Override
    protected void clear() {
        Player old = playerRef.get();
        if (old != null) old.close();
        playerRef.set(null);

        if (listener != null && old != null) listener.onPlayerCleared(old);

        super.clear();
    }

    @Nullable
    public Player getPlayer() {
        return playerRef.get();
    }

    public interface Listener extends SessionWrapper.Listener {
        void onPlayerCleared(@NotNull Player old);

        void onNewPlayer(@NotNull Player player);
    }
}
