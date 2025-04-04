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
import java.util.concurrent.ExecutorService; // Importa
import java.util.concurrent.Executors;   // Importa
import java.util.concurrent.TimeUnit;    // Importa

public class NetworkService {

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    // Rimosso: private Thread listenerThread; -> Usiamo ExecutorService
    private volatile boolean running = false;
    private ServerListener listener; // Callback per notificare l'UI
    private String currentListenerName = "null"; // Aggiungi per logging
    private ExecutorService networkExecutor; // Per gestire il thread del listener

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    // --- Interfaccia e Classe GameInfo (invariate) ---
    public interface ServerListener {
        void onConnected();
        void onDisconnected(String reason);
        void onMessageReceived(String rawMessage);
        void onNameRequested();
        void onNameAccepted();
        void onGamesList(List<GameInfo> games);
        void onGameCreated(int gameId);
        void onJoinOk(int gameId, char symbol, String opponentName);
        void onGameStart(int gameId, char symbol, String opponentName);
        void onBoardUpdate(String[] board);
        void onYourTurn();
        void onGameOver(String result);
        void onOpponentLeft();
        void onError(String message);
    }

    public static class GameInfo {
        public final int id;
        public final String creatorName;
        public GameInfo(int id, String creatorName) { this.id = id; this.creatorName = creatorName; }
        @Override public String toString() { return "Partita " + id + " (creata da " + creatorName + ")"; }
    }
    // ----------------------------------------------------

