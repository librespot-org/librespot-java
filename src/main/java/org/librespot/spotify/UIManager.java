package org.librespot.spotify;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.librespot.spotify.proto.Authentication;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

/**
 * @author Gianlu
 */
public class UIManager {
    private final Terminal terminal;
    private final Screen screen;
    private final MultiWindowTextGUI gui;
    private final LoginWindowHolder loginWindow;
    private final SpotifyWindowHolder spotifyWindow;
    private Session session;

    public UIManager() throws IOException {
        terminal = new DefaultTerminalFactory().setInitialTerminalSize(new TerminalSize(100, 40)).createTerminal();
        screen = new TerminalScreen(terminal);
        gui = new MultiWindowTextGUI(new SeparateTextGUIThread.Factory(), screen, new DefaultWindowManager(), null, new EmptySpace(TextColor.ANSI.BLUE));

        loginWindow = new LoginWindowHolder();
        spotifyWindow = new SpotifyWindowHolder();
    }

    private static Window createDialog(@Nullable String title, @NotNull String message) {
        Panel panel = new Panel(new GridLayout(1));
        panel.addComponent(new Label(message));

        Window window = new BasicWindow(title == null ? "" : title);
        window.setHints(Arrays.asList(Window.Hint.MODAL, Window.Hint.CENTERED));
        window.setComponent(panel);

        return window;
    }

    public void main() throws IOException {
        screen.startScreen();
        ((AsynchronousTextGUIThread) gui.getGUIThread()).start();

        session = Session.create();

        Window dialog = createDialog(null, "Connecting to Spotify servers...");
        session.connectAsync(gui, new Session.OnSuccess() {
            @Override
            public void success() {
                dialog.close();
            }

            @Override
            public void failed(@NotNull Exception ex) {
                dialog.close();
                showErrorDialog(ex);
            }
        });

        gui.addWindowAndWait(dialog);
        gui.addWindowAndWait(loginWindow.window);
    }

    private void showErrorDialog(@NotNull Exception ex) {
        ex.printStackTrace(); // TODO: Create error dialog AND TEST IT
    }

    private void doLogin(@NotNull String username, @NotNull String password, boolean save) {
        Window dialog = createDialog(null, "Authenticating...");

        session.authenticateUserPassAsync(username, password, gui, new Session.OnResult<Authentication.APWelcome>() {
            @Override
            public void result(Authentication.@NotNull APWelcome result) {
                dialog.close();

                if (save) Configuration.get().saveLogin(username, password);
            }

            @Override
            public void failed(@NotNull Exception ex) {
                dialog.close();
                showErrorDialog(ex);
            }
        });

        gui.removeWindow(loginWindow.window);
        gui.addWindowAndWait(dialog);

        gui.addWindowAndWait(spotifyWindow.window);
    }

    private class SpotifyWindowHolder extends WindowHolder {
        SpotifyWindowHolder() {
            super(new BasicWindow());

            Panel panel = new Panel(new GridLayout(2));

            ActionListBox playlists = new ActionListBox();
            panel.addComponent(playlists);
            playlists.addItem("test1", null);
            playlists.addItem("test2", null);
            playlists.addItem("test3", null);
            playlists.addItem("test4", null);
            playlists.addItem("test5", null);
            playlists.addItem("test6", null);
            playlists.addItem("test7", null);


            panel.addComponent(new Label("This is the main Spotify panel!"));

            // TODO: Main Spotify panel

            window.setHints(Collections.singleton(Window.Hint.CENTERED));
            window.setComponent(panel);
        }
    }

    private abstract class WindowHolder {
        final Window window;

        WindowHolder(Window window) {
            this.window = window;
        }
    }

    private class LoginWindowHolder extends WindowHolder {
        LoginWindowHolder() {
            super(new BasicWindow());

            Panel panel = new Panel(new GridLayout(2));

            panel.addComponent(new Label("librespot-java").addStyle(SGR.BOLD),
                    GridLayout.createLayoutData(GridLayout.Alignment.CENTER, GridLayout.Alignment.FILL, true, false, 2, 1));

            panel.addComponent(new EmptySpace(TerminalSize.ONE), GridLayout.createLayoutData(GridLayout.Alignment.CENTER, GridLayout.Alignment.FILL, true, false, 2, 1));

            panel.addComponent(new Label("Username"), GridLayout.createHorizontallyEndAlignedLayoutData(1));
            final TextBox username = new TextBox(new TerminalSize(20, 1));
            panel.addComponent(username);

            panel.addComponent(new Label("Password"), GridLayout.createHorizontallyEndAlignedLayoutData(1));
            final TextBox password = new TextBox(new TerminalSize(20, 1)).setMask('*');
            panel.addComponent(password);

            panel.addComponent(new EmptySpace(TerminalSize.ONE), GridLayout.createLayoutData(GridLayout.Alignment.CENTER, GridLayout.Alignment.FILL, true, false, 2, 1));

            final CheckBox rememberMe = new CheckBox("Remember me");
            panel.addComponent(rememberMe, GridLayout.createLayoutData(GridLayout.Alignment.CENTER, GridLayout.Alignment.FILL, true, false, 2, 1));

            panel.addComponent(new EmptySpace(TerminalSize.ONE), GridLayout.createLayoutData(GridLayout.Alignment.CENTER, GridLayout.Alignment.FILL, true, false, 2, 1));

            Button login = new Button("Login!", () -> {
                String userStr = username.getText();
                if (userStr.isEmpty()) throw new IllegalArgumentException("Username must not be empty!");

                String passwdStr = password.getText();
                if (passwdStr.isEmpty()) throw new IllegalArgumentException("Password must not be empty!");

                doLogin(userStr, passwdStr, rememberMe.isChecked());
            });
            panel.addComponent(login, GridLayout.createLayoutData(GridLayout.Alignment.CENTER, GridLayout.Alignment.FILL, true, false, 2, 1));

            Configuration.Login savedLogin = Configuration.get().getSavedLogin();
            if (savedLogin != null) {
                username.setText(savedLogin.username);
                password.setText(savedLogin.password);
                rememberMe.setChecked(true);
            }

            window.setHints(Collections.singleton(Window.Hint.CENTERED));
            window.setComponent(panel);
        }
    }
}
