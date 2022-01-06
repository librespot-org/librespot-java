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
