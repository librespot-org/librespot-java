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

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * @author Gianlu
 */
public final class FisherYatesShuffle<I> {
    private final Random random;
    private volatile long currentSeed;
    private volatile int sizeForSeed = -1;

    public FisherYatesShuffle(@NotNull Random random) {
        this.random = random;
    }

    private static int[] getShuffleExchanges(int size, long seed) {
        int[] exchanges = new int[size - 1];
        Random rand = new Random(seed);
        for (int i = size - 1; i > 0; i--) {
            int n = rand.nextInt(i + 1);
            exchanges[size - 1 - i] = n;
        }

        return exchanges;
    }

    public void shuffle(@NotNull List<I> list, boolean saveSeed) {
        shuffle(list, 0, list.size(), saveSeed);
    }

    /**
     * Shuffle the given list.
     *
     * @param list     the list.
     * @param from     lower bound index (inclusive).
     * @param to       top bound index (exclusive).
     * @param saveSeed whether the seed should be saved.
     */
    public void shuffle(@NotNull List<I> list, int from, int to, boolean saveSeed) {
        long seed = random.nextLong();
        if (saveSeed) currentSeed = seed;

        int size = to - from;
        if (saveSeed) sizeForSeed = size;

        int[] exchanges = getShuffleExchanges(size, seed);
        for (int i = size - 1; i > 0; i--) {
            int n = exchanges[size - 1 - i];
            Collections.swap(list, from + n, from + i);
        }
    }

    public void unshuffle(@NotNull List<I> list) {
        unshuffle(list, 0, list.size());
    }

    /**
     * Unshuffle the give list. The seed will be zeroed after this call.
     *
     * @param list the list.
     * @param from lower bound index (inclusive).
     * @param to   top bound index (exclusive).
     */
    public void unshuffle(@NotNull List<I> list, int from, int to) {
        if (currentSeed == 0) {
            throw new IllegalStateException("Current seed is zero!");
        }
        if (sizeForSeed != to - from) {
            throw new IllegalStateException("Size mismatch! Cannot unshuffle.");
        }

        int size = to - from;
        int[] exchanges = getShuffleExchanges(size, currentSeed);

        int swapsNeeded = size - 1;
        for (int i = 1; i < size; i++) {
            int n = exchanges[size - i - 1];
            Collections.swap(list, from + n, from + i);

            swapsNeeded--;
            if (swapsNeeded == 0) {
                break;
            }
        }

        currentSeed = 0;
        sizeForSeed = -1;
    }


    public boolean canUnshuffle(int size) {
        return currentSeed != 0 && sizeForSeed == size;
    }
}
