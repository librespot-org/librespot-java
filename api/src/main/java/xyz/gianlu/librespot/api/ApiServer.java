package xyz.gianlu.librespot.api;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Gianlu
 */
public class ApiServer implements Closeable {
    private final static Logger LOGGER = Logger.getLogger(ApiServer.class);
    private final Looper looper;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public ApiServer(int port) throws IOException {
        executorService.execute(looper = new Looper(port));
    }

    @Override
    public void close() throws IOException {
        looper.stop();
    }

    private class ClientRunner implements Runnable, Closeable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        ClientRunner(@NotNull Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        @Override
        public void run() {
            try {
                handshake();
            } catch (IOException ex) {
                LOGGER.fatal("Failed handling client!", ex);
            }
        }

        private void handshake() throws IOException {
            String sl = Utils.readLine(in);

            // TODO: Websocket upgrade
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private class Looper implements Runnable {
        private final ServerSocket serverSocket;
        private volatile boolean shouldStop = false;

        Looper(int port) throws IOException {
            serverSocket = new ServerSocket(port);
        }

        @Override
        public void run() {
            while (!shouldStop && !serverSocket.isClosed()) {
                try {
                    executorService.execute(new ClientRunner(serverSocket.accept()));
                } catch (IOException ex) {
                    LOGGER.fatal("Failed accepting connection!", ex);
                }
            }
        }

        private void stop() throws IOException {
            shouldStop = true;
            serverSocket.close();
        }
    }
}
