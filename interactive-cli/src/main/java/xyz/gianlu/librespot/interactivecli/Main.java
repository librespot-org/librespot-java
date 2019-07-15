package xyz.gianlu.librespot.interactivecli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.spi.LoggingEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.Version;
import xyz.gianlu.librespot.common.config.Configuration;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class);
    private static final Pattern LOG_LEVEL_PATTERN = Pattern.compile("^.+?\\s([A-Z]+)");
    private final List<String> logEntries = new ArrayList<>();
    private final CommandsHandler commands;
    private TerminalPosition lastLogPosition = new TerminalPosition(0, 0);

    public Main(@NotNull Terminal terminal, @NotNull Session.Builder session) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, MercuryClient.MercuryException {
        terminal.addResizeListener((t, newSize) -> {
            try {
                drawCommandInputLine(terminal);
                redrawLogs(terminal);
            } catch (IOException ex) {
                LOGGER.error("Failed redrawing resized window.", ex);
            }
        });

        setup(terminal);

        this.commands = new CommandsHandler(session.create());

        new Thread(() -> {
            try {
                draw(terminal);
            } catch (IOException ex) {
                LOGGER.error("Failed drawing.", ex);
            }
        }).start();
    }

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, MercuryClient.MercuryException {
        File customCommands = null;
        for (String arg : args) {
            if (arg.startsWith("--custom-commands=")) {
                int index = arg.indexOf('=');
                customCommands = new File(arg.substring(index + 1));
            }
        }

        Session.Builder s = new Session.Builder(getConfig());

        DefaultTerminalFactory factory = new DefaultTerminalFactory();
        factory.setTerminalEmulatorTitle(Version.versionString());
        Terminal terminal = factory.createTerminal();

        Main main = new Main(terminal, s);
        if (customCommands != null) {
            main.loadCustomCommands(customCommands);
            LOGGER.info("Loaded custom commands!");
        }
    }

    @Nullable
    private static TextColor colorForLogLevel(@NotNull String level) {
        switch (level) {
            case "TRACE":
            case "DEBUG":
            case "INFO":
                return TextColor.ANSI.WHITE;
            case "WARN":
                return TextColor.ANSI.YELLOW;
            case "FATAL":
            case "ERROR":
                return TextColor.ANSI.RED;
            default:
                return null;
        }
    }

    private static Configuration getConfig() throws IOException {
        File configFile = Optional.ofNullable(System.getProperty("config.file")).map(File::new).orElseGet(() -> {
            LOGGER.info("No external application.yml file found. Please check if env property 'config.file' is set");
            return new File(ClassLoader.getSystemResource("application.yml").getFile());
        });
        return new ObjectMapper(new YAMLFactory()).readValue(configFile, Configuration.class);
    }

    private void loadCustomCommands(@NotNull File file) throws FileNotFoundException {
        JsonObject obj = new JsonParser().parse(new FileReader(file)).getAsJsonObject();
        if (!obj.has("commands")) return;

        for (JsonElement elm : obj.getAsJsonArray("commands")) commands.addCustomCommand(elm.getAsJsonObject());
    }

    private void drawCommandInputLine(@NotNull Terminal terminal) throws IOException {
        terminal.setCursorPosition(new TerminalPosition(0, terminal.getTerminalSize().getRows() - 1));
        terminal.putCharacter('>');
        terminal.putCharacter('>');
        terminal.putCharacter(' ');
        terminal.flush();

        terminal.setCursorPosition(3, terminal.getTerminalSize().getRows() - 1);
    }

    private void draw(@NotNull Terminal terminal) throws IOException {
        while (true) {
            KeyStroke stroke = terminal.readInput();
            if (stroke.getKeyType() == KeyType.Character) {
                if (stroke.isCtrlDown() && stroke.getCharacter() == 'v') {
                    Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
                    if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        try {
                            String str = (String) t.getTransferData(DataFlavor.stringFlavor);
                            for (char c : str.toCharArray())
                                terminal.putCharacter(c);
                        } catch (UnsupportedFlavorException ignored) {
                        }
                    }
                } else {
                    terminal.putCharacter(stroke.getCharacter());
                }
            } else if (stroke.getKeyType() == KeyType.Backspace) {
                TerminalPosition del = terminal.getCursorPosition().withRelativeColumn(-1);
                if (del.getColumn() < 3) continue;

                terminal.newTextGraphics().setCharacter(del, TextCharacter.DEFAULT_CHARACTER);
                terminal.setCursorPosition(del);
            } else if (stroke.getKeyType() == KeyType.Enter) {
                int lastRow = terminal.getTerminalSize().getRows() - 1;
                int width = terminal.getTerminalSize().getColumns();
                char[] line = new char[width - 3];
                TextGraphics graphics = terminal.newTextGraphics();
                for (int i = 3; i < width; i++) {
                    line[i - 3] = graphics.getCharacter(i, lastRow).getCharacter();
                    graphics.setCharacter(i, lastRow, TextCharacter.DEFAULT_CHARACTER);
                }

                terminal.setCursorPosition(3, terminal.getTerminalSize().getRows() - 1);

                commands.handle(new String(line).trim());
            }

            terminal.flush();
        }
    }

    private void newLine(@NotNull Terminal terminal) throws IOException {
        if (lastLogPosition.getRow() == terminal.getTerminalSize().getRows() - 2) {
            lastLogPosition = new TerminalPosition(0, 0);
            return;
        }

        lastLogPosition = new TerminalPosition(0, lastLogPosition.getRow() + 1);
    }

    private void drawLogEntries(@NotNull Terminal terminal, int num) throws IOException {
        if (logEntries.isEmpty()) return;

        if (num > logEntries.size()) num = logEntries.size();

        for (int i = 0; i < num; i++)
            drawTextLine(terminal, logEntries.get(logEntries.size() - i - 1), num - i - 1);
    }

    private void drawTextLine(@NotNull Terminal terminal, @NotNull String str, int row) throws IOException {
        TerminalPosition userPos = new TerminalPosition(terminal.getCursorPosition().getColumn(), terminal.getCursorPosition().getRow());

        int colorFrom = -1;
        int colorTo = -1;
        TextColor color = null;

        Matcher matcher = LOG_LEVEL_PATTERN.matcher(str);
        if (matcher.find()) {
            String lvl = matcher.group(1);
            color = colorForLogLevel(lvl);
            colorFrom = str.indexOf(lvl);
            colorTo = colorFrom + lvl.length();
        }

        boolean lastWasR = false;
        for (int i = 0; i < terminal.getTerminalSize().getColumns(); i++) {
            char c;
            if (i < str.length()) c = str.charAt(i);
            else c = TextCharacter.DEFAULT_CHARACTER.getCharacter();

            if (c == '\n') {
                newLine(terminal);
                lastWasR = false;
                terminal.newTextGraphics().setCharacter(i, row, TextCharacter.DEFAULT_CHARACTER);
                continue;
            } else if (c == '\r') {
                lastWasR = true;
                terminal.newTextGraphics().setCharacter(i, row, TextCharacter.DEFAULT_CHARACTER);
                continue;
            } else {
                if (lastWasR) {
                    newLine(terminal);
                    lastWasR = false;
                    terminal.newTextGraphics().setCharacter(i, row, TextCharacter.DEFAULT_CHARACTER);
                    continue;
                }
            }

            if (color != null && i >= colorFrom && i <= colorTo) {
                terminal.newTextGraphics().setCharacter(i, row, new TextCharacter(c, color, null));
            } else {
                terminal.newTextGraphics().setCharacter(i, row, c);
            }
        }

        terminal.setCursorPosition(userPos);
        terminal.flush();
    }

    private void printLog(@NotNull Terminal terminal, String str) throws IOException {
        logEntries.add(str);
        redrawLogs(terminal);
    }

    private void redrawLogs(@NotNull Terminal terminal) throws IOException {
        drawLogEntries(terminal, terminal.getTerminalSize().getRows() - 1);
    }

    private void setup(@NotNull Terminal terminal) throws IOException {
        terminal.clearScreen();
        terminal.setCursorPosition(0, 0);

        drawCommandInputLine(terminal);

        Logger.getRootLogger().addAppender(new AppenderSkeleton() {
            {
                setLayout(new PatternLayout("%d{HH:mm:ss} %-5p %c{1}:%L - %m%n"));
            }

            @Override
            protected void append(LoggingEvent event) {
                String str = getLayout().format(event);
                try {
                    printLog(terminal, str);
                } catch (IOException ex) {
                    LOGGER.error("Failed printing log message!", ex);
                }
            }

            @Override
            public void close() {
            }

            @Override
            public boolean requiresLayout() {
                return true;
            }
        });
    }
}