    public void setServerListener(ServerListener newListener) {
        String newListenerName = (newListener != null) ? newListener.getClass().getSimpleName() + " ("+newListener.hashCode()+")" : "null";
        // Esegui log in Platform.runLater per evitare potenziali accessi concorrenti a System.out? Non strettamente necessario ma più pulito.
        // Platform.runLater(() -> System.out.println(getCurrentTimestamp() + " - NetworkService: *** setServerListener called *** | Old listener: " + currentListenerName + " | New listener: " + newListenerName));
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

        // Usa un ExecutorService per gestire il thread
        // Usiamo un single thread executor per assicurarci che le operazioni di rete siano sequenziali
        if (networkExecutor == null || networkExecutor.isShutdown()) {
            networkExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "NetworkListenerThread");
                t.setDaemon(true); // Permette all'applicazione di uscire anche se questo thread è in esecuzione
                return t;
            });
            System.out.println(getCurrentTimestamp()+" - NetworkService: Created new NetworkExecutor.");
        }


        networkExecutor.submit(() -> { // Sottoponi il task al thread pool
            System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Starting connection task.");
            try {
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Connecting to " + host + ":" + port + "...");
                socket = new Socket(host, port);
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Connection established.");
                // Notifica UI tramite Platform.runLater
                Platform.runLater(() -> {
                    if (listener != null) listener.onConnected();
                    else System.err.println(getCurrentTimestamp()+" - NetworkService: Listener is NULL in onConnected callback!");
                });

                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Listener thread starting loop. Active listener: " + currentListenerName);
                String serverMessage;
                while (running && (serverMessage = in.readLine()) != null) {
                    final String message = serverMessage;
                    System.out.println(getCurrentTimestamp() + " - RAW FROM SERVER: [" + message + "]");

                    // Esegui la logica di parsing e la chiamata al listener nel thread JavaFX
                    Platform.runLater(() -> {
                        final ServerListener currentListenerAtCallback = this.listener; // Copia locale per sicurezza nel lambda
                        if (currentListenerAtCallback != null) {
                            // System.out.println(getCurrentTimestamp() + " - NetworkService (in runLater): Processing message with listener -> " + currentListenerAtCallback.getClass().getSimpleName()+" ("+currentListenerAtCallback.hashCode()+")");
                            parseServerMessage(message); // Chiama il parsing effettivo
                        } else {
                            System.err.println(getCurrentTimestamp() + " - NetworkService (in runLater): ERROR - No active listener to handle message: " + message);
                        }
                    });
                }
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Read loop finished. Reason: running=" + running + " or readLine returned null.");

            } catch (SocketException e) {
                // Gestione Disconnessione/Errore
                final String errorMsg = "Connection error: " + e.getMessage();
                System.err.println(getCurrentTimestamp() + " - NetworkService (in executor): SocketException: " + e.getMessage() + " | running="+running);
                if (running) { // Se non stavamo chiudendo noi intenzionalmente
                    handleDisconnection(errorMsg);
                } else {
                    System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Socket closed intentionally.");
                }
            } catch (IOException e) {
                // Gestione Disconnessione/Errore
                final String errorMsg = "IO error: " + e.getMessage();
                System.err.println(getCurrentTimestamp() + " - NetworkService (in executor): IOException: " + e.getMessage() + " | running="+running);
                e.printStackTrace(); // Log stack trace per IO Error
                if (running) {
                    handleDisconnection(errorMsg);
                }
            } finally {
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Entering finally block.");
                // Assicurati che running sia false se siamo usciti per errore
                boolean wasRunning = running;
                running = false; // Messo qui per sicurezza
                closeResources(); // Chiudi sempre le risorse
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Listener task finished.");
                // Notifica disconnessione solo se non è già stato fatto e non è una chiusura intenzionale
                // La notifica viene ora fatta da handleDisconnection o da disconnect()
                // if(wasRunning) {
                //    Platform.runLater(() -> { if(listener!=null) listener.onDisconnected("Connection closed"); });
                //}
            }
        }); // Fine submit
        System.out.println(getCurrentTimestamp()+" - NetworkService: Connection task submitted to executor.");
    }

    // Helper per gestire la notifica di disconnessione in caso di errore
    private void handleDisconnection(String reason) {
        System.out.println(getCurrentTimestamp()+" - NetworkService: handleDisconnection called with reason: "+reason);
        if (!running) {
            System.out.println(getCurrentTimestamp()+" - NetworkService: handleDisconnection ignored as 'running' is already false.");
            return; // Evita doppie notifiche se disconnect() è già stato chiamato
        }
        running = false; // Segnala che non siamo più attivi
        final ServerListener listenerAtError = this.listener; // Copia il listener attuale
        Platform.runLater(() -> {
            if (listenerAtError != null) {
                System.out.println(getCurrentTimestamp()+" - NetworkService (in runLater): Notifying listener "+listenerAtError.getClass().getSimpleName()+" ("+listenerAtError.hashCode()+") of disconnection.");
                listenerAtError.onDisconnected(reason);
            } else {
                System.err.println(getCurrentTimestamp()+" - NetworkService: Listener is NULL when handling disconnection!");
            }
        });
        // Le risorse verranno chiuse nel blocco finally del task dell'executor
    }


    private void parseServerMessage(String message) {
        if (message == null || message.trim().isEmpty()) return;
        final ServerListener currentListener = this.listener; // Usa la copia locale nel Platform.runLater
        if(currentListener == null){
            System.err.println(getCurrentTimestamp()+" - NetworkService: parseServerMessage - Listener is NULL! Cannot process: "+message);
            return; // Non possiamo processare senza listener
        }

        try {
            if (message.startsWith("CMD:GET_NAME")) {
                // System.out.println(getCurrentTimestamp()+" - NetworkService: Calling listener.onNameRequested()");
                currentListener.onNameRequested();
            } else if (message.startsWith("RESP:NAME_OK")) {
                // System.out.println(getCurrentTimestamp()+" - NetworkService: Calling listener.onNameAccepted()");
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
                        }
                    }
                }
                // System.out.println(getCurrentTimestamp()+" - NetworkService: Calling listener.onGamesList() with " + games.size() + " games");
                currentListener.onGamesList(games);
            } else if (message.startsWith("RESP:CREATED ")) {
                int gameId = Integer.parseInt(message.substring("RESP:CREATED ".length()));
                // System.out.println(getCurrentTimestamp()+" - NetworkService: Calling listener.onGameCreated() for gameId " + gameId);
                currentListener.onGameCreated(gameId);
            } else if (message.startsWith("RESP:JOIN_OK ")) {
                String[] parts = message.substring("RESP:JOIN_OK ".length()).split(" ");
                int gameId = Integer.parseInt(parts[0]);
                char symbol = parts[1].charAt(0);
                String opponentName = parts[2];
                // System.out.println(getCurrentTimestamp()+" - NetworkService: Calling listener.onJoinOk() for gameId " + gameId);
                currentListener.onJoinOk(gameId, symbol, opponentName);
            } else if (message.startsWith("NOTIFY:GAME_START ")) {
                String[] parts = message.substring("NOTIFY:GAME_START ".length()).split(" ");
                int gameId = Integer.parseInt(parts[0]);
                char symbol = parts[1].charAt(0);
                String opponentName = parts[2];
                // System.out.println(getCurrentTimestamp()+" - NetworkService: Calling listener.onGameStart() for gameId " + gameId);
                currentListener.onGameStart(gameId, symbol, opponentName);
            } else if (message.startsWith("NOTIFY:BOARD ")) {
                String boardData = message.substring("NOTIFY:BOARD ".length());
                String[] boardCells = boardData.split(" ");
                // System.out.println(getCurrentTimestamp()+" - NetworkService: Calling listener.onBoardUpdate()");
                currentListener.onBoardUpdate(boardCells);
            } else if (message.startsWith("NOTIFY:YOUR_TURN")) {
                // System.out.println(getCurrentTimestamp()+" - NetworkService: Calling listener.onYourTurn()");
                currentListener.onYourTurn();
            } else if (message.startsWith("NOTIFY:GAMEOVER ")) {
                String result = message.substring("NOTIFY:GAMEOVER ".length());
                // System.out.println(getCurrentTimestamp()+" - NetworkService: Calling listener.onGameOver() with result: " + result);
                currentListener.onGameOver(result);
            } else if (message.startsWith("NOTIFY:OPPONENT_LEFT")) {
                // System.out.println(getCurrentTimestamp()+" - NetworkService: Calling listener.onOpponentLeft()");
                currentListener.onOpponentLeft();
            } else if (message.startsWith("NOTIFY:SERVER_SHUTDOWN")) {
                System.out.println(getCurrentTimestamp()+" - NetworkService: Handling Server Shutdown message.");
                // Non chiamare disconnect() qui, altrimenti causa loop/race condition con handleDisconnection
                // handleDisconnection("Server is shutting down"); // Invece, chiama questo helper
                running = false; // Segnala stop
                // La notifica onDisconnected avverrà da handleDisconnection o dal finally del thread
                // Chiudi il socket per forzare l'uscita dal readLine
                closeResources();

            } else if (message.startsWith("ERROR:")) {
                String errorMsg = message.substring("ERROR:".length());
                // System.out.println(getCurrentTimestamp()+" - NetworkService: Calling listener.onError() with message: " + errorMsg);
                currentListener.onError(errorMsg);
            } else {
                // System.out.println(getCurrentTimestamp()+" - NetworkService: Calling listener.onMessageReceived() for unhandled message");
                currentListener.onMessageReceived(message);
            }
        } catch (Exception e) {
            System.err.println(getCurrentTimestamp() + " - Error PARSING server message: [" + message + "]");
            e.printStackTrace();
            // Non chiamare onError qui se il listener è già stato gestito sopra
            // currentListener.onError("Error parsing message: " + e.getMessage());
        }
    }


    public void sendMessage(String message) {
        final String msgToSend = message; // Copia per logging/errori

        // Controllo robusto dello stato della connessione e del PrintWriter
        PrintWriter currentOut = this.out; // Copia locale per thread-safety
        Socket currentSocket = this.socket; // Copia locale

        if (currentOut != null && currentSocket != null && !currentSocket.isClosed() && !currentOut.checkError()) {
            // --- MODIFICA CHIAVE: ESEGUI L'INVIO DIRETTAMENTE ---
            // Rimuovi o commenta la sottomissione all'executor
             /*
             if(networkExecutor != null && !networkExecutor.isShutdown()) {
                networkExecutor.submit(() -> {
                    try {
                        System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Sending: [" + msgToSend + "]");
                        // Usa la copia locale 'currentOut' qui dentro se rimetti l'executor
                        out.println(msgToSend);
                         if(out.checkError()) {
                             System.err.println(getCurrentTimestamp() + " - NetworkService (in executor): PrintWriter error detected after sending.");
                             handleDisconnection("Send Error");
                         }
                    } catch (Exception e) {
                         System.err.println(getCurrentTimestamp() + " - NetworkService (in executor): Exception during send: "+e.getMessage());
                          handleDisconnection("Send Exception");
                    }
                });
             } else {
                  System.err.println(getCurrentTimestamp() + " - Cannot send message, executor not available.");
             }
             */

            // --- ESEGUI DIRETTAMENTE ---
            // Poiché questo metodo può essere chiamato dal thread JavaFX,
            // l'invio avverrà su quel thread. Se l'invio dovesse bloccare
            // per troppo tempo (improbabile per brevi messaggi TCP),
            // potrebbe servire un executor separato per l'invio.
            // Ma per ora, questa è la soluzione più semplice al deadlock.
            try {
                System.out.println(getCurrentTimestamp() + " - NetworkService (direct send): Sending: [" + msgToSend + "]");
                currentOut.println(msgToSend);
                if (currentOut.checkError()) { // Controlla errore DOPO l'invio
                    System.err.println(getCurrentTimestamp() + " - NetworkService (direct send): PrintWriter error detected after sending.");
                    // Se c'è un errore qui, siamo probabilmente già disconnessi.
                    // handleDisconnection potrebbe essere già stato chiamato o lo sarà a breve dal read loop.
                    // Evitiamo di chiamarlo di nuovo qui per non causare doppie notifiche.
                    // Potremmo semplicemente loggare l'errore.
                } else {
                    // System.out.println(getCurrentTimestamp() + " - NetworkService (direct send): Message sent successfully.");
                }
            } catch (Exception e) {
                // Questo non dovrebbe accadere con println, ma per sicurezza
                System.err.println(getCurrentTimestamp() + " - NetworkService (direct send): Exception during send: "+e.getMessage());
                // Anche qui, evita di chiamare handleDisconnection direttamente se possibile.
            }
            // --- FINE INVIO DIRETTO ---

        } else {
            System.err.println(getCurrentTimestamp() + " - Cannot send message, not connected or PrintWriter error. Message: [" + msgToSend + "]");
            // Log aggiuntivo per capire perché non possiamo inviare
            // RIGA CORRETTA:
            System.err.println(getCurrentTimestamp() + " - Send Check: running="+running+", socket="+(socket != null)+", socket.isConnected="+(socket != null ? socket.isConnected():"N/A")+", socket.isClosed="+(socket != null ? socket.isClosed():"N/A")+", out="+(out!=null)+", out.checkError="+(out != null ? out.checkError() : "N/A"));
            // Potrebbe notificare l'UI qui, ma attenzione a non farlo se è una disconnessione normale.
            // Platform.runLater(() -> { if(listener != null && running) listener.onError("Cannot send message - Not Connected"); });
        }
    }

    // --- Metodi per inviare comandi specifici ---
    public void sendName(String name) { sendMessage("NAME " + name); }
    public void sendListRequest() { sendMessage("LIST"); }
    public void sendCreateGame() { sendMessage("CREATE"); }
    public void sendJoinGame(int gameId) { sendMessage("JOIN " + gameId); }
    public void sendMove(int row, int col) { sendMessage("MOVE " + row + " " + col); }
    public void sendQuit() { sendMessage("QUIT"); }
    // --------------------------------------------


    public void disconnect() {
        System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() CALLED.");
        if (!running) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() ignored, already not running.");
            // Pulisci executor se necessario?
            shutdownExecutor();
            return;
        }

        running = false; // Segnala al thread di terminare
        final ServerListener listenerAtDisconnect = this.listener; // Copia listener

        // Chiudi le risorse PRIMA di notificare, per evitare race condition
        closeResources();

        // Ferma il thread pool
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

    // Metodo per chiudere le risorse di rete
    private synchronized void closeResources() { // Aggiunto synchronized
        System.out.println(getCurrentTimestamp() + " - NetworkService: closeResources() CALLED.");
        try {
            if (out != null) {
                System.out.println(getCurrentTimestamp() + " - NetworkService: Closing PrintWriter.");
                out.close();
            }
            if (in != null) {
                System.out.println(getCurrentTimestamp() + " - NetworkService: Closing BufferedReader.");
                in.close();
            }
            if (socket != null && !socket.isClosed()) {
                System.out.println(getCurrentTimestamp() + " - NetworkService: Closing Socket.");
                socket.close();
            }
            System.out.println(getCurrentTimestamp() + " - NetworkService: Network resources closed.");
        } catch (IOException e) {
            System.err.println(getCurrentTimestamp() + " - NetworkService: Error closing resources: " + e.getMessage());
        } finally {
            out = null;
            in = null;
            socket = null;
        }
    }

    // Metodo per chiudere l'executor
    private void shutdownExecutor() {
        if (networkExecutor != null && !networkExecutor.isShutdown()) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Shutting down NetworkExecutor...");
            networkExecutor.shutdown(); // Disabilita nuove task
            try {
                // Aspetta un po' per le task correnti, ma non bloccare troppo a lungo
                if (!networkExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    System.err.println(getCurrentTimestamp() + " - NetworkService: Executor did not terminate gracefully, forcing shutdown.");
                    networkExecutor.shutdownNow(); // Cancella task in attesa/running
                } else {
                    System.out.println(getCurrentTimestamp() + " - NetworkService: NetworkExecutor terminated gracefully.");
                }
            } catch (InterruptedException ie) {
                System.err.println(getCurrentTimestamp() + " - NetworkService: Interrupted while waiting for executor termination.");
                networkExecutor.shutdownNow();
                Thread.currentThread().interrupt(); // Re-imposta l'interrupt status
            }
        }
    }


    public boolean isConnected() {
        // Controlla 'running' E lo stato del socket/streams se disponibili
        return running && socket != null && socket.isConnected() && !socket.isClosed() && out != null && !out.checkError() ;
    }

    // Getter per il listener (utile per debug)
    public ServerListener getCurrentListener() {
        return listener;
    }
}