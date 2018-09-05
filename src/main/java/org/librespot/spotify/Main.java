package org.librespot.spotify;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.mercury.MercuryClient;
import org.librespot.spotify.mercury.MercuryRequests;
import org.librespot.spotify.mercury.OnResult;
import org.librespot.spotify.mercury.model.PlaylistId;
import org.librespot.spotify.mercury.model.TrackId;
import org.librespot.spotify.proto.Metadata;
import org.librespot.spotify.proto.Playlist4Changes;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * @author Gianlu
 */
public class Main {

    public static void main(String[] args) throws IOException {
        Terminal terminal = new DefaultTerminalFactory().setInitialTerminalSize(new TerminalSize(100, 40)).createTerminal();
        Screen screen = new TerminalScreen(terminal);
        screen.startScreen();

        Panel panel = new Panel(new GridLayout(2));
        panel.setPreferredSize(new TerminalSize(50, 7));
        panel.setPosition(new TerminalPosition(25, 10));

        panel.addComponent(new Label("librespot-java").addStyle(SGR.BOLD),
                GridLayout.createLayoutData(GridLayout.Alignment.CENTER, GridLayout.Alignment.FILL, true, false, 2, 1));

        panel.addComponent(new EmptySpace(new TerminalSize(1, 1)), GridLayout.createLayoutData(GridLayout.Alignment.CENTER, GridLayout.Alignment.FILL, true, false, 2, 1));

        panel.addComponent(new Label("Username"), GridLayout.createHorizontallyEndAlignedLayoutData(1));
        final TextBox username = new TextBox(new TerminalSize(20, 1));
        panel.addComponent(username);

        panel.addComponent(new Label("Password"), GridLayout.createHorizontallyEndAlignedLayoutData(1));
        final TextBox password = new TextBox(new TerminalSize(20, 1)).setMask('*');
        panel.addComponent(password);

        panel.addComponent(new EmptySpace(new TerminalSize(1, 1)), GridLayout.createLayoutData(GridLayout.Alignment.CENTER, GridLayout.Alignment.FILL, true, false, 2, 1));

        Button login = new Button("Login!", new Runnable() {
            @Override
            public void run() {
                try {
                    startSession(username.getText(), password.getText());
                } catch (IOException | GeneralSecurityException | Session.SpotifyAuthenticationException e) {
                    e.printStackTrace();
                }
            }
        });
        panel.addComponent(login, GridLayout.createLayoutData(GridLayout.Alignment.CENTER, GridLayout.Alignment.FILL, true, false, 2, 1));

        BasicWindow window = new BasicWindow();
        window.setComponent(panel);

        MultiWindowTextGUI gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLUE));
        gui.addWindowAndWait(window);
    }

    private static void startSession(String username, String password) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException {
        Session session = Session.create();
        session.connect();
        session.authenticateUserPass(username, password);

        MercuryClient client = session.mercury();
        client.request(MercuryRequests.getRootPlaylists(username), new OnResult<Playlist4Changes.SelectedListContent>() {
            @Override
            public void result(Playlist4Changes.@NotNull SelectedListContent result) {
                System.out.println(result);

                PlaylistId id = new PlaylistId(result.getContents().getItems(0));
                client.request(MercuryRequests.getPlaylist(id), new OnResult<Playlist4Changes.SelectedListContent>() {
                    @Override
                    public void result(Playlist4Changes.@NotNull SelectedListContent result) {
                        System.out.println(result);

                        TrackId id = new TrackId(result.getContents().getItems(0));
                        client.request(MercuryRequests.getTrack(id), new OnResult<Metadata.Track>() {
                            @Override
                            public void result(Metadata.@NotNull Track result) {
                                System.out.println(result);
                            }

                            @Override
                            public void failed(@NotNull Exception ex) {
                                ex.printStackTrace();
                            }
                        });
                    }

                    @Override
                    public void failed(@NotNull Exception ex) {
                        ex.printStackTrace();
                    }
                });
            }

            @Override
            public void failed(@NotNull Exception ex) {
                ex.printStackTrace();
            }
        });
    }
}
