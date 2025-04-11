/* ======== Main.java ======== */
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
        System.out.println(getCurrentTimestamp()+" - Applicazione Principale: start() chiamato."); // Tradotto
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/home-page-view.fxml"));
            Parent root = loader.load();
            primaryStage.setTitle("Tris Client"); // Mantenuto nome proprio
            Scene scene = new Scene(root);
            String css = this.getClass().getResource("/org/trisclient/trisclient/style.css").toExternalForm();
            if (css != null) {
                scene.getStylesheets().add(css);
                System.out.println(getCurrentTimestamp()+" - Applicazione Principale: CSS applicato."); // Tradotto
            } else {
                System.err.println(getCurrentTimestamp()+" - Applicazione Principale: File CSS non trovato!"); // Tradotto
            }
            primaryStage.setScene(scene);

            primaryStage.setOnCloseRequest(event -> {
                System.out.println(getCurrentTimestamp()+" - Applicazione Principale: Richiesta chiusura finestra."); // Tradotto
                if (HomePageController.networkServiceInstance != null && HomePageController.networkServiceInstance.isConnected()) {
                    System.out.println(getCurrentTimestamp()+" - Applicazione Principale: Disconnessione NetworkService per chiusura finestra."); // Tradotto
                    HomePageController.networkServiceInstance.disconnect();
                }
                System.out.println(getCurrentTimestamp()+" - Applicazione Principale: Uscita dall'applicazione."); // Tradotto
            });


            primaryStage.show();
            System.out.println(getCurrentTimestamp()+" - Applicazione Principale: Stage primario mostrato."); // Tradotto
        } catch (IOException e) {
            System.err.println(getCurrentTimestamp()+" - Applicazione Principale: ERRORE FATALE caricamento FXML!"); // Tradotto
            e.printStackTrace();
            Platform.exit();
        } catch (Exception e) {
            System.err.println(getCurrentTimestamp()+" - Applicazione Principale: ERRORE INASPETTATO durante l'avvio!"); // Tradotto
            e.printStackTrace();
            Platform.exit();
        }
    }

    public static void main(String[] args) {
        System.out.println("-------------------- Avvio Client Tris --------------------"); // Tradotto
        launch(args);
        System.out.println("-------------------- Chiusura Client Tris ---------------------"); // Tradotto
    }
}