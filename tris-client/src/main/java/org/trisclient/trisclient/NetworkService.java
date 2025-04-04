package org.trisclient.trisclient;

import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NetworkService {

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Thread listenerThread;
    private volatile boolean running = false;
    private ServerListener listener; // Callback per notificare l'UI

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    public interface ServerListener {
        void onConnected();
        void onDisconnected(String reason);
        void onMessageReceived(String rawMessage); // Per debug o messaggi non gestiti
        void onNameRequested(); // Server chiede il nome
        void onNameAccepted();  // Server conferma ricezione nome
        void onGamesList(List<GameInfo> games); // Ricevuta lista partite
        void onGameCreated(int gameId); // Conferma creazione partita
        void onJoinOk(int gameId, char symbol, String opponentName); // Conferma join
        void onGameStart(int gameId, char symbol, String opponentName); // Partita inizia (per entrambi)
        void onBoardUpdate(String[] board); // Board aggiornata (formato array di stringhe "X", "O", "EMPTY")
        void onYourTurn(); // È il tuo turno
        void onGameOver(String result); // Partita finita (WIN, LOSE, DRAW)
        void onOpponentLeft(); // Avversario disconnesso
        void onError(String message); // Messaggio di errore dal server
    }

    // Classe interna semplice per info partita dalla lista
    public static class GameInfo {
        public final int id;
        public final String creatorName;
        public GameInfo(int id, String creatorName) {
            this.id = id;
            this.creatorName = creatorName;
        }
        @Override
        public String toString() { return "Partita " + id + " (creata da " + creatorName + ")"; }
    }

    public void setServerListener(ServerListener newListener) {
        String listenerName = (newListener != null) ? newListener.getClass().getSimpleName() : "null";
        System.out.println(getCurrentTimestamp() + " - NetworkService: Setting active listener to -> " + listenerName);
        this.listener = newListener; // Imposta il nuovo listener
    }

    public void connect(String host, int port, ServerListener initialListener) {
        if (running) {
            System.out.println(getCurrentTimestamp() + " - Already connected or connecting.");
            return;
        }
        setServerListener(initialListener);
        running = true;

        listenerThread = new Thread(() -> {
            try {
                System.out.println(getCurrentTimestamp() + " - Connecting to " + host + ":" + port + "...");
                socket = new Socket(host, port);
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                System.out.println(getCurrentTimestamp() + " - Connection established.");
                Platform.runLater(listener::onConnected); // Notifica UI

                String serverMessage;
                while (running && (serverMessage = in.readLine()) != null) {
                    final String message = serverMessage; // Copia per lambda
                    System.out.println(getCurrentTimestamp() + " - RAW FROM SERVER: [" + message + "]");
                    Platform.runLater(() -> {
                        if (this.listener != null) { // Aggiungi controllo null
                            parseServerMessage(message);
                        } else {
                            System.err.println(getCurrentTimestamp() + " - NetworkService: ERROR - No active listener to handle message: " + message);
                        }
                    });
                }
            } catch (SocketException e) {
                if (running) { // Se non stiamo chiudendo noi
                    System.err.println(getCurrentTimestamp() + " - SocketException: " + e.getMessage());
                    Platform.runLater(() -> listener.onDisconnected("Connection error: " + e.getMessage()));
                } else {
                    System.out.println(getCurrentTimestamp() + " - Socket closed intentionally.");
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println(getCurrentTimestamp() + " - IOException: " + e.getMessage());
                    e.printStackTrace();
                    Platform.runLater(() -> listener.onDisconnected("IO error: " + e.getMessage()));
                }
            } finally {
                running = false;
                closeResources();
                System.out.println(getCurrentTimestamp() + " - Listener thread finished.");
                // Notifica disconnessione solo se non è già stato fatto
                // Potrebbe essere già stato chiamato da un errore precedente
                // TODO: Aggiungere un flag per evitare doppie notifiche
                // Platform.runLater(() -> listener.onDisconnected("Connection closed"));
            }
        });
        listenerThread.start();
    }

    private void parseServerMessage(String message) {
        if (message == null || message.trim().isEmpty()) return;

        try {
            if (message.startsWith("CMD:GET_NAME")) {
                listener.onNameRequested();
            } else if (message.startsWith("RESP:NAME_OK")) {
                listener.onNameAccepted();
            } else if (message.startsWith("RESP:GAMES_LIST;")) {
                List<GameInfo> games = new ArrayList<>();
                String content = message.substring("RESP:GAMES_LIST;".length());
                if (!content.isEmpty()) {
                    String[] gameEntries = content.split("\\|"); // Split per partita
                    for (String entry : gameEntries) {
                        String[] parts = entry.split(","); // Split per id,nome
                        if (parts.length == 2) {
                            try {
                                int id = Integer.parseInt(parts[0]);
                                String name = parts[1];
                                games.add(new GameInfo(id, name));
                            } catch (NumberFormatException e) {
                                System.err.println(getCurrentTimestamp() + " - Error parsing game ID in list: " + entry);
                            }
                        }
                    }
                }
                listener.onGamesList(games);
            } else if (message.startsWith("RESP:CREATED ")) {
                int gameId = Integer.parseInt(message.substring("RESP:CREATED ".length()));
                listener.onGameCreated(gameId);
            } else if (message.startsWith("RESP:JOIN_OK ")) {
                String[] parts = message.substring("RESP:JOIN_OK ".length()).split(" ");
                int gameId = Integer.parseInt(parts[0]);
                char symbol = parts[1].charAt(0);
                String opponentName = parts[2];
                listener.onJoinOk(gameId, symbol, opponentName);
            } else if (message.startsWith("NOTIFY:GAME_START ")) {
                String[] parts = message.substring("NOTIFY:GAME_START ".length()).split(" ");
                int gameId = Integer.parseInt(parts[0]);
                char symbol = parts[1].charAt(0);
                String opponentName = parts[2];
                listener.onGameStart(gameId, symbol, opponentName);
            } else if (message.startsWith("NOTIFY:BOARD ")) {
                String boardData = message.substring("NOTIFY:BOARD ".length());
                String[] boardCells = boardData.split(" "); // Divide per "X", "O", "EMPTY"
                listener.onBoardUpdate(boardCells); // Passa l'array [9]
            } else if (message.startsWith("NOTIFY:YOUR_TURN")) {
                listener.onYourTurn();
            } else if (message.startsWith("NOTIFY:GAMEOVER ")) {
                String result = message.substring("NOTIFY:GAMEOVER ".length());
                listener.onGameOver(result); // WIN, LOSE, DRAW
            } else if (message.startsWith("NOTIFY:OPPONENT_LEFT")) {
                listener.onOpponentLeft();
            } else if (message.startsWith("NOTIFY:SERVER_SHUTDOWN")) {
                listener.onDisconnected("Server is shutting down");
                disconnect(); // Chiudi la nostra connessione
            } else if (message.startsWith("ERROR:")) {
                String errorMsg = message.substring("ERROR:".length());
                listener.onError(errorMsg);
            } else {
                // Messaggio non riconosciuto
                listener.onMessageReceived(message);
            }
        } catch (Exception e) {
            System.err.println(getCurrentTimestamp() + " - Error parsing server message: [" + message + "]");
            e.printStackTrace();
            listener.onError("Error parsing message: " + e.getMessage());
        }
    }


    public void sendMessage(String message) {
        if (out != null && !socket.isClosed()) {
            System.out.println(getCurrentTimestamp() + " - Sending: [" + message + "]");
            out.println(message);
        } else {
            System.err.println(getCurrentTimestamp() + " - Cannot send message, not connected.");
            // Potrebbe notificare l'UI qui
        }
    }

    // --- Metodi per inviare comandi specifici ---

    public void sendName(String name) {
        sendMessage("NAME " + name);
    }

    public void sendListRequest() {
        sendMessage("LIST");
    }

    public void sendCreateGame() {
        sendMessage("CREATE");
    }

    public void sendJoinGame(int gameId) {
        sendMessage("JOIN " + gameId);
    }

    public void sendMove(int row, int col) {
        sendMessage("MOVE " + row + " " + col);
    }

    public void sendQuit() {
        sendMessage("QUIT"); // Invia il comando QUIT al server
        // La disconnessione effettiva avverrà quando il server chiude o nel finally
    }


    public void disconnect() {
        System.out.println(getCurrentTimestamp() + " - disconnect() called.");
        running = false; // Segnala al thread di terminare
        closeResources();
        if (listenerThread != null) {
            listenerThread.interrupt(); // Interrompi se bloccato su readLine
        }
        // La notifica onDisconnected dovrebbe avvenire nel blocco finally del thread
    }

    private void closeResources() {
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null && !socket.isClosed()) socket.close();
            System.out.println(getCurrentTimestamp() + " - Network resources closed.");
        } catch (IOException e) {
            System.err.println(getCurrentTimestamp() + " - Error closing resources: " + e.getMessage());
        } finally {
            out = null;
            in = null;
            socket = null;
        }
    }

    public boolean isConnected() {
        return running && socket != null && socket.isConnected() && !socket.isClosed();
    }
}