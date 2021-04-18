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

package xyz.gianlu.librespot.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.MapEntry;
import com.google.protobuf.Message;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * @author Gianlu
 */
public final class ProtobufToJson {

    private ProtobufToJson() {
    }

    @NotNull
    public static JsonObject convert(@NotNull Message message) {
        JsonObject obj = new JsonObject();
        Map<Descriptors.FieldDescriptor, Object> fields = message.getAllFields();
        for (Descriptors.FieldDescriptor descriptor : fields.keySet())
            put(obj, descriptor, fields.get(descriptor));
        return obj;
    }

    @NotNull
    public static JsonArray convertList(@NotNull List<? extends Message> list) {
        JsonArray array = new JsonArray(list.size());
        for (Message msg : list) array.add(convert(msg));
        return array;
    }

    private static @NotNull JsonArray arrayOfNumbers(@NotNull List<? extends Number> list) {
        JsonArray array = new JsonArray(list.size());
        for (Number num : list) array.add(num);
        return array;
    }

    private static @NotNull JsonArray arrayOfBooleans(@NotNull List<Boolean> list) {
        JsonArray array = new JsonArray(list.size());
        for (Boolean b : list) array.add(b);
        return array;
    }

    private static @NotNull JsonObject mapOfStrings(@NotNull List<MapEntry<?, ?>> map) {
        JsonObject obj = new JsonObject();
        for (MapEntry<?, ?> entry : map) obj.addProperty(entry.getKey().toString(), entry.getValue().toString());
        return obj;
    }

    private static @NotNull JsonArray arrayOfStrings(@NotNull List<String> list) {
        JsonArray array = new JsonArray(list.size());
        for (String str : list) array.add(str);
        return array;
    }

    private static @NotNull JsonArray arrayOfEnums(@NotNull List<Descriptors.EnumValueDescriptor> list) {
        JsonArray array = new JsonArray(list.size());
        for (Descriptors.EnumValueDescriptor desc : list) array.add(desc.getName());
        return array;
    }

    private static @NotNull JsonArray arrayOfByteStrings(@NotNull List<ByteString> list) {
        JsonArray array = new JsonArray(list.size());
        for (ByteString str : list) array.add(Utils.bytesToHex(str));
        return array;
    }

    @SuppressWarnings("unchecked")
    private static void put(@NotNull JsonObject json, @NotNull Descriptors.FieldDescriptor descriptor, Object obj) {
        String key = descriptor.getJsonName();
        switch (descriptor.getJavaType()) {
            case FLOAT:
            case LONG:
            case DOUBLE:
            case INT:
                if (descriptor.isRepeated()) json.add(key, arrayOfNumbers((List<? extends Number>) obj));
                else json.addProperty(key, (Number) obj);
                break;
            case BOOLEAN:
                if (descriptor.isRepeated()) json.add(key, arrayOfBooleans((List<Boolean>) obj));
                else json.addProperty(key, (Boolean) obj);
                break;
            case STRING:
                if (descriptor.isRepeated()) json.add(key, arrayOfStrings((List<String>) obj));
                else json.addProperty(key, (String) obj);
                break;
            case BYTE_STRING:
                if (descriptor.isRepeated()) json.add(key, arrayOfByteStrings((List<ByteString>) obj));
                else json.addProperty(key, Utils.bytesToHex((ByteString) obj));
                break;
            case ENUM:
                if (descriptor.isRepeated()) json.add(key, arrayOfEnums((List<Descriptors.EnumValueDescriptor>) obj));
                else json.addProperty(key, ((Descriptors.EnumValueDescriptor) obj).getName());
                break;
            case MESSAGE:
                if (descriptor.isMapField()) json.add(key, mapOfStrings((List<MapEntry<?, ?>>) obj));
                else if (descriptor.isRepeated()) json.add(key, convertList((List<? extends Message>) obj));
                else json.add(key, convert((Message) obj));
                break;
            default:
                throw new IllegalStateException("Unknown type: " + descriptor.getJavaType());
        }
    }
}
