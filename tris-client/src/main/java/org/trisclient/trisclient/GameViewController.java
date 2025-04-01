package org.trisclient.trisclient;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URL;
import java.util.ResourceBundle;

public class GameViewController implements Initializable {

    @FXML
    private Label TextTurno;
    @FXML
    private GridPane gridPane;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    // Array di bottoni per rappresentare la griglia di gioco
    private Button[][] buttons = new Button[3][3];

    // Flag per sapere se è il turno dell'utente
    private volatile boolean myTurn = false;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Crea la griglia 3x3 inserendo dei Button nel GridPane
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Button btn = new Button(" ");
                btn.setPrefSize(70, 70);
                final int row = i;
                final int col = j;
                btn.setOnAction(e -> handleCellClick(row, col));
                buttons[i][j] = btn;
                gridPane.add(btn, j, i);
            }
        }

        // Connessione al server in un thread in background
        new Thread(() -> {
            try {
                socket = new Socket("127.0.0.1", 12345);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                String line;
                // Legge i messaggi dal server riga per riga
                while ((line = in.readLine()) != null) {
                    String msg = line;
                    // Usa Platform.runLater per aggiornare l’interfaccia (JavaFX UI thread)
                    Platform.runLater(() -> processServerMessage(msg));
                }
            } catch (IOException ex) {
                ex.printStackTrace();
                Platform.runLater(() -> TextTurno.setText("Errore di connessione al server."));
            }
        }).start();
    }

    // Metodo chiamato quando l'utente clicca su una cella della griglia
    private void handleCellClick(int row, int col) {
        if (myTurn) {
            // Invia al server la mossa nel formato "riga col"
            String move = row + " " + col;
            out.println(move);
            myTurn = false;
            TextTurno.setText("Aspetta il turno dell'altro giocatore...");
        }
    }

    // Elabora i messaggi ricevuti dal server e aggiorna l’interfaccia
    private void processServerMessage(String msg) {
        // Se il messaggio contiene il disegno della board (usa caratteri "|" e "-"),
        // lo parsifichiamo e aggiorniamo i bottoni.
        if (msg.contains("|") || msg.contains("-")) {
            String[] lines = msg.split("\n");
            int row = 0;
            // Il server invia il board in formato testuale:
            // " X | O |  "
            // "-----------"
            // "   | X | O"
            // "-----------"
            // " O |   | X"
            for (String line : lines) {
                if (line.contains("|")) {
                    String[] cells = line.split("\\|");
                    int col = 0;
                    for (String cell : cells) {
                        String c = cell.trim();
                        if (!c.isEmpty()) {
                            buttons[row][col].setText(c);
                        } else {
                            buttons[row][col].setText(" ");
                        }
                        col++;
                    }
                    row++;
                }
            }
        }

        // Se il messaggio contiene il prompt per inserire la mossa, abilita il turno
        if (msg.contains("inserisci la tua mossa")) {
            myTurn = true;
            TextTurno.setText("Il tuo turno!");
        }
        // Se il messaggio contiene il risultato della partita, visualizzalo
        if (msg.contains("Hai vinto") || msg.contains("Hai perso") || msg.contains("Pareggio")) {
            TextTurno.setText(msg);
            myTurn = false;
        }
    }
}
