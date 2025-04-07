package org.trisclient.trisclient;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


public class Main extends Application {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    @Override
    public void start(Stage primaryStage) {
        System.out.println(getCurrentTimestamp()+" - Main Application: start() called.");
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/home-page-view.fxml"));
            Parent root = loader.load();
            primaryStage.setTitle("Tris Client");
            Scene scene = new Scene(root);
            String css = this.getClass().getResource("/org/trisclient/trisclient/style.css").toExternalForm();
            if (css != null) {
                scene.getStylesheets().add(css);
                System.out.println(getCurrentTimestamp()+" - Main Application: Applied CSS.");
            } else {
                System.err.println(getCurrentTimestamp()+" - Main Application: CSS file not found!");
            }
            primaryStage.setScene(scene);

            primaryStage.setOnCloseRequest(event -> {
                System.out.println(getCurrentTimestamp()+" - Main Application: Window close requested.");
                if (HomePageController.networkServiceInstance != null && HomePageController.networkServiceInstance.isConnected()) {
                    System.out.println(getCurrentTimestamp()+" - Main Application: Disconnecting NetworkService due to window close.");
                    HomePageController.networkServiceInstance.disconnect();
                }
                System.out.println(getCurrentTimestamp()+" - Main Application: Exiting application.");
            });


            primaryStage.show();
            System.out.println(getCurrentTimestamp()+" - Main Application: Primary stage shown.");
        } catch (IOException e) {
            System.err.println(getCurrentTimestamp()+" - Main Application: FATAL ERROR loading FXML!");
            e.printStackTrace();
            Platform.exit();
        } catch (Exception e) {
            System.err.println(getCurrentTimestamp()+" - Main Application: UNEXPECTED ERROR during start!");
            e.printStackTrace();
            Platform.exit();
        }
    }

    public static void main(String[] args) {
        System.out.println("-------------------- Tris Client Starting --------------------");
        launch(args);
        System.out.println("-------------------- Tris Client Exiting ---------------------");
    }
}