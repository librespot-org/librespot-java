package xyz.gianlu.librespot.interactivecli;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class Main {

    private static void draw(@NotNull Terminal terminal) throws IOException {
        terminal.clearScreen();
        terminal.setCursorPosition(0, 0);

        terminal.putCharacter('H');
        terminal.putCharacter('e');
        terminal.putCharacter('l');
        terminal.putCharacter('l');
        terminal.putCharacter('o');
        terminal.putCharacter('\n');
        terminal.flush();

        terminal.setCursorPosition(terminal.getCursorPosition().withRow(terminal.getTerminalSize().getRows()));
        terminal.putCharacter('>');
        terminal.putCharacter('>');
        terminal.putCharacter(' ');
        terminal.flush();

        terminal.setCursorPosition(3, terminal.getTerminalSize().getRows() - 1);

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

    public static void main(String[] args) throws IOException {
        DefaultTerminalFactory defaultTerminalFactory = new DefaultTerminalFactory();
        Terminal terminal = defaultTerminalFactory.createTerminal();
        terminal.addResizeListener((t, newSize) -> {
            System.out.println("RESIZE");
        });

        draw(terminal);
    }
}
