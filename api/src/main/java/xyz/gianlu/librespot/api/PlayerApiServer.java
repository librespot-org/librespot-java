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
import xyz.gianlu.librespot.api.handlers.InstanceHandler;
import xyz.gianlu.librespot.api.handlers.PlayerHandler;

/**
 * @author devgianlu
 */
public class PlayerApiServer extends ApiServer {
    public PlayerApiServer(int port, @NotNull String host, @NotNull PlayerWrapper wrapper) {
        super(port, host, wrapper);

        handler.post("/player/{cmd}", new PlayerHandler(wrapper));
        handler.post("/instance/{action}", InstanceHandler.forPlayer(this, wrapper)); // Overrides session only handler
        wrapper.setListener(events);
    }
}
