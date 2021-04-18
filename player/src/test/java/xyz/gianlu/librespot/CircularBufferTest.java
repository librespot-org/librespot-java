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

package xyz.gianlu.librespot;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import xyz.gianlu.librespot.player.mixing.CircularBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Gianlu
 */
class CircularBufferTest {

    private static void write(@NotNull CircularBuffer b, int count) {
        for (int i = 0; i < count; i++) b.write((byte) i);
    }

    private static void read(@NotNull CircularBuffer b, int count) {
        for (int i = 0; i < count; i++) b.read();
    }

    @Test
    void test() {
        CircularBuffer b = new CircularBuffer(32);
        assertEquals(0, b.available());
        assertEquals(32, b.free());

        write(b, 32);
        System.out.println(b.dump());
        assertEquals(32, b.available());
        assertEquals(0, b.free());
        assertTrue(b.full());

        read(b, 20);
        System.out.println(b.dump());
        assertEquals(12, b.available());
        assertEquals(20, b.free());

        write(b, 14);
        System.out.println(b.dump());
        assertEquals(26, b.available());
        assertEquals(6, b.free());

        read(b, 26);
        System.out.println(b.dump());
        assertEquals(0, b.available());
        assertEquals(32, b.free());

        write(b, 32);
        System.out.println(b.dump());
        assertEquals(32, b.available());
        assertEquals(0, b.free());
        assertTrue(b.full());
    }
}
