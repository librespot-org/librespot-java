package xyz.gianlu.librespot.api.client;

import com.google.gson.JsonObject;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    public TextField mercuryContentType;
    public TextField mercuryUri;
    public ComboBox<String> mercuryMethod;
    public TitledPane receivedContainer;
    public TitledPane sendContainer;
    public TableView<Header> mercuryHeaders;
    public TableColumn<Header, String> mercuryHeaderKeys;
    public TableColumn<Header, String> mercuryHeaderValues;
    private NetworkThread networkThread;

    public MainController() {
    }

    public void initialize() {
        responses.setItems(FXCollections.observableArrayList());
        mercuryMethod.setItems(FXCollections.observableArrayList("GET", "SUB", "UNSUB", "SEND"));
        sendContainer.setDisable(true);
        receivedContainer.setDisable(true);

        mercuryHeaderKeys.setCellValueFactory(param -> param.getValue().key);
        mercuryHeaderKeys.setCellFactory(TextFieldTableCell.forTableColumn());
        mercuryHeaderKeys.setOnEditCommit(t -> {
            Header header = t.getTableView().getItems().get(t.getTablePosition().getRow());
            header.key.set(t.getNewValue());

            if (header.key.isEmpty().get() && header.value.isEmpty().get())
                t.getTableView().getItems().remove(header);
        });

        mercuryHeaderValues.setCellValueFactory(param -> param.getValue().value);
        mercuryHeaderValues.setCellFactory(TextFieldTableCell.forTableColumn());
        mercuryHeaderValues.setOnEditCommit(t -> {
            Header header = t.getTableView().getItems().get(t.getTablePosition().getRow());
            header.value.set(t.getNewValue());

            if (header.key.isEmpty().get() && header.value.isEmpty().get())
                t.getTableView().getItems().remove(header);
        });

        mercuryHeaders.setItems(FXCollections.observableArrayList());
    }

    public void clickedConnect(MouseEvent mouseEvent) {
        try {
            networkThread = new NetworkThread(new URI(address.getText()), this);
        } catch (URISyntaxException ex) {
            showError(ex);
        }
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("An error occurred!");
        alert.setHeaderText("Look, an Error Dialog");
        alert.setContentText("Ooops, there was an error!");

        alert.showAndWait();
    }

    private void showError(@NotNull Throwable throwable) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("An error occurred!");
        alert.setHeaderText(throwable.getMessage());

        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        String exceptionText = sw.toString();

        Label label = new Label("The exception stacktrace was:");
        TextArea textArea = new TextArea(exceptionText);
        textArea.setEditable(false);
        textArea.setWrapText(true);

        textArea.setMaxWidth(Double.MAX_VALUE);
        textArea.setMaxHeight(Double.MAX_VALUE);
        GridPane.setVgrow(textArea, Priority.ALWAYS);
        GridPane.setHgrow(textArea, Priority.ALWAYS);

        GridPane expContent = new GridPane();
        expContent.setMaxWidth(Double.MAX_VALUE);
        expContent.add(label, 0, 0);
        expContent.add(textArea, 0, 1);

        alert.getDialogPane().setExpandableContent(expContent);
        alert.showAndWait();
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
        sendContainer.setDisable(false);
        receivedContainer.setDisable(false);
    }

    @Override
    public void error(@NotNull Throwable ex) {
        showError(ex);
        closed();
    }

    @Override
    public void closed() {
        connect.setVisible(true);
        disconnect.setVisible(false);
        address.setDisable(false);
        sendContainer.setDisable(true);
        receivedContainer.setDisable(true);
    }

    @Override
    public void unknownResponse(@NotNull JsonObject obj) {
        System.out.println(obj);
    }

    public void clickedGenerateId(MouseEvent mouseEvent) {
        jsonrpcId.setText(String.valueOf(ThreadLocalRandom.current().nextInt(1000)));
    }

    public void clickedGeneralSend(MouseEvent mouseEvent) {
        if (networkThread == null) return;

        networkThread.sendGeneral(jsonrpcId.getText(), jsonrpcMethod.getText(), jsonrpcParams.getText(), this);
    }

    public void clickedMercurySend(MouseEvent mouseEvent) {
        if (networkThread == null) return;

        networkThread.sendMercury(mercuryMethod.getSelectionModel().getSelectedItem(), mercuryUri.getText(),
                mercuryContentType.getText(), Header.toMap(mercuryHeaders.getItems()), this);
    }

    @Override
    public void response(@NotNull JsonObject json) {
        responses.getItems().add(json.toString());
    }

    public void clickedMercuryAddEmptyHeader(MouseEvent mouseEvent) {
        mercuryHeaders.getItems().add(new Header("", ""));
        int last = mercuryHeaders.getItems().size() - 1;
        mercuryHeaders.getSelectionModel().select(last);
        mercuryHeaders.getFocusModel().focus(last);
    }

    public void clickedResponse(MouseEvent mouseEvent) throws IOException {
        String json = responses.getSelectionModel().getSelectedItem();

        FXMLLoader fxmlLoader = new FXMLLoader(getClass().getClassLoader().getResource("responseDialog.fxml"));
        Parent parent = fxmlLoader.load();
        ResponseDialogController dialogController = fxmlLoader.getController();
        dialogController.setJson(json);

        Scene scene = new Scene(parent, 400, 300);
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setScene(scene);
        stage.showAndWait();
    }

    public void clickedPlayerPlay(MouseEvent event) {
        if (networkThread == null) return;
        networkThread.sendPlayer("play", this);
    }

    public void clickedPlayerPause(MouseEvent event) {
        if (networkThread == null) return;
        networkThread.sendPlayer("pause", this);
    }

    public void clickedPlayerPlayPause(MouseEvent event) {
        if (networkThread == null) return;
        networkThread.sendPlayer("playPause", this);
    }

    public void clickedPlayerNext(MouseEvent event) {
        if (networkThread == null) return;
        networkThread.sendPlayer("next", this);
    }

    public void clickedPlayerPrev(MouseEvent event) {
        if (networkThread == null) return;
        networkThread.sendPlayer("prev", this);
    }

    public static class Header {
        private final SimpleStringProperty key;
        private final SimpleStringProperty value;

        Header(String key, String value) {
            this.key = new SimpleStringProperty(null, "key", key);
            this.value = new SimpleStringProperty(null, "value", value);
        }

        @NotNull
        static Map<String, String> toMap(List<Header> list) {
            Map<String, String> map = new HashMap<>();
            for (Header header : list) map.put(header.key.getValue(), header.value.getValue());
            return map;
        }
    }

}
