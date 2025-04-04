package org.trisclient.trisclient;

import javafx.application.Application;
import javafx.application.Platform; // Importa Platform
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException; // Importa IOException
import java.time.LocalDateTime; // Importa
import java.time.format.DateTimeFormatter; // Importa


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
            primaryStage.setScene(new Scene(root));

            // Gestione chiusura finestra
            primaryStage.setOnCloseRequest(event -> {
                System.out.println(getCurrentTimestamp()+" - Main Application: Window close requested.");
                // Notifica NetworkService per chiudere la connessione se attiva
                if (HomePageController.networkServiceInstance != null && HomePageController.networkServiceInstance.isConnected()) {
                    System.out.println(getCurrentTimestamp()+" - Main Application: Disconnecting NetworkService due to window close.");
                    // Invia QUIT se vuoi notificare il server? Opzionale.
                    // HomePageController.networkServiceInstance.sendQuit();
                    HomePageController.networkServiceInstance.disconnect();
                }
                // Platform.exit(); // Assicura che l'applicazione termini
                System.out.println(getCurrentTimestamp()+" - Main Application: Exiting application.");
            });


            primaryStage.show();
            System.out.println(getCurrentTimestamp()+" - Main Application: Primary stage shown.");
        } catch (IOException e) {
            System.err.println(getCurrentTimestamp()+" - Main Application: FATAL ERROR loading FXML!");
            e.printStackTrace();
            // Mostra un alert di errore critico?
            Platform.exit(); // Esci se non possiamo caricare la UI iniziale
        } catch (Exception e) { // Catch generico per altri errori in start
            System.err.println(getCurrentTimestamp()+" - Main Application: UNEXPECTED ERROR during start!");
            e.printStackTrace();
            Platform.exit();
        }
    }

    public static void main(String[] args) {
        // Puoi aggiungere logging qui se necessario prima di launch
        System.out.println("-------------------- Tris Client Starting --------------------");
        launch(args);
        System.out.println("-------------------- Tris Client Exiting ---------------------"); // Verrà stampato alla fine
    }
}