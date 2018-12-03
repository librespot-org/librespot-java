package xyz.gianlu.librespot.api.client;

import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.input.MouseEvent;
import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public class ResponseDialogController {
    public TextArea jsonField;

    void setJson(@NotNull String json) {
        jsonField.setText(json);
    }

    public void clickedClose(MouseEvent event) {
        ((Node) (event.getSource())).getScene().getWindow().hide();
    }
}
