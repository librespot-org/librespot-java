package xyz.gianlu.librespot;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import xyz.gianlu.librespot.player.mixing.CircularBuffer;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Gianlu
 */
class CircularBufferTest {

    private static void write(@NotNull CircularBuffer b, int count) throws IOException {
        for (int i = 0; i < count; i++) b.write((byte) i);
    }

    private static void read(@NotNull CircularBuffer b, int count) throws IOException {
        for (int i = 0; i < count; i++) b.read();
    }

    @Test
    void test() throws IOException {
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
