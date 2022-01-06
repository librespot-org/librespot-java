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

package xyz.gianlu.librespot.json;

import com.google.gson.JsonObject;
import com.spotify.context.ContextPageOuterClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.ProtoUtils;

import java.util.List;

/**
 * @author devgianlu
 */
public final class ResolvedContextWrapper extends JsonWrapper {

    public ResolvedContextWrapper(@NotNull JsonObject obj) {
        super(obj);
    }

    @NotNull
    public List<ContextPageOuterClass.ContextPage> pages() {
        return ProtoUtils.jsonToContextPages(obj.getAsJsonArray("pages"));
    }

    @Nullable
    public JsonObject metadata() {
        return obj.getAsJsonObject("metadata");
    }

    @NotNull
    public String uri() {
        return obj.get("uri").getAsString();
    }

    @NotNull
    public String url() {
        return obj.get("url").getAsString();
    }
}
