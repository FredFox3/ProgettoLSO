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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class NetworkService {

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean running = false;
    private final AtomicReference<ServerListener> listenerRef = new AtomicReference<>();
    private String currentListenerName = "null";
    private ExecutorService networkExecutor;

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    public interface ServerListener {
        void onConnected();
        void onDisconnected(String reason);
        void onMessageReceived(String rawMessage);
        void onNameRequested();
        void onNameAccepted();
        void onGamesList(List<GameInfo> games);
        void onGameCreated(int gameId);
        void onGameStart(int gameId, char symbol, String opponentName);
        void onBoardUpdate(String[] board);
        void onYourTurn();
        void onGameOver(String result);
        void onOpponentLeft();
        void onError(String message);
        void onJoinRequestSent(int gameId);
        void onJoinRequestReceived(String requesterName);
        void onJoinAccepted(int gameId, char symbol, String opponentName);
        void onJoinRejected(int gameId, String creatorName);
        void onActionConfirmed(String message);
    }

    public static class GameInfo {
        public final int id;
        public final String creatorName;
        public GameInfo(int id, String creatorName) { this.id = id; this.creatorName = creatorName; }
        @Override public String toString() { return "Partita " + id + " (creata da " + creatorName + ")"; }
    }

    public void setServerListener(ServerListener newListener) {
        String oldListenerName = this.currentListenerName;
        String newListenerName = (newListener != null) ? newListener.getClass().getSimpleName() + " ("+newListener.hashCode()+")" : "null";
        listenerRef.set(newListener);
        this.currentListenerName = newListenerName;
        System.out.println(getCurrentTimestamp() + " - NetworkService: *** setServerListener called *** | Old listener: " + oldListenerName + " | New listener: " + newListenerName);
    }

    public void connect(String host, int port, ServerListener initialListener) {
        if (!canAttemptConnect()) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: connect() called but cannot attempt connection (already running or executor active?).");
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
                    ServerListener currentListener = listenerRef.get();
                    if (currentListener != null) {
                        currentListener.onConnected();
                    } else {
                        System.err.println(getCurrentTimestamp()+" - NetworkService: Listener is NULL in onConnected callback!");
                    }
                });

                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Listener thread starting loop. Active listener: " + currentListenerName);
                String serverMessage;
                while (running && (serverMessage = in.readLine()) != null) {
                    final String message = serverMessage;
                    System.out.println(getCurrentTimestamp() + " - RAW FROM SERVER: [" + message + "]");

                    Platform.runLater(() -> {
                        ServerListener currentListener = listenerRef.get();
                        if (currentListener != null) {
                            parseServerMessage(message, currentListener);
                        } else {
                            System.err.println(getCurrentTimestamp() + " - NetworkService (in runLater): ERROR - No active listener to handle message: " + message);
                        }
                    });
                }
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Read loop finished. Reason: running=" + running + " or readLine returned null.");

                if (running) {
                    System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Detected server closed connection unexpectedly (readLine returned null while running).");
                    handleDisconnection("Server closed connection");
                }

            } catch (SocketException e) {
                final String errorMsg = "Connection error: " + e.getMessage();
                System.err.println(getCurrentTimestamp() + " - NetworkService (in executor): SocketException: " + e.getMessage() + " | running="+running);
                if (running) {
                    handleDisconnection(errorMsg);
                } else {
                    System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Socket closed intentionally or during shutdown.");
                }
            } catch (IOException e) {
                final String errorMsg = "IO error: " + e.getMessage();
                System.err.println(getCurrentTimestamp() + " - NetworkService (in executor): IOException: " + e.getMessage() + " | running="+running);
                if (running) {
                    handleDisconnection(errorMsg);
                }
            } finally {
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Entering finally block.");
                closeResources();
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Listener task finished.");
            }
        });
        System.out.println(getCurrentTimestamp()+" - NetworkService: Connection task submitted to executor.");
    }

    private void handleDisconnection(String reason) {
        System.out.println(getCurrentTimestamp()+" - NetworkService: handleDisconnection called with reason: "+reason);
        if (!running) {
            System.out.println(getCurrentTimestamp()+" - NetworkService: handleDisconnection ignored as 'running' is already false.");
            return;
        }
        running = false;

        Platform.runLater(() -> {
            ServerListener currentListener = listenerRef.get();
            if (currentListener != null) {
                System.out.println(getCurrentTimestamp()+" - NetworkService (in runLater): Notifying listener "+currentListener.getClass().getSimpleName()+" ("+currentListener.hashCode()+") of disconnection: "+reason);
                currentListener.onDisconnected(reason);
            } else {
                System.err.println(getCurrentTimestamp()+" - NetworkService: Listener is NULL when handling disconnection!");
            }
        });
    }

    private void parseServerMessage(String message, ServerListener currentListener) {
        if (message == null || message.trim().isEmpty()) return;
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
                        if (parts.length >= 2) {
                            try {
                                int id = Integer.parseInt(parts[0]);
                                String name = String.join(",", Arrays.copyOfRange(parts, 1, parts.length)); // Join remaining parts for names with commas
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
                    int gameId = Integer.parseInt(message.substring("RESP:CREATED ".length()).trim());
                    currentListener.onGameCreated(gameId);
                } catch (NumberFormatException e){
                    System.err.println(getCurrentTimestamp() + " - Error parsing game ID in CREATED: " + message);
                    currentListener.onError("Invalid game ID format from server (CREATED)");
                }
            } else if (message.startsWith("RESP:REQUEST_SENT ")) {
                try {
                    int gameId = Integer.parseInt(message.substring("RESP:REQUEST_SENT ".length()).trim());
                    currentListener.onJoinRequestSent(gameId);
                } catch (NumberFormatException e) {
                    System.err.println(getCurrentTimestamp() + " - Error parsing REQUEST_SENT game ID: " + message);
                    currentListener.onError("Invalid format from server (REQUEST_SENT)");
                }
            } else if (message.startsWith("NOTIFY:JOIN_REQUEST ")) {
                String requesterName = message.substring("NOTIFY:JOIN_REQUEST ".length()).trim();
                if (!requesterName.isEmpty()) {
                    currentListener.onJoinRequestReceived(requesterName);
                } else {
                    System.err.println(getCurrentTimestamp() + " - Malformed JOIN_REQUEST (empty name): " + message);
                    currentListener.onError("Malformed JOIN_REQUEST message from server (empty name)");
                }
            } else if (message.startsWith("RESP:JOIN_ACCEPTED ")) {
                String[] parts = message.substring("RESP:JOIN_ACCEPTED ".length()).split(" ");
                if (parts.length >= 3) {
                    try {
                        int gameId = Integer.parseInt(parts[0]);
                        char symbol = parts[1].charAt(0);
                        String opponentName = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
                        currentListener.onJoinAccepted(gameId, symbol, opponentName);
                    } catch(NumberFormatException | IndexOutOfBoundsException e){
                        System.err.println(getCurrentTimestamp() + " - Error parsing JOIN_ACCEPTED: " + message);
                        currentListener.onError("Invalid format from server (JOIN_ACCEPTED)");
                    }
                } else {
                    System.err.println(getCurrentTimestamp() + " - Malformed JOIN_ACCEPTED message: " + message);
                    currentListener.onError("Malformed JOIN_ACCEPTED message from server");
                }
            } else if (message.startsWith("RESP:JOIN_REJECTED ")) {
                String[] parts = message.substring("RESP:JOIN_REJECTED ".length()).split(" ");
                if (parts.length >= 2) {
                    try {
                        int gameId = Integer.parseInt(parts[0]);
                        String creatorName = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
                        currentListener.onJoinRejected(gameId, creatorName);
                    } catch (NumberFormatException e) {
                        System.err.println(getCurrentTimestamp() + " - Error parsing JOIN_REJECTED game ID: " + message);
                        currentListener.onError("Invalid format from server (JOIN_REJECTED ID)");
                    }
                } else {
                    System.err.println(getCurrentTimestamp() + " - Malformed JOIN_REJECTED message: " + message);
                    currentListener.onError("Malformed JOIN_REJECTED message from server");
                }
            } else if (message.startsWith("RESP:REJECT_OK ")) {
                String rejectedName = message.substring("RESP:REJECT_OK ".length()).trim();
                currentListener.onActionConfirmed("Rejected request from " + rejectedName);
            }
            else if (message.startsWith("NOTIFY:GAME_START ")) {
                String[] parts = message.substring("NOTIFY:GAME_START ".length()).split(" ");
                if (parts.length >= 3) {
                    try {
                        int gameId = Integer.parseInt(parts[0]);
                        char symbol = parts[1].charAt(0);
                        String opponentName = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
                        currentListener.onGameStart(gameId, symbol, opponentName);
                    } catch(NumberFormatException | IndexOutOfBoundsException e){
                        System.err.println(getCurrentTimestamp() + " - Error parsing GAME_START: " + message);
                        currentListener.onError("Invalid format from server (GAME_START)");
                    }
                } else {
                    System.err.println(getCurrentTimestamp() + " - Malformed GAME_START message: " + message);
                    currentListener.onError("Malformed GAME_START message from server");
                }
            } else if (message.startsWith("NOTIFY:BOARD ")) {
                String boardData = message.substring("NOTIFY:BOARD ".length());
                String[] boardCells = boardData.split(" ");
                if(boardCells.length == 9) {
                    currentListener.onBoardUpdate(boardCells);
                } else {
                    System.err.println(getCurrentTimestamp() + " - Malformed BOARD message (length != 9): " + message);
                    currentListener.onError("Malformed BOARD message from server");
                }
            } else if (message.startsWith("NOTIFY:YOUR_TURN")) {
                currentListener.onYourTurn();
            } else if (message.startsWith("NOTIFY:GAMEOVER ")) {
                String result = message.substring("NOTIFY:GAMEOVER ".length()).trim();
                currentListener.onGameOver(result);
            } else if (message.startsWith("NOTIFY:OPPONENT_LEFT")) {
                currentListener.onOpponentLeft();
            } else if (message.startsWith("NOTIFY:SERVER_SHUTDOWN")) {
                System.out.println(getCurrentTimestamp()+" - NetworkService: Handling Server Shutdown message.");
                handleDisconnection("Server is shutting down");
                closeResources();

            } else if (message.startsWith("ERROR:")) {
                String errorMsg = message.substring("ERROR:".length()).trim();
                currentListener.onError(errorMsg);
            } else {
                System.out.println(getCurrentTimestamp()+" - NetworkService: Received unhandled message type.");
                currentListener.onMessageReceived(message);
            }
        } catch (Exception e) {
            System.err.println(getCurrentTimestamp() + " - CRITICAL Error PARSING server message: [" + message + "]");
            e.printStackTrace();
            try {
                currentListener.onError("Client error parsing message: " + e.getMessage());
            } catch (Exception innerE) {
                System.err.println(getCurrentTimestamp() + " - Error calling listener.onError after parsing exception!");
                innerE.printStackTrace();
            }
        }
    }

    public void sendMessage(String message) {
        final String msgToSend = message;
        PrintWriter currentOut = this.out;
        Socket currentSocket = this.socket;

        if (running && currentOut != null && currentSocket != null && currentSocket.isConnected() && !currentSocket.isClosed() && !currentOut.checkError()) {
            try {
                System.out.println(getCurrentTimestamp() + " - NetworkService (direct send): Sending: [" + msgToSend + "]");
                currentOut.println(msgToSend);
                if (currentOut.checkError()) {
                    System.err.println(getCurrentTimestamp() + " - NetworkService (direct send): PrintWriter error detected AFTER sending. Likely disconnected.");
                    // Attempt to handle disconnection here, as reading might block
                    if(running) {
                        handleDisconnection("Send failed (PrintWriter error)");
                    }
                }
            } catch (Exception e) {
                System.err.println(getCurrentTimestamp() + " - NetworkService (direct send): Exception during send: "+e.getMessage());
                if(running) {
                    handleDisconnection("Send failed (Exception)");
                }
            }
        } else {
            System.err.println(getCurrentTimestamp() + " - Cannot send message, connection state invalid. Message: [" + msgToSend + "]");
            System.err.println(getCurrentTimestamp() + " - Send Check: running="+running+", socket="+(currentSocket != null)+", socket.isConnected="+(currentSocket != null ? currentSocket.isConnected():"N/A")+", socket.isClosed="+(currentSocket != null ? currentSocket.isClosed():"N/A")+", out="+(currentOut!=null)+", out.checkError="+(currentOut != null ? currentOut.checkError() : "N/A"));
            // If we tried to send while not running/connected, maybe trigger disconnect callback if it wasn't already triggered
            if(!running){
                Platform.runLater(() -> {
                    ServerListener l = listenerRef.get();
                    if(l != null) l.onDisconnected("Attempted send while disconnected");
                });
            }
        }
    }

    public void sendName(String name) { sendMessage("NAME " + name); }
    public void sendListRequest() { sendMessage("LIST"); }
    public void sendCreateGame() { sendMessage("CREATE"); }
    public void sendJoinRequest(int gameId) { sendMessage("JOIN_REQUEST " + gameId); }
    public void sendAcceptRequest(String playerName) { sendMessage("ACCEPT " + playerName); }
    public void sendRejectRequest(String playerName) { sendMessage("REJECT " + playerName); }
    public void sendMove(int row, int col) { sendMessage("MOVE " + row + " " + col); }
    public void sendQuit() { sendMessage("QUIT"); }

    public void disconnect() {
        System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() CALLED (Window Close/App Exit).");
        if (!running) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() ignored, already not running.");
            shutdownExecutor(); // Still ensure executor is cleaned up if called again
            return;
        }

        handleDisconnection("Disconnected by client"); // Set running to false and notify listener
        closeResources(); // Close IO streams and socket
        shutdownExecutor(); // Shutdown the thread pool

        System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() finished.");
    }

    private synchronized void closeResources() {
        System.out.println(getCurrentTimestamp() + " - NetworkService: closeResources() CALLED.");
        if (out != null) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Closing PrintWriter.");
            out.close();
            out = null;
        }
        // Close socket before input stream to interrupt blocking read
        if (socket != null && !socket.isClosed()) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Closing Socket.");
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println(getCurrentTimestamp() + " - NetworkService: Error closing socket: " + e.getMessage());
            }
            socket = null;
        }
        if (in != null) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Closing BufferedReader.");
            try {
                in.close();
            } catch (IOException e) {
                // This might happen if socket was closed already, generally safe to ignore after socket close attempt
                System.err.println(getCurrentTimestamp() + " - NetworkService: Error closing BufferedReader (may be expected after socket close): " + e.getMessage());
            }
            in = null;
        }
        System.out.println(getCurrentTimestamp() + " - NetworkService: Network resources closed.");
    }


    private void shutdownExecutor() {
        if (networkExecutor != null && !networkExecutor.isShutdown()) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Shutting down NetworkExecutor...");
            networkExecutor.shutdown();
            try {
                if (!networkExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    System.err.println(getCurrentTimestamp() + " - NetworkService: Executor did not terminate gracefully, forcing shutdown.");
                    networkExecutor.shutdownNow();
                } else {
                    System.out.println(getCurrentTimestamp() + " - NetworkService: NetworkExecutor terminated gracefully.");
                }
            } catch (InterruptedException ie) {
                System.err.println(getCurrentTimestamp() + " - NetworkService: Interrupted while waiting for executor termination.");
                networkExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isConnected() {
        // Refined check: running must be true, socket must be non-null, connected, and not closed.
        // Also check PrintWriter for critical errors (which usually indicate a closed stream).
        return running && socket != null && socket.isConnected() && !socket.isClosed() && out != null && !out.checkError() ;
    }


    public ServerListener getCurrentListener() {
        return listenerRef.get();
    }

    public boolean canAttemptConnect() {
        // Allow connection attempt if not currently running OR if the executor is shutdown/terminated.
        return !running || (networkExecutor == null || networkExecutor.isShutdown() || networkExecutor.isTerminated());
    }


    public void cleanupExecutor() {
        System.out.println(getCurrentTimestamp() + " - NetworkService: cleanupExecutor() called.");
        shutdownExecutor();
        networkExecutor = null;
    }
}