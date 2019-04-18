package xyz.gianlu.librespot.interactivecli;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextCharacter;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Gianlu
 */
public class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class);
    private static final List<String> logEntries = new ArrayList<>();
    private static TerminalPosition lastLogPosition = new TerminalPosition(0, 0);

    private static void drawCommandInputLine(@NotNull Terminal terminal) throws IOException {
        terminal.setCursorPosition(new TerminalPosition(0, terminal.getTerminalSize().getRows() - 1));
        terminal.putCharacter('>');
        terminal.putCharacter('>');
        terminal.putCharacter(' ');
        terminal.flush();

        terminal.setCursorPosition(3, terminal.getTerminalSize().getRows() - 1);
    }

    private static void draw(@NotNull Terminal terminal) throws IOException {
        terminal.clearScreen();
        terminal.setCursorPosition(0, 0);

        drawCommandInputLine(terminal);

        Logger.getRootLogger().removeAllAppenders();
        Logger.getRootLogger().addAppender(new AppenderSkeleton() {
            {
                setLayout(new PatternLayout("%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n"));
            }

            @Override
            protected void append(LoggingEvent event) {
                String str = getLayout().format(event);
                try {
                    printLog(terminal, str);
                } catch (IOException e) {
                    e.printStackTrace();
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

        new Thread(() -> {
            try {
                spamLogs();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        while (true) {
            KeyStroke stroke = terminal.readInput();
            if (stroke.getKeyType() == KeyType.Character) {
                terminal.putCharacter(stroke.getCharacter());
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

                System.out.println("COMMAND: " + new String(line).trim());
            }

            terminal.flush();
        }
    }

    private static void newLine(Terminal terminal) throws IOException {
        if (lastLogPosition.getRow() == terminal.getTerminalSize().getRows() - 2) {
            lastLogPosition = new TerminalPosition(0, 0);
            return;
        }

        lastLogPosition = new TerminalPosition(0, lastLogPosition.getRow() + 1);
    }

    private static void drawLogEntries(Terminal terminal, int num) throws IOException {
        if (logEntries.isEmpty()) return;

        if (num > logEntries.size()) num = logEntries.size();

        for (int i = 0; i < num; i++)
            drawTextLine(terminal, logEntries.get(logEntries.size() - i - 1), num - i - 1);
    }

    private static void drawTextLine(Terminal terminal, String str, int row) throws IOException {
        TerminalPosition userPos = new TerminalPosition(terminal.getCursorPosition().getColumn(), terminal.getCursorPosition().getRow());

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

            terminal.newTextGraphics().setCharacter(i, row, c);
        }

        terminal.setCursorPosition(userPos);
        terminal.flush();
    }

    private static void printLog(Terminal terminal, String str) throws IOException {
        logEntries.add(str);

        drawLogEntries(terminal, terminal.getTerminalSize().getRows() - 1);
    }

    private static void spamLogs() throws InterruptedException {
        long i = 120;
        while (true) {
            Thread.sleep(100);
            LOGGER.info(i--);
        }
    }

    public static void main(String[] args) throws IOException {
        DefaultTerminalFactory defaultTerminalFactory = new DefaultTerminalFactory();
        Terminal terminal = defaultTerminalFactory.createTerminal();
        terminal.addResizeListener((t, newSize) -> {
            try {
                drawCommandInputLine(terminal);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        draw(terminal);
    }
}
