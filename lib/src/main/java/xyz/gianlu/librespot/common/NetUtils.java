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

import java.io.IOException;

/**
 * @author Gianlu
 */
public final class NetUtils {

    private NetUtils() {
    }

    @NotNull
    public static StatusLine parseStatusLine(@NotNull String line) throws IOException {
        try {
            int index = line.indexOf(' ');
            String httpVersion = line.substring(0, index);
            line = line.substring(index + 1);
            index = line.indexOf(' ');
            String statusCode = line.substring(0, index);
            String statusPhrase = line.substring(index + 1);
            return new StatusLine(httpVersion, Integer.parseInt(statusCode), statusPhrase);
        } catch (Exception ex) {
            throw new IOException(line, ex);
        }
    }

    public static class StatusLine {
        public final String httpVersion;
        public final int statusCode;
        public final String statusPhrase;

        private StatusLine(String httpVersion, int statusCode, String statusPhrase) {
            this.httpVersion = httpVersion;
            this.statusCode = statusCode;
            this.statusPhrase = statusPhrase;
        }
    }
}
