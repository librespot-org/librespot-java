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

package xyz.gianlu.librespot.player;


import org.apache.logging.log4j.core.config.Configurator;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.ZeroconfServer;
import xyz.gianlu.librespot.common.Log4JUncaughtExceptionHandler;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, MercuryClient.MercuryException {
        FileConfiguration conf = new FileConfiguration(args);
        Configurator.setRootLevel(conf.loggingLevel());
        Thread.setDefaultUncaughtExceptionHandler(new Log4JUncaughtExceptionHandler());

        if (conf.authStrategy() == FileConfiguration.AuthStrategy.ZEROCONF) {
            ShellEvents shellEvents;
            ShellEvents.Configuration eventsShellConf = conf.toEventsShell();
            if (eventsShellConf.enabled) shellEvents = new ShellEvents(eventsShellConf);
            else shellEvents = null;

            ZeroconfServer server = conf.initZeroconfBuilder().create();
            server.addSessionListener(new ZeroconfServer.SessionListener() {
                Player lastPlayer = null;

                {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (lastPlayer != null) lastPlayer.close();
                    }));
                }

                @Override
                public void sessionClosing(@NotNull Session session) {
                    if (lastPlayer != null) lastPlayer.close();
                }

                @Override
                public void sessionChanged(@NotNull Session session) {
                    lastPlayer = new Player(conf.toPlayer(), session);

                    if (shellEvents != null) {
                        session.addReconnectionListener(shellEvents);
                        lastPlayer.addEventsListener(shellEvents);
                    }
                }
            });

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.closeSession();
                    server.close();
                } catch (IOException ignored) {
                }
            }));
        } else {
            Session session = conf.initSessionBuilder().create();
            Player player = new Player(conf.toPlayer(), session);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    player.close();
                    session.close();
                } catch (IOException ignored) {
                }
            }));

            ShellEvents.Configuration eventsShellConf = conf.toEventsShell();
            if (eventsShellConf.enabled) {
                ShellEvents shellEvents = new ShellEvents(eventsShellConf);
                session.addReconnectionListener(shellEvents);
                player.addEventsListener(shellEvents);
            }
        }
    }
}
