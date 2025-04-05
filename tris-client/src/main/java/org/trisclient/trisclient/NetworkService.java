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
// import java.util.Arrays; // Non usato
import java.util.List;
import java.util.concurrent.ExecutorService; // Importa
import java.util.concurrent.Executors;   // Importa
import java.util.concurrent.TimeUnit;    // Importa

public class NetworkService {

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean running = false;
    private ServerListener listener; // Callback per notificare l'UI
    private String currentListenerName = "null"; // Aggiungi per logging
    private ExecutorService networkExecutor; // Per gestire il thread del listener

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    // --- Interfaccia ServerListener AGGIORNATA ---
    public interface ServerListener {
        void onConnected();
        void onDisconnected(String reason);
        void onMessageReceived(String rawMessage); // Messaggi non gestiti
        void onNameRequested();
        void onNameAccepted();
        void onGamesList(List<GameInfo> games);
        void onGameCreated(int gameId);
        void onJoinOk(int gameId, char symbol, String opponentName);
        void onGameStart(int gameId, char symbol, String opponentName);
        void onBoardUpdate(String[] board);
        void onYourTurn();
        // NUOVO METODO
        void onGameOver(String result); // result = "WIN", "LOSE", "DRAW"
        void onOpponentLeft();
        void onError(String message);
    }
    // --------------------------------------------


    public static class GameInfo {
        public final int id;
        public final String creatorName;
        public GameInfo(int id, String creatorName) { this.id = id; this.creatorName = creatorName; }
        @Override public String toString() { return "Partita " + id + " (creata da " + creatorName + ")"; }
    }

    public void setServerListener(ServerListener newListener) {
        String newListenerName = (newListener != null) ? newListener.getClass().getSimpleName() + " ("+newListener.hashCode()+")" : "null";
        System.out.println(getCurrentTimestamp() + " - NetworkService: *** setServerListener called *** | Old listener: " + currentListenerName + " | New listener: " + newListenerName);
        this.listener = newListener;
        this.currentListenerName = newListenerName;
    }

    public void connect(String host, int port, ServerListener initialListener) {
        if (running) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Already connected or connecting.");
            return;
        }
        setServerListener(initialListener);
        running = true;

