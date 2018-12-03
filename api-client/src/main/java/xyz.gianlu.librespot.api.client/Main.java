package xyz.gianlu.librespot.api.client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * @author Gianlu
 */
public class Main extends Application {
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader();
        Parent root = loader.load(getClass().getClassLoader().getResourceAsStream("main.fxml"));
        primaryStage.setTitle("librespot-java API client");
        primaryStage.setScene(new Scene(root, 600, 400));
        primaryStage.show();
    }
}
