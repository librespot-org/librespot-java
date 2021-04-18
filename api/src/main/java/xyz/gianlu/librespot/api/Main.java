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


import org.apache.logging.log4j.core.config.Configurator;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Log4JUncaughtExceptionHandler;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.player.FileConfiguration;
import xyz.gianlu.librespot.player.FileConfiguration.AuthStrategy;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException, MercuryClient.MercuryException, GeneralSecurityException, Session.SpotifyAuthenticationException {
        FileConfiguration conf = new FileConfiguration(args);
        Configurator.setRootLevel(conf.loggingLevel());
        Thread.setDefaultUncaughtExceptionHandler(new Log4JUncaughtExceptionHandler());

        String host = conf.apiHost();
        int port = conf.apiPort();

        if (args.length > 0 && args[0].equals("noPlayer")) withoutPlayer(port, host, conf);
        else withPlayer(port, host, conf);
    }

    private static void withPlayer(int port, @NotNull String host, @NotNull FileConfiguration conf) throws IOException, MercuryClient.MercuryException, GeneralSecurityException, Session.SpotifyAuthenticationException {
        PlayerWrapper wrapper;
        if (conf.authStrategy() == AuthStrategy.ZEROCONF)
            wrapper = PlayerWrapper.fromZeroconf(conf.initZeroconfBuilder().create(), conf.toPlayer(), conf.toEventsShell());
        else
            wrapper = PlayerWrapper.fromSession(conf.initSessionBuilder().create(), conf.toPlayer(), conf.toEventsShell());

        PlayerApiServer server = new PlayerApiServer(port, host, wrapper);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
    }

    private static void withoutPlayer(int port, @NotNull String host, @NotNull FileConfiguration conf) throws IOException, MercuryClient.MercuryException, GeneralSecurityException, Session.SpotifyAuthenticationException {
        SessionWrapper wrapper;
        if (conf.authStrategy() == AuthStrategy.ZEROCONF)
            wrapper = SessionWrapper.fromZeroconf(conf.initZeroconfBuilder().create(), conf.toEventsShell());
        else
            wrapper = SessionWrapper.fromSession(conf.initSessionBuilder().create(), conf.toEventsShell());

        ApiServer server = new ApiServer(port, host, wrapper);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
    }
}
