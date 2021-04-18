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

import java.io.Closeable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Wrapper around AsyncProcessor that deals with void methods and does not expect a response type
 *
 * @param <T> Task type for processor
 */
public class AsyncWorker<T> implements Closeable {
    private final AsyncProcessor<T, Void> underlyingProcessor;

    public AsyncWorker(@NotNull String name, @NotNull Consumer<T> consumer) {
        this.underlyingProcessor = new AsyncProcessor<>(name, t -> {
            consumer.accept(t);
            return null;
        });
    }

    @NotNull
    public Future<Void> submit(@NotNull T task) {
        return underlyingProcessor.submit(task);
    }

    public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        return underlyingProcessor.awaitTermination(timeout, unit);
    }

    @Override
    public void close() {
        underlyingProcessor.close();
    }
}
