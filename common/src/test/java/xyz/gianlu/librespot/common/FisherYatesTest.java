package xyz.gianlu.librespot.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * @author Gianlu
 */
class FisherYatesTest {
    private final List<String> original = Arrays.asList("A", "B", "C", "D", "E", "F", "G", "H");

    @Test
    void testShuffle() {
        FisherYatesShuffle<String> fy = new FisherYatesShuffle<>(new Random());
        List<String> list = new ArrayList<>(original);
        System.out.println(list);

        fy.shuffle(list, 2, 6, true);
        System.out.println(list);

        fy.unshuffle(list, 2, 6);
        System.out.println(list);

        Assertions.assertIterableEquals(list, original);
    }
}
