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

package xyz.gianlu.librespot.player.metrics;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.EventService;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.player.StateWrapper;

/**
 * Event structure for a new session ID.
 *
 * @author devgianlu
 */
public final class NewSessionIdEvent implements EventService.GenericEvent {
    private final String sessionId;
    private final StateWrapper state;

    public NewSessionIdEvent(@NotNull String sessionId, @NotNull StateWrapper state) {
        this.sessionId = sessionId;
        this.state = state;
    }

    @Override
    @NotNull
    public EventService.EventBuilder build() {
        String contextUri = state.getContextUri();

        EventService.EventBuilder event = new EventService.EventBuilder(EventService.Type.NEW_SESSION_ID);
        event.append(sessionId);
        event.append(contextUri);
        event.append(contextUri);
        event.append(String.valueOf(TimeProvider.currentTimeMillis()));
        event.append("").append(String.valueOf(state.getContextSize()));
        event.append(state.getContextUrl());
        return event;
    }
}
