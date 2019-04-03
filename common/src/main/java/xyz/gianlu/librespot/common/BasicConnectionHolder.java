package xyz.gianlu.librespot.common;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;

/**
 * @author Gianlu
 */
public class BasicConnectionHolder {
    public final String host;
    private final String path;
    private final int port;

    public BasicConnectionHolder(@NotNull String str) throws MalformedURLException {
        URL url = new URL(str);

        this.host = url.getHost();
        this.port = url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
        this.path = url.getPath() + "?" + url.getQuery();
    }

    @NotNull
    public Socket createSocket() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    public void sendHeadRequest(@NotNull OutputStream out) throws IOException {
        out.write("HEAD ".getBytes());
        out.write(path.getBytes());
        out.write(" HTTP/1.1".getBytes());
        out.write("\r\nHost: ".getBytes());
        out.write(host.getBytes());
        out.write("\r\n\r\n".getBytes());
        out.flush();
    }

    public void sendGetRequest(@NotNull OutputStream out, int rangeStart, int rangeEnd) throws IOException {
        out.write("GET ".getBytes());
        out.write(path.getBytes());
        out.write(" HTTP/1.1".getBytes());
        out.write("\r\nHost: ".getBytes());
        out.write(host.getBytes());
        out.write("\r\nRange: bytes=".getBytes());
        out.write(String.valueOf(rangeStart).getBytes());
        out.write("-".getBytes());
        out.write(String.valueOf(rangeEnd).getBytes());
        out.write("\r\n\r\n".getBytes());
        out.flush();
    }
}
