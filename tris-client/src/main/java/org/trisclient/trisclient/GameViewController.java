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
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class GameViewController implements Initializable {

    @FXML
    private Label TextTurno;
    @FXML
    private GridPane gridPane;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    private Button[][] buttons = new Button[3][3];
    private volatile boolean myTurn = false;

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private volatile boolean isReadingBoard = false;
    private List<String> boardLinesBuffer = new ArrayList<>();
    private static final int BOARD_TOTAL_LINES = 5;

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
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

        new Thread(() -> {
            try {
                System.out.println(getCurrentTimestamp() + " - Tentativo di connessione a 127.0.0.1:12345...");
                socket = new Socket("127.0.0.1", 12345);
                System.out.println(getCurrentTimestamp() + " - Connesso al server.");
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                String line;
                while ((line = in.readLine()) != null) {
                    final String msg = line;
                    System.out.println(getCurrentTimestamp() + " - RIGA GREZZA RICEVUTA: [" + msg + "]");
                    Platform.runLater(() -> processServerMessage(msg));
                }
                System.out.println(getCurrentTimestamp() + " - Connessione chiusa dal server.");
                Platform.runLater(() -> TextTurno.setText("Disconnesso dal server."));

            } catch (IOException ex) {
                ex.printStackTrace();
                System.err.println(getCurrentTimestamp() + " - Errore di I/O nel thread di rete: " + ex.getMessage());
                Platform.runLater(() -> TextTurno.setText("Errore di connessione/comunicazione."));
            } finally {
                try {
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                        System.out.println(getCurrentTimestamp() + " - Socket chiuso nel blocco finally.");
                    }
                } catch (IOException e) {
                    System.err.println(getCurrentTimestamp() + " - Errore durante la chiusura del socket: " + e.getMessage());
                }
            }
        }).start();
    }

    private void handleCellClick(int row, int col) {
        System.out.println(getCurrentTimestamp() + " - Cella cliccata: " + row + " " + col + " (myTurn=" + myTurn + ")");
        if (myTurn) {
            String move = row + " " + col;
            System.out.println(getCurrentTimestamp() + " - Invio mossa: [" + move + "]");
            out.println(move);
            myTurn = false;
            Platform.runLater(() -> TextTurno.setText("Mossa inviata. Aspetta il turno dell'altro giocatore..."));
        } else {
            System.out.println(getCurrentTimestamp() + " - Click ignorato: non è il mio turno.");
        }
    }

    private void processServerMessage(String msg) {
        if (isReadingBoard) {
            boardLinesBuffer.add(msg);
            if (boardLinesBuffer.size() == BOARD_TOTAL_LINES) {
                System.out.println(getCurrentTimestamp() + " - Board completa ricevuta (" + boardLinesBuffer.size() + " righe). Elaborazione...");
                parseAndDisplayBoard(new ArrayList<>(boardLinesBuffer));
                boardLinesBuffer.clear();
                isReadingBoard = false;
            } else {
                System.out.println(getCurrentTimestamp() + " - Riga board aggiunta al buffer (" + boardLinesBuffer.size() + "/" + BOARD_TOTAL_LINES + ")");
            }
        } else if (isPotentialBoardStart(msg)) {
            System.out.println(getCurrentTimestamp() + " - Rilevato potenziale inizio board. Avvio buffering...");
            isReadingBoard = true;
            boardLinesBuffer.clear();
            boardLinesBuffer.add(msg);
            if (boardLinesBuffer.size() == BOARD_TOTAL_LINES) {
                System.out.println(getCurrentTimestamp() + " - Board completa ricevuta immediatamente. Elaborazione...");
                parseAndDisplayBoard(new ArrayList<>(boardLinesBuffer));
                boardLinesBuffer.clear();
                isReadingBoard = false;
            }
        } else {
            System.out.println(getCurrentTimestamp() + " - Messaggio non-board ricevuto. Elaborazione come comando...");
            processCommandMessage(msg);
        }
    }

    private boolean isPotentialBoardStart(String msg) {
        return msg.contains("|");
    }

    private void processCommandMessage(String msg) {
        System.out.println(getCurrentTimestamp() + " - Processing command message: [" + msg + "]");

        if (msg.contains("inserisci la tua mossa")) {
            System.out.println(getCurrentTimestamp() + " - Rilevato messaggio 'inserisci mossa'. Abilito il turno.");
            myTurn = true;
            TextTurno.setText("Il tuo turno!");
        } else if (msg.contains("Hai vinto") || msg.contains("Hai perso") || msg.contains("Pareggio")) {
            System.out.println(getCurrentTimestamp() + " - Rilevato messaggio di fine partita: " + msg);
            TextTurno.setText(msg.trim());
            myTurn = false;
            for(int i=0; i<3; i++) for(int j=0; j<3; j++) buttons[i][j].setDisable(true);
            System.out.println(getCurrentTimestamp() + " - Bottoni disabilitati.");
        } else if (msg.contains("Benvenuto")) {
            System.out.println(getCurrentTimestamp() + " - Messaggio di benvenuto ricevuto.");
            TextTurno.setText("Connesso. In attesa dell'altro giocatore...");
        } else if (msg.contains("SERVER_SHUTDOWN")) {
            System.out.println(getCurrentTimestamp() + " - Ricevuto messaggio di shutdown dal server.");
            TextTurno.setText("Il server si sta arrestando.");
            myTurn = false;
            for(int i=0; i<3; i++) for(int j=0; j<3; j++) buttons[i][j].setDisable(true);
        } else if (msg.contains("L'altro giocatore si è disconnesso")) {
            System.out.println(getCurrentTimestamp() + " - Ricevuto messaggio di disconnessione avversario.");
            TextTurno.setText("L'avversario si è disconnesso.");
            myTurn = false;
            for(int i=0; i<3; i++) for(int j=0; j<3; j++) buttons[i][j].setDisable(true);
        } else if (msg.contains("non valido") || msg.contains("non valida")) {
            System.out.println(getCurrentTimestamp() + " - Ricevuto messaggio di errore mossa: " + msg);
            TextTurno.setText("Server: " + msg.trim());
        }
        else {
            if (!msg.trim().isEmpty()) {
                System.out.println(getCurrentTimestamp() + " - Messaggio server non classificato (ignorando): " + msg);
            }
        }
    }

    private void parseAndDisplayBoard(List<String> completeBoardLines) {
        System.out.println(getCurrentTimestamp() + " - Parsing and displaying full board UI...");
        int boardRowIndex = 0;

        for (String line : completeBoardLines) {
            if (line.contains("|")) {
                String[] cells = line.split("\\|");
                int boardColIndex = 0;

                for (String cell : cells) {
                    if (boardRowIndex < 3 && boardColIndex < 3) {
                        String symbol = cell.trim();
                        buttons[boardRowIndex][boardColIndex].setText(symbol.isEmpty() ? " " : symbol);
                        boardColIndex++;
                    }
                }
                if (boardColIndex > 0) {
                    boardRowIndex++;
                }
            }
        }

        if (boardRowIndex != 3) {
            System.err.println(getCurrentTimestamp() + " - WARNING: La board UI potrebbe non essere stata aggiornata completamente. Righe processate: " + boardRowIndex);
        }

        System.out.println(getCurrentTimestamp() + " - Board UI aggiornata.");
    }
}