        if (networkExecutor == null || networkExecutor.isShutdown()) {
            networkExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "NetworkListenerThread");
                t.setDaemon(true);
                return t;
            });
            System.out.println(getCurrentTimestamp()+" - NetworkService: Created new NetworkExecutor.");
        }


        networkExecutor.submit(() -> {
            System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Starting connection task.");
            try {
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Connecting to " + host + ":" + port + "...");
                socket = new Socket(host, port);
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Connection established.");

                Platform.runLater(() -> {
                    final ServerListener currentListenerOnConnect = this.listener;
                    if (currentListenerOnConnect != null) currentListenerOnConnect.onConnected();
                    else System.err.println(getCurrentTimestamp()+" - NetworkService: Listener is NULL in onConnected callback!");
                });

                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Listener thread starting loop. Active listener: " + currentListenerName);
                String serverMessage;
                // Leggi finché running è true E la connessione è attiva E il thread non è interrotto
                while (running && (serverMessage = in.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                    final String message = serverMessage;
                    System.out.println(getCurrentTimestamp() + " - RAW FROM SERVER: [" + message + "]");

                    Platform.runLater(() -> {
                        final ServerListener currentListenerAtCallback = this.listener;
                        if (currentListenerAtCallback != null) {
                            parseServerMessage(message); // Chiama il parsing effettivo
                        } else {
                            System.err.println(getCurrentTimestamp() + " - NetworkService (in runLater): ERROR - No active listener to handle message: " + message);
                        }
                    });
                }
                // Se usciamo dal loop, controlliamo perché
                if (Thread.currentThread().isInterrupted()) {
                    System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Read loop interrupted.");
                } else if (!running) {
                    System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Read loop finished because running flag is false.");
                } else {
                    System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Read loop finished because readLine returned null (connection closed by server?).");
                }


            } catch (SocketException e) {
                final String errorMsg = "Connection error: " + e.getMessage();
                System.err.println(getCurrentTimestamp() + " - NetworkService (in executor): SocketException: " + e.getMessage() + " | running="+running);
                // Se running è true, è una disconnessione inaspettata
                if (running) {
                    handleDisconnection(errorMsg);
                } else {
                    System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Socket closed intentionally during shutdown.");
                }
            } catch (IOException e) {
                // Potrebbe essere causato anche da una chiusura forzata del socket (es. in disconnect)
                if (running) {
                    final String errorMsg = "IO error: " + e.getMessage();
                    System.err.println(getCurrentTimestamp() + " - NetworkService (in executor): IOException: " + e.getMessage() + " | running="+running);
                    // e.printStackTrace(); // Log stack trace per IO Error se necessario
                    handleDisconnection(errorMsg);
                } else {
                    System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): IOException likely due to intentional close.");
                }
            } finally {
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Entering finally block.");
                // Assicurati che running sia false se siamo usciti per errore o chiusura server
                boolean wasRunning = running;
                running = false; // Messo qui per sicurezza prima di chiudere risorse
                closeResources(); // Chiudi sempre le risorse
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Listener task finished.");
                // Notifica disconnessione solo se è avvenuta inaspettatamente (wasRunning era true)
                if (wasRunning) {
                    Platform.runLater(() -> {
                        final ServerListener finalListener = this.listener;
                        if (finalListener != null) {
                            System.out.println(getCurrentTimestamp()+" - NetworkService (finally): Notifying listener "+finalListener.getClass().getSimpleName()+" of unexpected disconnection.");
                            // Evita doppia notifica se handleDisconnection è già stato chiamato
                            // Questo è un fallback nel caso handleDisconnection non sia stato chiamato
                            // Potrebbe essere rimosso se handleDisconnection è robusto
                            // finalListener.onDisconnected("Connection closed unexpectedly");
                        } else {
                            System.err.println(getCurrentTimestamp()+" - NetworkService (finally): Listener is NULL when handling final disconnection!");
                        }
                    });
                }
            }
        });
        System.out.println(getCurrentTimestamp()+" - NetworkService: Connection task submitted to executor.");
    }

    // Helper per gestire la notifica di disconnessione in caso di errore/chiusura server
    private void handleDisconnection(String reason) {
        System.out.println(getCurrentTimestamp()+" - NetworkService: handleDisconnection called with reason: "+reason);
        if (!running) {
            System.out.println(getCurrentTimestamp()+" - NetworkService: handleDisconnection ignored as 'running' is already false.");
            return; // Evita doppie notifiche
        }
        running = false; // Segnala che non siamo più attivi
        final ServerListener listenerAtError = this.listener; // Copia il listener attuale

        // Esegui la notifica sul thread JavaFX
        Platform.runLater(() -> {
            if (listenerAtError != null) {
                System.out.println(getCurrentTimestamp()+" - NetworkService (in runLater): Notifying listener "+listenerAtError.getClass().getSimpleName()+" ("+listenerAtError.hashCode()+") of disconnection.");
                listenerAtError.onDisconnected(reason);
            } else {
                System.err.println(getCurrentTimestamp()+" - NetworkService: Listener is NULL when handling disconnection!");
            }
        });
        // Non chiudere le risorse qui, verranno chiuse nel blocco finally del task dell'executor
    }


    // Metodo di Parsing AGGIORNATO
    private void parseServerMessage(String message) {
        if (message == null || message.trim().isEmpty()) return;
        final ServerListener currentListener = this.listener;
        if(currentListener == null){
            System.err.println(getCurrentTimestamp()+" - NetworkService: parseServerMessage - Listener is NULL! Cannot process: "+message);
            return;
        }

        try {
            if (message.startsWith("CMD:GET_NAME")) {
                currentListener.onNameRequested();
            } else if (message.startsWith("RESP:NAME_OK")) {
                currentListener.onNameAccepted();
            } else if (message.startsWith("RESP:GAMES_LIST;")) {
                List<GameInfo> games = new ArrayList<>();
                String content = message.substring("RESP:GAMES_LIST;".length());
                if (!content.isEmpty()) {
                    String[] gameEntries = content.split("\\|");
                    for (String entry : gameEntries) {
                        String[] parts = entry.split(",");
                        if (parts.length == 2) {
                            try {
                                int id = Integer.parseInt(parts[0]);
                                String name = parts[1];
                                games.add(new GameInfo(id, name));
                            } catch (NumberFormatException e) {
                                System.err.println(getCurrentTimestamp() + " - Error parsing game ID in list: " + entry);
                            }
                        } else {
                            System.err.println(getCurrentTimestamp() + " - Malformed game entry in list: " + entry);
                        }
                    }
                }
                currentListener.onGamesList(games);
            } else if (message.startsWith("RESP:CREATED ")) {
                try {
                    int gameId = Integer.parseInt(message.substring("RESP:CREATED ".length()));
                    currentListener.onGameCreated(gameId);
                } catch (NumberFormatException | IndexOutOfBoundsException e) {
                    System.err.println(getCurrentTimestamp() + " - Error parsing CREATED response: " + message);
                    currentListener.onError("Received malformed CREATED response from server.");
                }
            } else if (message.startsWith("RESP:JOIN_OK ")) {
                try {
                    String[] parts = message.substring("RESP:JOIN_OK ".length()).split(" ", 3); // Limita a 3 parti
                    int gameId = Integer.parseInt(parts[0]);
                    char symbol = parts[1].charAt(0);
                    String opponentName = (parts.length > 2) ? parts[2] : "UnknownOpponent";
                    currentListener.onJoinOk(gameId, symbol, opponentName);
                } catch (Exception e) { // Cattura generica per parsing error
                    System.err.println(getCurrentTimestamp() + " - Error parsing JOIN_OK response: " + message + " - " + e.getMessage());
                    currentListener.onError("Received malformed JOIN_OK response from server.");
                }
            } else if (message.startsWith("NOTIFY:GAME_START ")) {
                try {
                    String[] parts = message.substring("NOTIFY:GAME_START ".length()).split(" ", 3); // Limita a 3 parti
                    int gameId = Integer.parseInt(parts[0]);
                    char symbol = parts[1].charAt(0);
                    String opponentName = (parts.length > 2) ? parts[2] : "UnknownOpponent";
                    currentListener.onGameStart(gameId, symbol, opponentName);
                } catch (Exception e) {
                    System.err.println(getCurrentTimestamp() + " - Error parsing GAME_START notification: " + message + " - " + e.getMessage());
                    currentListener.onError("Received malformed GAME_START notification from server.");
                }
            } else if (message.startsWith("NOTIFY:BOARD ")) {
                String boardData = message.substring("NOTIFY:BOARD ".length());
                String[] boardCells = boardData.split(" ");
                if (boardCells.length == 9) { // Controllo di validità base
                    currentListener.onBoardUpdate(boardCells);
                } else {
                    System.err.println(getCurrentTimestamp() + " - Received malformed BOARD notification (expected 9 cells): " + message);
                    currentListener.onError("Received invalid board update from server.");
                }
            } else if (message.startsWith("NOTIFY:YOUR_TURN")) {
                currentListener.onYourTurn();
            }
            // --- NUOVO HANDLER ---
            else if (message.startsWith("NOTIFY:GAMEOVER ")) {
                String result = message.substring("NOTIFY:GAMEOVER ".length()).trim();
                // Valida il risultato ricevuto
                if (result.equals("WIN") || result.equals("LOSE") || result.equals("DRAW")) {
                    currentListener.onGameOver(result);
                } else {
                    System.err.println(getCurrentTimestamp() + " - Received unknown GAMEOVER result: " + result);
                    currentListener.onError("Received unknown game result from server: " + result);
                }
            }
            // --- FINE NUOVO HANDLER ---
            else if (message.startsWith("NOTIFY:OPPONENT_LEFT")) {
                currentListener.onOpponentLeft();
            } else if (message.startsWith("NOTIFY:SERVER_SHUTDOWN")) {
                System.out.println(getCurrentTimestamp()+" - NetworkService: Handling Server Shutdown message.");
                // Chiama handleDisconnection per notificare correttamente e pulire
                handleDisconnection("Server is shutting down");
                // Non chiudere le risorse qui, ci pensa handleDisconnection/finally

            } else if (message.startsWith("ERROR:")) {
                String errorMsg = message.substring("ERROR:".length()).trim();
                currentListener.onError(errorMsg);
            } else {
                System.out.println(getCurrentTimestamp()+" - NetworkService: Forwarding unhandled message to listener: "+ message);
                currentListener.onMessageReceived(message); // Inoltra messaggi non riconosciuti
            }
        } catch (Exception e) {
            System.err.println(getCurrentTimestamp() + " - Critical Error PARSING server message: [" + message + "]");
            e.printStackTrace();
            currentListener.onError("Internal error parsing server message: " + e.getMessage());
        }
    }


    public void sendMessage(String message) {
        final String msgToSend = message;
        PrintWriter currentOut = this.out;
        Socket currentSocket = this.socket;

        if (running && currentOut != null && currentSocket != null && !currentSocket.isClosed() && !currentOut.checkError()) {
            // Invia direttamente dal thread chiamante (solitamente JavaFX thread)
            // Se questo causa blocchi UI (improbabile per messaggi brevi), considerare un executor separato per l'invio.
            try {
                System.out.println(getCurrentTimestamp() + " - NetworkService (direct send): Sending: [" + msgToSend + "]");
                currentOut.println(msgToSend); // println aggiunge newline automaticamente
                if (currentOut.checkError()) {
                    System.err.println(getCurrentTimestamp() + " - NetworkService (direct send): PrintWriter error detected AFTER sending.");
                    // L'errore verrà probabilmente rilevato anche dal read loop.
                    // Evitiamo di chiamare handleDisconnection qui per non avere doppie notifiche.
                    // Potremmo solo loggare o impostare un flag.
                }
            } catch (Exception e) {
                // Eccezioni qui sono meno probabili con println, ma gestiamole
                System.err.println(getCurrentTimestamp() + " - NetworkService (direct send): Exception during send: "+e.getMessage());
                // Anche qui, evitiamo handleDisconnection diretto se possibile.
            }
        } else {
            System.err.println(getCurrentTimestamp() + " - Cannot send message, not connected or PrintWriter error. Message: [" + msgToSend + "]");
            System.err.println(getCurrentTimestamp() + " - Send Check: running="+running+", socket="+(socket != null)+", socket.isConnected="+(socket != null ? socket.isConnected():"N/A")+", socket.isClosed="+(socket != null ? socket.isClosed():"N/A")+", out="+(out!=null)+", out.checkError="+(out != null ? out.checkError() : "N/A"));
            // Potrebbe notificare l'UI qui, ma attenzione a non farlo se è una disconnessione normale.
            // Platform.runLater(() -> {
            //    final ServerListener currentListenerOnSendFail = this.listener;
            //    if(currentListenerOnSendFail != null && running) // Notifica solo se eravamo 'running'
            //        currentListenerOnSendFail.onError("Cannot send message - Not Connected");
            // });
        }
    }

    // --- Metodi per inviare comandi specifici ---
    public void sendName(String name) { sendMessage("NAME " + name); }
    public void sendListRequest() { sendMessage("LIST"); }
    public void sendCreateGame() { sendMessage("CREATE"); }
    public void sendJoinGame(int gameId) { sendMessage("JOIN " + gameId); }
    public void sendMove(int row, int col) { sendMessage("MOVE " + row + " " + col); }
    public void sendQuit() { sendMessage("QUIT"); } // Invia QUIT prima di disconnettere?
    // --------------------------------------------


    public void disconnect() {
        System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() CALLED.");
        if (!running) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() ignored, already not running.");
            shutdownExecutor(); // Assicurati che l'executor sia spento
            return;
        }

        running = false; // Segnala al thread di terminare e previene ulteriori send
        final ServerListener listenerAtDisconnect = this.listener;

        // Invia QUIT al server *prima* di chiudere localmente? Opzionale.
        // Se fatto, assicurati che sendMessage possa ancora funzionare brevemente.
        // sendQuit(); // Potrebbe fallire se la connessione è già persa

        // Chiudi le risorse per interrompere il read/write bloccante
        // Usare un metodo separato per la chiusura pulita
        closeResources();

        // Interrompi il thread dell'executor se è bloccato in readLine
        interruptExecutorThread();

        // Ferma l'executor service
        shutdownExecutor();

        // Notifica l'UI della disconnessione intenzionale
        Platform.runLater(() -> {
            if(listenerAtDisconnect != null) {
                System.out.println(getCurrentTimestamp()+" - NetworkService (in runLater): Notifying listener "+listenerAtDisconnect.getClass().getSimpleName()+" ("+listenerAtDisconnect.hashCode()+") of intentional disconnect.");
                listenerAtDisconnect.onDisconnected("Disconnected by client");
            } else {
                System.err.println(getCurrentTimestamp()+" - NetworkService: Listener is NULL during intentional disconnect!");
            }
        });
        System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() finished.");
    }

    // Metodo per chiudere le risorse di rete in modo sincronizzato
    private synchronized void closeResources() {
        System.out.println(getCurrentTimestamp() + " - NetworkService: closeResources() CALLED.");
        // Chiudi prima gli stream per sbloccare read/write
        if (out != null) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Closing PrintWriter.");
            out.close();
            out = null; // Imposta a null dopo la chiusura
        }
        if (in != null) {
            try {
                System.out.println(getCurrentTimestamp() + " - NetworkService: Closing BufferedReader.");
                in.close();
                in = null; // Imposta a null
            } catch (IOException e) {
                System.err.println(getCurrentTimestamp() + " - NetworkService: Error closing BufferedReader: " + e.getMessage());
            }
        }
        // Infine chiudi il socket
        if (socket != null && !socket.isClosed()) {
            try {
                System.out.println(getCurrentTimestamp() + " - NetworkService: Closing Socket.");
                socket.close();
                socket = null; // Imposta a null
            } catch (IOException e) {
                System.err.println(getCurrentTimestamp() + " - NetworkService: Error closing socket: " + e.getMessage());
            }
        }
        System.out.println(getCurrentTimestamp() + " - NetworkService: Network resources closed.");
    }

    // Metodo per interrompere il thread nell'executor
    private void interruptExecutorThread() {
        if (networkExecutor != null && !networkExecutor.isShutdown()) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Attempting to interrupt executor thread(s)...");
            // Interrompe i thread in attesa/bloccati (es. su readLine)
            networkExecutor.shutdownNow();
            try {
                // Attendi brevemente per permettere la gestione dell'interrupt
                if (!networkExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    System.err.println(getCurrentTimestamp() + " - NetworkService: Executor did not terminate after interrupt within timeout.");
                } else {
                    System.out.println(getCurrentTimestamp() + " - NetworkService: Executor terminated after interrupt.");
                }
            } catch (InterruptedException e) {
                System.err.println(getCurrentTimestamp() + " - NetworkService: Interrupted while waiting for executor termination after shutdownNow.");
                Thread.currentThread().interrupt();
            }
        }
    }


    // Metodo per chiudere l'executor (chiamato dopo interrupt/shutdownNow)
    private void shutdownExecutor() {
        // L'interruptExecutorThread ora chiama shutdownNow, quindi qui basta controllare
        if (networkExecutor != null && !networkExecutor.isTerminated()) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: shutdownExecutor called, ensuring termination.");
            // Se non è ancora terminato dopo shutdownNow e await, c'è poco altro da fare
            if (!networkExecutor.isTerminated()) {
                System.err.println(getCurrentTimestamp() + " - NetworkService: Warning - Executor service still not terminated.");
            }
        }
        networkExecutor = null; // Rimuovi riferimento all'executor spento
    }


    public boolean isConnected() {
        // Controlla 'running' E che il socket esista, sia connesso e non chiuso
        return running && socket != null && socket.isConnected() && !socket.isClosed();
    }

    // Getter per il listener (utile per debug)
    public ServerListener getCurrentListener() {
        return listener;
    }
}