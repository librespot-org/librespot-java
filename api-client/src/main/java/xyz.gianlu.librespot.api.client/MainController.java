package xyz.gianlu.librespot.api.client;

import com.google.gson.JsonObject;
import com.sun.javafx.collections.ObservableListWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author Gianlu
 */
public class MainController implements NetworkThread.Listener, NetworkThread.Callback {
    public TextField address;
    public Button disconnect;
    public Button connect;
    public TextField jsonrpcId;
    public TextField jsonrpcMethod;
    public TextArea jsonrpcParams;
    public ListView<String> responses;
    private NetworkThread networkThread;

    public MainController() {
    }

    public void initialize() {
        responses.setItems(new ObservableListWrapper<>(new ArrayList<>()));
    }

    public void clickedConnect(MouseEvent mouseEvent) {
        networkThread = new NetworkThread(address.getText(), this);
    }

    public void clickedDisconnect(MouseEvent mouseEvent) {
        if (networkThread != null) {
            networkThread.close();
            networkThread = null;
        }
    }

    @Override
    public void connected() {
        connect.setVisible(false);
        disconnect.setVisible(true);
        address.setDisable(true);
    }

    @Override
    public void closed() {
        connect.setVisible(true);
        disconnect.setVisible(false);
        address.setDisable(false);
    }

    @Override
    public void unknownResponse(@NotNull JsonObject obj) {
        System.out.println(obj);
    }

    public void clickedGenerateId(MouseEvent mouseEvent) {
        jsonrpcId.setText(String.valueOf(ThreadLocalRandom.current().nextInt(1000)));
    }

    public void clickedSend(MouseEvent mouseEvent) {
        if (networkThread == null) return;

        networkThread.send(jsonrpcId.getText(), jsonrpcMethod.getText(), jsonrpcParams.getText(), this);
    }

    @Override
    public void response(@NotNull JsonObject json) {
        responses.getItems().add(json.toString());
    }
}
