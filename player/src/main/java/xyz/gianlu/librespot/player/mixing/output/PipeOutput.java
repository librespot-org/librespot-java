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

package xyz.gianlu.librespot.player.mixing.output;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * @author devgianlu
 */
public final class PipeOutput implements SinkOutput {
    private static final Logger LOGGER = LoggerFactory.getLogger(PipeOutput.class);
    private final File file;
    private OutputStream stream;

    public PipeOutput(@NotNull File file) {
        this.file = file;
    }

    @Override
    public void write(byte[] buffer, int offset, int len) throws IOException {
        if (stream == null) {
            if (!file.exists()) {
                try {
                    Process p = new ProcessBuilder()
                            .command("mkfifo", file.getAbsolutePath())
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .start();
                    p.waitFor();
                    if (p.exitValue() != 0)
                        LOGGER.warn("Failed creating pipe! {exit: {}}", p.exitValue());
                    else
                        LOGGER.info("Created pipe: " + file);
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
            }

            stream = new FileOutputStream(file);
        }

        stream.write(buffer, 0, len);
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
