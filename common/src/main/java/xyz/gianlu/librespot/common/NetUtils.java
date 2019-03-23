package xyz.gianlu.librespot.common;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

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

    @NotNull
    public static Map<String, String> parseHeaders(@NotNull InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String header;
        while (!(header = Utils.readLine(in)).isEmpty()) {
            String[] split = Utils.split(header, ':');
            headers.put(split[0], split[1].trim());
        }

        return headers;
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
