/*
 * Copyright 2022 devgianlu
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

package xyz.gianlu.librespot.mercury;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.json.JsonWrapper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;

/**
 * @author Gianlu
 */
public class JsonMercuryRequest<W extends JsonWrapper> {
    final RawMercuryRequest request;
    private final Class<W> wrapperClass;

    JsonMercuryRequest(@NotNull RawMercuryRequest request, @NotNull Class<W> wrapperClass) {
        this.request = request;
        this.wrapperClass = wrapperClass;
    }

    @NotNull
    public W instantiate(@NotNull MercuryClient.Response resp) {
        try (Reader reader = new InputStreamReader(resp.payload.stream())) {
            JsonElement elm = JsonParser.parseReader(reader);
            return wrapperClass.getConstructor(JsonObject.class).newInstance(elm.getAsJsonObject());
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException | IOException ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
