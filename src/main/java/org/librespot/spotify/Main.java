package org.librespot.spotify;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException {
        Configuration.init();

        UIManager handler = new UIManager();
        handler.main();
    }
}
