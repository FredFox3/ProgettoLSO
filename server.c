#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <signal.h>
#include <stdbool.h>
#include <time.h>
#include <sys/time.h>
#include <pthread.h> // Per i thread

#define PORT 12345
#define BUFFER_SIZE 1024
#define MAX_TOTAL_CLIENTS 10 // Max client connessi *contemporaneamente* al server
#define MAX_GAMES 5         // Max partite *attive* contemporaneamente
#define MAX_NAME_LEN 32

// Stati del Client
typedef enum {
    CLIENT_STATE_CONNECTED, // Appena connesso, prima di mandare il nome
    CLIENT_STATE_LOBBY,     // Connesso, ha mandato il nome, in attesa di comandi
    CLIENT_STATE_WAITING,   // Ha creato una partita, aspetta un avversario
    CLIENT_STATE_PLAYING    // Sta giocando una partita
} ClientState;

// Stati della Partita
typedef enum {
    GAME_STATE_EMPTY,        // Slot non usato
    GAME_STATE_WAITING,      // In attesa del secondo giocatore
    GAME_STATE_IN_PROGRESS,  // Partita in corso
    GAME_STATE_FINISHED      // Partita terminata (può essere resettata a EMPTY)
} GameState;

// Simboli Cella
typedef enum { CELL_EMPTY, CELL_X, CELL_O } Cell;

// Info Partita
typedef struct {
    int id;
    GameState state;
    Cell board[3][3];
    int player1_fd;
    int player2_fd;
    int current_turn_fd; // FD del giocatore di turno
    char player1_name[MAX_NAME_LEN];
    char player2_name[MAX_NAME_LEN];
} GameInfo;

// Info Client
typedef struct {
    int fd;
    ClientState state;
    char name[MAX_NAME_LEN];
    int game_id; // ID della partita a cui partecipa (0 se nessuna)
    bool active; // Flag per indicare se lo slot client è in uso
} ClientInfo;

// --- Variabili Globali ---
GameInfo games[MAX_GAMES];
ClientInfo clients[MAX_TOTAL_CLIENTS];
int server_fd = -1;
volatile sig_atomic_t keep_running = 1;
int next_game_id = 1; // Contatore per ID univoci partite

// Mutex per proteggere l'accesso alle liste globali
pthread_mutex_t client_list_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t game_list_mutex = PTHREAD_MUTEX_INITIALIZER;

// --- Funzioni Utilità Log ---
void get_timestamp(char *buffer, size_t len) {
    struct timeval tv;
    struct tm tm_info;
    gettimeofday(&tv, NULL);
    localtime_r(&tv.tv_sec, &tm_info);
    strftime(buffer, len, "%H:%M:%S", &tm_info);
    int ms = tv.tv_usec / 1000;
    snprintf(buffer + strlen(buffer), len - strlen(buffer), ".%03d", ms);
}

#define LOG(...) do { \
    char timestamp[30]; \
    get_timestamp(timestamp, sizeof(timestamp)); \
    printf("%s - ", timestamp); \
    printf(__VA_ARGS__); \
    fflush(stdout); \
} while(0)

#define LOG_PERROR(msg) do { \
    char timestamp[30]; \
    get_timestamp(timestamp, sizeof(timestamp)); \
    fprintf(stderr, "%s - ", timestamp); \
    fflush(stderr); \
    perror(msg); \
} while(0)

// --- Funzioni di Gestione Partita ---
void init_board(Cell board[3][3]) {
    memset(board, CELL_EMPTY, sizeof(Cell) * 3 * 3);
}

// Converte la board in una stringa per l'invio
void board_to_string(Cell board[3][3], char *out_str, size_t max_len) {
    out_str[0] = '\0';
    size_t current_len = 0;
    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {
            const char* cell_str;
            switch (board[r][c]) {
                case CELL_X: cell_str = "X"; break;
                case CELL_O: cell_str = "O"; break;
                default:     cell_str = "EMPTY"; break;
            }
            int written = snprintf(out_str + current_len, max_len - current_len, "%s", cell_str);
            if (written >= max_len - current_len) goto end; // Buffer pieno
            current_len += written;

            if (r < 2 || c < 2) {
                if (current_len < max_len - 1) {
                     out_str[current_len++] = ' ';
                     out_str[current_len] = '\0'; // Rassicura null termination
                } else {
                    goto end; // Buffer pieno
                }
            }
        }
    }
    end:; // Label per uscita anticipata
}


bool check_winner(Cell board[3][3], Cell player) {
     for (int i = 0; i < 3; i++) {
        if (board[i][0] == player && board[i][1] == player && board[i][2] == player) return true;
        if (board[0][i] == player && board[1][i] == player && board[2][i] == player) return true;
    }
    if (board[0][0] == player && board[1][1] == player && board[2][2] == player) return true;
    if (board[0][2] == player && board[1][1] == player && board[2][0] == player) return true;
    return false;
}

bool board_full(Cell board[3][3]) {
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++)
            if (board[i][j] == CELL_EMPTY) return false;
    return true;
}

// Invia un messaggio a un client specifico (thread-safe se fd è valido)
// Ritorna false in caso di errore di invio
bool send_to_client(int client_fd, const char* message) {
    if (client_fd < 0) return false;
    // Aggiungi il newline se non presente alla fine del messaggio
    char msg_with_nl[BUFFER_SIZE + 1]; // +1 per il newline
    strncpy(msg_with_nl, message, BUFFER_SIZE);
    msg_with_nl[BUFFER_SIZE] = '\0'; // Assicura terminazione
    if (strlen(msg_with_nl) == 0 || msg_with_nl[strlen(msg_with_nl) - 1] != '\n') {
        strncat(msg_with_nl, "\n", BUFFER_SIZE - strlen(msg_with_nl));
    }

    LOG("DEBUG: Sending to fd %d: [%s]", client_fd, msg_with_nl); // Mostra anche il newline
    ssize_t bytes_sent = send(client_fd, msg_with_nl, strlen(msg_with_nl), MSG_NOSIGNAL);
    if (bytes_sent <= 0) {
        if (bytes_sent == 0 || errno == EPIPE || errno == ECONNRESET || errno == ENOTCONN || errno == EBADF) {
            LOG("Send failed (client fd %d likely disconnected): %s\n", client_fd, strerror(errno));
        } else {
            LOG_PERROR("Send failed");
        }
        return false;
    }
    return true;
}


// Trova un client tramite fd (necessita mutex esternamente se si modifica la lista)
int find_client_index(int fd) {
    for (int i = 0; i < MAX_TOTAL_CLIENTS; ++i) {
        if (clients[i].active && clients[i].fd == fd) {
            return i;
        }
    }
    return -1;
}

// Trova una partita tramite id (necessita mutex esternamente se si modifica la lista)
int find_game_index(int game_id) {
     if (game_id <= 0) return -1;
    for (int i = 0; i < MAX_GAMES; ++i) {
        // Non cercare tra le partite EMPTY o FINISHED che non sono ancora state pulite
        if ((games[i].state == GAME_STATE_WAITING || games[i].state == GAME_STATE_IN_PROGRESS) && games[i].id == game_id) {
            return i;
        }
    }
    return -1;
}

// Trova una partita tramite ID, includendo quelle finite se necessario
int find_game_index_allow_finished(int game_id) {
     if (game_id <= 0) return -1;
    for (int i = 0; i < MAX_GAMES; ++i) {
        if (games[i].state != GAME_STATE_EMPTY && games[i].id == game_id) {
            return i;
        }
    }
    return -1;
}


// --- Logica di Gestione Client (Eseguita in un Thread Separato) ---
void *handle_client(void *arg) {
    int client_index = *((int*)arg);
    free(arg); // Libera memoria allocata per passare l'indice

    // Leggi i dati del client *dopo* aver acquisito il mutex iniziale
    pthread_mutex_lock(&client_list_mutex);
    if (client_index < 0 || client_index >= MAX_TOTAL_CLIENTS || !clients[client_index].active) {
        pthread_mutex_unlock(&client_list_mutex);
        LOG("ERROR: Invalid client index %d passed to thread.\n", client_index);
        return NULL;
    }
    int client_fd = clients[client_index].fd;
    // Copia nome e stato iniziali per usarli fuori dal lock, se necessario
    // char client_name_copy[MAX_NAME_LEN];
    // ClientState client_state_copy = clients[client_index].state;
    // strncpy(client_name_copy, clients[client_index].name, MAX_NAME_LEN);
    pthread_mutex_unlock(&client_list_mutex);


    char buffer[BUFFER_SIZE];
    char response[BUFFER_SIZE];
    bool client_connected = true;

    LOG("Thread started for client fd %d (index %d)\n", client_fd, client_index);

    // 1. Richiedi il nome al client
    if (!send_to_client(client_fd, "CMD:GET_NAME")) { // Aggiunge \n automaticamente
         client_connected = false; // Errore invio, chiudi subito
    }

    while (client_connected && keep_running) {
        memset(buffer, 0, BUFFER_SIZE);
        ssize_t bytes_read = read(client_fd, buffer, BUFFER_SIZE - 1);

        if (bytes_read <= 0) {
            // Prendi il lock per leggere il nome in modo sicuro prima di loggare
            pthread_mutex_lock(&client_list_mutex);
            char current_name[MAX_NAME_LEN] = "(unknown)";
            if(clients[client_index].active) strncpy(current_name, clients[client_index].name, MAX_NAME_LEN-1);
             current_name[MAX_NAME_LEN-1] = '\0';
            pthread_mutex_unlock(&client_list_mutex);

            if (bytes_read == 0) {
                LOG("Client fd %d (index %d, name '%s') disconnected gracefully.\n", client_fd, client_index, current_name);
            } else if (errno == EINTR && !keep_running) {
                LOG("Read interrupted by shutdown signal for client fd %d.\n", client_fd);
            } else if (errno != EINTR) {
                // Non usare LOG_PERROR qui perché strerror non è thread-safe per tutti gli errno
                LOG("Read error from client fd %d (index %d, name '%s'): %s\n", client_fd, client_index, current_name, strerror(errno));
            }
            client_connected = false;
            break; // Esce dal ciclo while
        }

        // Rimuovi newline finale se presente
        buffer[strcspn(buffer, "\r\n")] = 0;

        // Leggi stato e nome attuali sotto lock per logging accurato
        pthread_mutex_lock(&client_list_mutex);
        ClientState current_state = CLIENT_STATE_CONNECTED; // Default se non attivo
        char current_name[MAX_NAME_LEN] = "(unknown)";
        if(clients[client_index].active) {
            current_state = clients[client_index].state;
            strncpy(current_name, clients[client_index].name, MAX_NAME_LEN -1);
            current_name[MAX_NAME_LEN -1] = '\0';
        }
        pthread_mutex_unlock(&client_list_mutex);

        LOG("Received from fd %d (index %d, name '%s', state %d): [%s]\n",
            client_fd, client_index, current_name, current_state, buffer);


        // --- Parsing del Comando ---
        // Blocco i mutex solo quando necessario per accedere/modificare dati condivisi

        // Comando: NAME <nome> (inviato solo una volta all'inizio)
        if (strncmp(buffer, "NAME ", 5) == 0 && current_state == CLIENT_STATE_CONNECTED) {
            pthread_mutex_lock(&client_list_mutex);
            // Ricontrolla lo stato dopo aver preso il lock
            if (clients[client_index].active && clients[client_index].state == CLIENT_STATE_CONNECTED) {
                strncpy(clients[client_index].name, buffer + 5, MAX_NAME_LEN - 1);
                clients[client_index].name[MAX_NAME_LEN - 1] = '\0'; // Assicura null termination
                clients[client_index].state = CLIENT_STATE_LOBBY;
                LOG("Client fd %d registered name: %s\n", client_fd, clients[client_index].name);
                send_to_client(client_fd, "RESP:NAME_OK"); // Conferma ricezione nome
            } else {
                 LOG("WARN: Received NAME command from fd %d in unexpected state %d or client inactive.\n", client_fd, clients[client_index].state);
                 // Potresti inviare un errore al client?
            }
            pthread_mutex_unlock(&client_list_mutex);
        }
        // Comando: LIST (richiede lista partite in attesa)
        else if (strcmp(buffer, "LIST") == 0 && current_state == CLIENT_STATE_LOBBY) {
            response[0] = '\0';
            strcat(response, "RESP:GAMES_LIST;"); // Inizia la risposta
            bool first = true;

            pthread_mutex_lock(&game_list_mutex);
            for (int i = 0; i < MAX_GAMES; ++i) {
                if (games[i].state == GAME_STATE_WAITING) {
                    if (!first) strcat(response, "|"); // Separatore tra partite
                    // Aumenta la dimensione se i nomi sono lunghi
                    char game_info[MAX_NAME_LEN + 10]; // Abbastanza per ID, virgola, nome e NUL
                    snprintf(game_info, sizeof(game_info), "%d,%s", games[i].id, games[i].player1_name);
                    // Controlla se c'è abbastanza spazio nel buffer di risposta
                    if (strlen(response) + strlen(game_info) < BUFFER_SIZE - 2) { // -2 per \n e NUL
                        strcat(response, game_info);
                        first = false;
                    } else {
                        LOG("WARN: Games list response buffer full, list truncated for fd %d.\n", client_fd);
                        break; // Interrompi l'aggiunta di altre partite
                    }
                }
            }
            pthread_mutex_unlock(&game_list_mutex);

            // Non aggiungere \n qui, send_to_client lo fa
            if (!send_to_client(client_fd, response)) client_connected = false;
        }
        // Comando: CREATE (crea una nuova partita)
        else if (strcmp(buffer, "CREATE") == 0 && current_state == CLIENT_STATE_LOBBY) {
            int game_idx = -1;
            bool created = false;
            int created_game_id = -1;

            pthread_mutex_lock(&game_list_mutex);
            pthread_mutex_lock(&client_list_mutex); // Lock client list per leggere il nome

            // Verifica se il client è ancora attivo e nello stato giusto
            if (!clients[client_index].active || clients[client_index].state != CLIENT_STATE_LOBBY) {
                 LOG("WARN: CREATE command from fd %d but client not in LOBBY state or inactive.\n", client_fd);
                 pthread_mutex_unlock(&client_list_mutex);
                 pthread_mutex_unlock(&game_list_mutex);
                 send_to_client(client_fd, "ERROR:Cannot create game, invalid client state");
                 continue; // Salta al prossimo ciclo di read
            }


            // Trova uno slot libero
            for (int i = 0; i < MAX_GAMES; ++i) {
                if (games[i].state == GAME_STATE_EMPTY) {
                    game_idx = i;
                    break;
                }
            }

            if (game_idx != -1) {
                games[game_idx].id = next_game_id++;
                games[game_idx].state = GAME_STATE_WAITING;
                init_board(games[game_idx].board);
                games[game_idx].player1_fd = client_fd;
                games[game_idx].player2_fd = -1;
                games[game_idx].current_turn_fd = -1; // Nessuno gioca ancora
                strncpy(games[game_idx].player1_name, clients[client_index].name, MAX_NAME_LEN -1);
                games[game_idx].player1_name[MAX_NAME_LEN -1] = '\0';
                games[game_idx].player2_name[0] = '\0';

                // Aggiorna lo stato del client
                clients[client_index].state = CLIENT_STATE_WAITING;
                clients[client_index].game_id = games[game_idx].id;

                created = true;
                created_game_id = games[game_idx].id;
                LOG("Client %s (fd %d) created game %d (index %d)\n", clients[client_index].name, client_fd, created_game_id, game_idx);

            } else {
                strcpy(response, "ERROR:Server full, cannot create game");
                LOG("Cannot create game, server full (MAX_GAMES reached)\n");
            }

             pthread_mutex_unlock(&client_list_mutex);
             pthread_mutex_unlock(&game_list_mutex);

             if (created) {
                 snprintf(response, sizeof(response), "RESP:CREATED %d", created_game_id);
                 if (!send_to_client(client_fd, response)) client_connected = false;
                 // TODO: Notificare gli altri client in LOBBY della nuova partita (opzionale ma carino)
             } else {
                  if (!send_to_client(client_fd, response)) client_connected = false;
             }
        }
        // Comando: JOIN <id> (unisciti a una partita)
        else if (strncmp(buffer, "JOIN ", 5) == 0 && current_state == CLIENT_STATE_LOBBY) {
            int game_id_to_join = atoi(buffer + 5);
            int game_idx = -1;
            int player1_idx = -1;
            bool join_success = false;
            int player1_fd = -1;
            char player1_name[MAX_NAME_LEN] = "";
            char player2_name[MAX_NAME_LEN] = ""; // Nome di chi sta facendo join

            pthread_mutex_lock(&game_list_mutex);
            pthread_mutex_lock(&client_list_mutex); // Blocca entrambe le liste

             // Controlla stato del client che fa join
             if (!clients[client_index].active || clients[client_index].state != CLIENT_STATE_LOBBY) {
                 LOG("WARN: JOIN command from fd %d but client not in LOBBY state or inactive.\n", client_fd);
                 snprintf(response, sizeof(response), "ERROR:Cannot join game, invalid client state");
                 goto join_end; // Salta alla fine del blocco JOIN
             }
             strncpy(player2_name, clients[client_index].name, MAX_NAME_LEN -1);
             player2_name[MAX_NAME_LEN - 1] = '\0';

            game_idx = find_game_index(game_id_to_join); // Cerca partite attive (WAITING o IN_PROGRESS)

            if (game_idx != -1 && games[game_idx].state == GAME_STATE_WAITING && games[game_idx].player1_fd != client_fd) {
                // Partita trovata e in attesa, e non è quella creata da me stesso
                player1_fd = games[game_idx].player1_fd;
                strncpy(player1_name, games[game_idx].player1_name, MAX_NAME_LEN-1);
                player1_name[MAX_NAME_LEN-1] = '\0';

                player1_idx = find_client_index(player1_fd);

                if (player1_idx != -1 && clients[player1_idx].active) { // Assicurati che il P1 sia ancora attivo
                    // Aggiorna la partita
                    games[game_idx].player2_fd = client_fd;
                    strncpy(games[game_idx].player2_name, player2_name, MAX_NAME_LEN -1);
                    games[game_idx].player2_name[MAX_NAME_LEN - 1] = '\0';
                    games[game_idx].state = GAME_STATE_IN_PROGRESS;
                    games[game_idx].current_turn_fd = games[game_idx].player1_fd; // Creatore inizia (X)

                    // Aggiorna stato dei client
                    clients[client_index].state = CLIENT_STATE_PLAYING;
                    clients[client_index].game_id = game_id_to_join;
                    clients[player1_idx].state = CLIENT_STATE_PLAYING; // Anche il creatore passa a PLAYING

                    LOG("Client %s (fd %d) joined game %d (index %d) created by %s (fd %d)\n",
                        player2_name, client_fd, game_id_to_join, game_idx,
                        player1_name, player1_fd);

                     join_success = true;

                } else {
                     // Errore: creatore non trovato o disconnesso
                    snprintf(response, sizeof(response), "ERROR:Game creator (%s) no longer available for game %d", player1_name, game_id_to_join);
                     // La partita è corrotta, resettala?
                     games[game_idx].state = GAME_STATE_EMPTY;
                     LOG("ERROR: Creator (fd %d) for game %d not found or inactive in client list! Resetting game.\n", player1_fd, game_id_to_join);
                }

            } else {
                // Partita non trovata o non disponibile
                 if (game_idx != -1 && games[game_idx].player1_fd == client_fd) {
                     snprintf(response, sizeof(response), "ERROR:Cannot join your own game %d", game_id_to_join);
                 } else if (game_idx != -1) { // Trovata ma non in attesa
                     snprintf(response, sizeof(response), "ERROR:Game %d is not waiting for players (state %d)", game_id_to_join, games[game_idx].state);
                 } else { // Non trovata
                    snprintf(response, sizeof(response), "ERROR:Game %d not found", game_id_to_join);
                 }
            }

        join_end: // Label per saltare qui in caso di errore iniziale
            pthread_mutex_unlock(&client_list_mutex);
            pthread_mutex_unlock(&game_list_mutex);

            if (join_success) {
                 // Invia notifiche di inizio partita (FUORI DAI LOCK)
                 char board_str[BUFFER_SIZE]; // Abbastanza grande per 9 celle + spazi
                 // Blocca solo game_list per leggere la board aggiornata
                 pthread_mutex_lock(&game_list_mutex);
                 // Ricerca di nuovo l'indice per sicurezza (potrebbe essere cambiato?) No, l'ID è fisso.
                 int current_game_idx = find_game_index_allow_finished(game_id_to_join); // Permetti anche FINISHED se per caso
                 if(current_game_idx != -1 && games[current_game_idx].state == GAME_STATE_IN_PROGRESS) { // Controlla stato di nuovo
                     board_to_string(games[current_game_idx].board, board_str, sizeof(board_str));
                 } else {
                      LOG("ERROR: Game %d state changed unexpectedly after JOIN setup. Cannot send board.\n", game_id_to_join);
                      board_str[0] = '\0'; // Stringa vuota se errore
                 }
                 pthread_mutex_unlock(&game_list_mutex);


                // A chi si è unito (O)
                snprintf(response, sizeof(response), "RESP:JOIN_OK %d O %s", game_id_to_join, player1_name);
                if (!send_to_client(client_fd, response)) client_connected = false;
                snprintf(response, sizeof(response), "NOTIFY:GAME_START %d O %s", game_id_to_join, player1_name);
                if (!send_to_client(client_fd, response)) client_connected = false;
                if (strlen(board_str) > 0) {
                    snprintf(response, sizeof(response), "NOTIFY:BOARD %s", board_str);
                    if (!send_to_client(client_fd, response)) client_connected = false;
                }
                // Nessuna notifica di turno a chi si unisce, inizia P1


                // Al creatore (X)
                 snprintf(response, sizeof(response), "NOTIFY:GAME_START %d X %s", game_id_to_join, player2_name);
                 if (!send_to_client(player1_fd, response)) { /* Errore invio P1 */ }
                 if (strlen(board_str) > 0) {
                    snprintf(response, sizeof(response), "NOTIFY:BOARD %s", board_str);
                    if (!send_to_client(player1_fd, response)) { /* Errore invio P1 */ }
                 }
                 if (!send_to_client(player1_fd, "NOTIFY:YOUR_TURN")) { /* Errore invio P1 */ }


            } else {
                 // Invia messaggio di errore (response è già stato preparato)
                 if (!send_to_client(client_fd, response)) client_connected = false;
            }
        }
         // Comando: MOVE <r> <c> (effettua una mossa)
        else if (strncmp(buffer, "MOVE ", 5) == 0 && current_state == CLIENT_STATE_PLAYING) {
            int r, c;
            if (sscanf(buffer + 5, "%d %d", &r, &c) == 2) {
                int game_idx = -1;
                int current_game_id = 0;
                int opponent_fd = -1;
                Cell my_symbol = CELL_EMPTY;
                bool move_ok = false;
                bool game_ended = false;
                char end_reason[5] = ""; // "WIN", "DRAW"
                char board_str_after_move[BUFFER_SIZE] = ""; // Per inviare la board DOPO la mossa

                pthread_mutex_lock(&game_list_mutex);
                pthread_mutex_lock(&client_list_mutex); // Serve per leggere game_id

                // Verifica stato client e trova ID partita
                 if (!clients[client_index].active || clients[client_index].state != CLIENT_STATE_PLAYING) {
                     LOG("WARN: MOVE command from fd %d but client not in PLAYING state or inactive.\n", client_fd);
                     snprintf(response, sizeof(response), "ERROR:Cannot make move, invalid client state");
                     goto move_end; // Salta alla fine del blocco MOVE
                 }
                 current_game_id = clients[client_index].game_id;


                game_idx = find_game_index(current_game_id); // Cerca solo partite IN_PROGRESS

                if (game_idx != -1) { // Partita trovata e in corso
                     if (games[game_idx].current_turn_fd == client_fd) {
                        if (r >= 0 && r < 3 && c >= 0 && c < 3 && games[game_idx].board[r][c] == CELL_EMPTY) {
                            // Mossa valida
                            my_symbol = (client_fd == games[game_idx].player1_fd) ? CELL_X : CELL_O;
                            games[game_idx].board[r][c] = my_symbol;
                            move_ok = true;
                            LOG("Player %s (fd %d) made move %d,%d in game %d\n", clients[client_index].name, client_fd, r, c, current_game_id);

                            // Ottieni la stringa della board *dopo* la mossa
                            board_to_string(games[game_idx].board, board_str_after_move, sizeof(board_str_after_move));

                            // Controlla fine partita
                            if (check_winner(games[game_idx].board, my_symbol)) {
                                game_ended = true;
                                strcpy(end_reason, "WIN");
                                games[game_idx].state = GAME_STATE_FINISHED; // Cambia stato partita
                                LOG("Game %d finished. Winner: %s\n", current_game_id, clients[client_index].name);
                            } else if (board_full(games[game_idx].board)) {
                                game_ended = true;
                                strcpy(end_reason, "DRAW");
                                games[game_idx].state = GAME_STATE_FINISHED; // Cambia stato partita
                                LOG("Game %d finished. Draw.\n", current_game_id);
                            }

                            // Determina avversario e cambia turno se la partita continua
                            opponent_fd = (client_fd == games[game_idx].player1_fd) ? games[game_idx].player2_fd : games[game_idx].player1_fd;
                            if (!game_ended) {
                                games[game_idx].current_turn_fd = opponent_fd;
                            } else {
                                games[game_idx].current_turn_fd = -1; // Nessuno gioca più
                                // Aggiorna stato dei client a LOBBY
                                clients[client_index].state = CLIENT_STATE_LOBBY;
                                clients[client_index].game_id = 0;
                                int opponent_idx = find_client_index(opponent_fd);
                                if (opponent_idx != -1 && clients[opponent_idx].active) { // Assicurati che opp sia attivo
                                    clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                                    clients[opponent_idx].game_id = 0;
                                } else {
                                     LOG("WARN: Opponent fd %d for game %d not found or inactive when game ended.\n", opponent_fd, current_game_id);
                                     opponent_fd = -1; // Non inviare notifiche se l'opp non c'è più
                                }
                            }

                        } else {
                             snprintf(response, sizeof(response), "ERROR:Invalid move (%d,%d) - occupied or out of bounds", r, c);
                        }
                    } else {
                         snprintf(response, sizeof(response), "ERROR:Not your turn (current turn fd: %d)", games[game_idx].current_turn_fd);
                    }
                } else {
                     snprintf(response, sizeof(response), "ERROR:Game %d not found or not in progress", current_game_id);
                     // Se la partita non è trovata, forse il client è in uno stato inconsistente?
                     // Riportalo alla Lobby per sicurezza?
                     clients[client_index].state = CLIENT_STATE_LOBBY;
                     clients[client_index].game_id = 0;
                     LOG("WARN: Client fd %d tried to move in non-existent/finished game %d. Resetting client to LOBBY.\n", client_fd, current_game_id);
                }

            move_end:
                pthread_mutex_unlock(&client_list_mutex);
                pthread_mutex_unlock(&game_list_mutex);


                // Se la mossa è stata valida, invia aggiornamenti (FUORI DAI LOCK)
                if (move_ok) {
                    // Invia board aggiornata a entrambi
                    if (strlen(board_str_after_move) > 0) {
                        snprintf(response, sizeof(response), "NOTIFY:BOARD %s", board_str_after_move);
                        if (!send_to_client(client_fd, response)) client_connected = false;
                        if (opponent_fd != -1) send_to_client(opponent_fd, response);
                    }

                    if (game_ended) {
                         // Invia risultato partita a entrambi
                         // A chi ha fatto la mossa
                         snprintf(response, sizeof(response), "NOTIFY:GAMEOVER %s", (strcmp(end_reason,"WIN")==0) ? "WIN" : "DRAW");
                         if (!send_to_client(client_fd, response)) client_connected = false;
                         // All'avversario (se ancora connesso)
                         if (opponent_fd != -1) {
                            snprintf(response, sizeof(response), "NOTIFY:GAMEOVER %s", (strcmp(end_reason,"WIN")==0) ? "LOSE" : "DRAW");
                            send_to_client(opponent_fd, response);
                         }

                         // Resetta lo slot partita (opzionale, potrebbe essere fatto da un thread di pulizia)
                         // Potrebbe causare problemi se i client non sono ancora tornati alla lobby?
                         // Meglio lasciarlo GAME_STATE_FINISHED per ora.
                         // pthread_mutex_lock(&game_list_mutex);
                         // if(game_idx != -1) games[game_idx].state = GAME_STATE_EMPTY;
                         // pthread_mutex_unlock(&game_list_mutex);


                    } else {
                        // Invia notifica di turno all'avversario (se ancora connesso)
                         if (opponent_fd != -1) send_to_client(opponent_fd, "NOTIFY:YOUR_TURN");
                    }
                } else {
                    // Mossa non valida, invia l'errore preparato in 'response'
                     if (!send_to_client(client_fd, response)) client_connected = false;
                }

            } else {
                 strcpy(response, "ERROR:Invalid move format. Use: MOVE <row> <col>");
                 if (!send_to_client(client_fd, response)) client_connected = false;
            }
        }
         // Comando: QUIT (abbandona partita/lobby)
         else if (strcmp(buffer, "QUIT") == 0) {
              pthread_mutex_lock(&client_list_mutex);
              char current_name_quit[MAX_NAME_LEN] = "(unknown)";
              if(clients[client_index].active) strncpy(current_name_quit, clients[client_index].name, MAX_NAME_LEN-1);
              current_name_quit[MAX_NAME_LEN-1] = '\0';
              pthread_mutex_unlock(&client_list_mutex);
             LOG("Client %s (fd %d) requested QUIT.\n", current_name_quit, client_fd);
             client_connected = false; // Forza uscita dal loop e cleanup
         }
        // Comando non riconosciuto
        else {
            // Non inviare errore se il client non ha ancora mandato il nome
             if (current_state != CLIENT_STATE_CONNECTED) {
                 snprintf(response, sizeof(response), "ERROR:Unknown command or invalid state (%d) for command: %s", current_state, buffer);
                 if (!send_to_client(client_fd, response)) client_connected = false;
             }
        }
    } // End while(client_connected)

    // --- Cleanup del Client ---
    // Leggi nome e game_id prima di marcarlo inattivo
    pthread_mutex_lock(&client_list_mutex);
    int game_id_on_disconnect = clients[client_index].game_id;
    char name_on_disconnect[MAX_NAME_LEN];
    strncpy(name_on_disconnect, clients[client_index].name, MAX_NAME_LEN-1);
    name_on_disconnect[MAX_NAME_LEN-1] = '\0';
    LOG("Cleaning up client fd %d (index %d, name '%s')\n", client_fd, client_index, name_on_disconnect);
    pthread_mutex_unlock(&client_list_mutex);


    close(client_fd); // Chiudi socket PRIMA di modificare le strutture dati condivise

    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex); // Blocca entrambe per sicurezza

    // Ricontrolla l'indice e lo stato attivo, potrebbero essere cambiati
     if (client_index < 0 || client_index >= MAX_TOTAL_CLIENTS || !clients[client_index].active || clients[client_index].fd != client_fd) {
         // Il client è già stato pulito o riassegnato? Logga e esci.
         LOG("WARN: Client fd %d (index %d) already cleaned up or reallocated during cleanup phase.\n", client_fd, client_index);
         pthread_mutex_unlock(&game_list_mutex);
         pthread_mutex_unlock(&client_list_mutex);
         return NULL;
     }

    // Marca lo slot client come libero
    clients[client_index].active = false;
    clients[client_index].fd = -1;
    clients[client_index].game_id = 0;
    clients[client_index].state = CLIENT_STATE_CONNECTED; // Reset state iniziale
    clients[client_index].name[0] = '\0';


    // Se il client era in una partita, gestisci l'abbandono
    if (game_id_on_disconnect > 0) {
        int game_idx = find_game_index_allow_finished(game_id_on_disconnect); // Cerca anche se finita ma non pulita
        if (game_idx != -1 && (games[game_idx].state == GAME_STATE_WAITING || games[game_idx].state == GAME_STATE_IN_PROGRESS)) {
            LOG("Client was in active game %d (index %d). Notifying opponent and cleaning up game.\n", game_id_on_disconnect, game_idx);
            int opponent_fd = -1;
            int opponent_idx = -1;

            if (games[game_idx].player1_fd == client_fd) { // Il client disconnesso era P1
                 opponent_fd = games[game_idx].player2_fd; // Può essere -1 se P2 non si era ancora unito
            } else if (games[game_idx].player2_fd == client_fd) { // Il client disconnesso era P2
                opponent_fd = games[game_idx].player1_fd; // P1 è sempre != -1 se P2 esiste
            }

             // Notifica l'avversario (se esiste ed è ancora connesso)
             if (opponent_fd != -1) {
                  opponent_idx = find_client_index(opponent_fd); // Cerca l'indice dell'avversario
                  if (opponent_idx != -1 && clients[opponent_idx].active) { // Assicurati che sia ancora attivo
                        // Invia solo OPPONENT_LEFT. Il client gestirà la vittoria.
                        send_to_client(opponent_fd, "NOTIFY:OPPONENT_LEFT");
                        // Riporta l'avversario alla lobby
                        clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                        clients[opponent_idx].game_id = 0;
                        LOG("Opponent %s (fd %d) notified and moved back to LOBBY.\n", clients[opponent_idx].name, opponent_fd);
                  } else {
                       LOG("WARN: Opponent fd %d for game %d not found or inactive during disconnect cleanup.\n", opponent_fd, game_id_on_disconnect);
                  }
             } else {
                 LOG("Client %s disconnected from game %d while waiting for opponent.\n", name_on_disconnect, game_id_on_disconnect);
             }

            // Pulisci/Resetta la partita in ogni caso (anche se era solo in attesa)
             games[game_idx].state = GAME_STATE_EMPTY; // Resetta completamente lo slot partita
             games[game_idx].player1_fd = -1;
             games[game_idx].player2_fd = -1;
             games[game_idx].current_turn_fd = -1;
             games[game_idx].player1_name[0] = '\0';
             games[game_idx].player2_name[0] = '\0';
             games[game_idx].id = 0; // Opzionale: azzera anche l'ID o lascialo per log storici
             LOG("Game %d (index %d) reset due to player leaving.\n", game_id_on_disconnect, game_idx);
        } else if (game_idx != -1 && games[game_idx].state == GAME_STATE_FINISHED) {
             LOG("Client %s disconnected after game %d finished. No action needed on game state.\n", name_on_disconnect, game_id_on_disconnect);
             // Potremmo voler pulire lo slot GAME_STATE_FINISHED qui se non altrove
             // games[game_idx].state = GAME_STATE_EMPTY;
        }
    }

    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    LOG("Thread finished for client fd %d.\n", client_fd);
    return NULL;
}


// --- Funzione Gestione Segnali ---
void handle_signal(int signal) {
    keep_running = 0;
    // Scrittura sicura da signal handler (evita printf)
    char msg[] = "\n!!! Signal received, initiating server shutdown... !!!\n";
    ssize_t written = write(STDOUT_FILENO, msg, strlen(msg)); // Ignora risultato write
    (void)written; // Per evitare warning "unused result"

    // Chiudi il server socket per sbloccare accept()
    // È relativamente sicuro chiudere un fd da un signal handler
     int saved_errno = errno; // Salva errno
     if (server_fd != -1) {
         shutdown(server_fd, SHUT_RDWR); // Tenta di sbloccare accept
         close(server_fd);
         server_fd = -1;   // Impedisce riutilizzo in cleanup principale
     }
     errno = saved_errno; // Ripristina errno
}


// --- Funzione Cleanup Globale ---
void cleanup() {
    LOG("Cleaning up resources...\n");

    // Invia messaggio di shutdown ai client attivi e chiudi connessioni
    pthread_mutex_lock(&client_list_mutex);
    for (int i = 0; i < MAX_TOTAL_CLIENTS; i++) {
        if (clients[i].active && clients[i].fd != -1) {
            LOG("Closing connection for client %d (fd %d, name: %s)\n", i, clients[i].fd, clients[i].name);
            send_to_client(clients[i].fd, "NOTIFY:SERVER_SHUTDOWN");
            shutdown(clients[i].fd, SHUT_RDWR); // Tenta di sbloccare read bloccanti nei thread client
            close(clients[i].fd);
            clients[i].fd = -1; // Marca come chiuso
        }
        // Resetta comunque lo stato per sicurezza
        clients[i].active = false;
        clients[i].game_id = 0;
        clients[i].state = CLIENT_STATE_CONNECTED;
        clients[i].name[0] = '\0';
    }
    pthread_mutex_unlock(&client_list_mutex);

    // Il server_fd potrebbe essere già stato chiuso dall'handler del segnale
    // Non è necessario chiuderlo di nuovo qui se server_fd == -1

     // Distruggi i mutex
     pthread_mutex_destroy(&client_list_mutex);
     pthread_mutex_destroy(&game_list_mutex);


    LOG("Server stopped.\n");
}

// --- Main ---
int main() {
    struct sockaddr_in address;
    int addrlen = sizeof(address);
    pthread_t thread_id; // Spostato fuori dal loop

    // Imposta gestione segnali
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = handle_signal;
    action.sa_flags = SA_RESTART; // Riprova le syscall interrotte se possibile (es. accept)
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGTERM, &action, NULL);
    // Ignora SIGPIPE per gestire errori di send/write nel codice
    signal(SIGPIPE, SIG_IGN);


    // Inizializza liste client e partite
    pthread_mutex_lock(&client_list_mutex);
    for(int i=0; i<MAX_TOTAL_CLIENTS; ++i) {
        clients[i].active = false;
        clients[i].fd = -1;
        clients[i].game_id = 0;
        clients[i].state = CLIENT_STATE_CONNECTED;
        clients[i].name[0] = '\0';
    }
    pthread_mutex_unlock(&client_list_mutex);

    pthread_mutex_lock(&game_list_mutex);
    for(int i=0; i<MAX_GAMES; ++i) {
        games[i].state = GAME_STATE_EMPTY;
        games[i].id = 0;
        games[i].player1_fd = -1;
        games[i].player2_fd = -1;
    }
    pthread_mutex_unlock(&game_list_mutex);


    // --- Setup Socket Server ---
    if ((server_fd = socket(AF_INET, SOCK_STREAM, 0)) == 0) {
        LOG_PERROR("socket failed");
        exit(EXIT_FAILURE);
    }
    int opt = 1;
    if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt))) {
        LOG_PERROR("setsockopt SO_REUSEADDR failed");
        // Non fatale, continua
    }
     // Potrebbe essere utile SO_REUSEPORT se si volessero più processi server sulla stessa porta
     // if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEPORT, &opt, sizeof(opt))) {
     //    LOG_PERROR("setsockopt SO_REUSEPORT failed");
     // }

    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(PORT);
    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address))<0) {
        LOG_PERROR("bind failed");
        close(server_fd);
        exit(EXIT_FAILURE);
    }
    if (listen(server_fd, MAX_TOTAL_CLIENTS) < 0) { // Metti in coda fino a MAX_TOTAL_CLIENTS
        LOG_PERROR("listen failed");
        close(server_fd);
        exit(EXIT_FAILURE);
    }

    LOG("Server listening on port %d... (Max Clients: %d, Max Games: %d)\n", PORT, MAX_TOTAL_CLIENTS, MAX_GAMES);

    // --- Ciclo Accettazione Connessioni ---
    while (keep_running) {
        // Nota: addrlen deve essere reimpostato ad ogni chiamata ad accept
        addrlen = sizeof(address);
        int new_socket = accept(server_fd, (struct sockaddr *)&address, (socklen_t*)&addrlen);

        if (new_socket < 0) {
            // Se keep_running è falso, l'uscita è intenzionale (probabilmente da handle_signal)
            if (!keep_running) {
                 if(errno == EBADF) { // EBADF è atteso se il socket è stato chiuso da handle_signal
                    LOG("Accept stopped: server socket closed by signal handler.\n");
                 } else {
                    LOG("Accept stopped: keep_running is false (errno: %d %s).\n", errno, strerror(errno));
                 }
                break; // Esce dal loop while
            } else if (errno == EINTR) {
                // Interrotto da un segnale ma keep_running è ancora vero? Riprova.
                LOG("Accept interrupted (EINTR), restarting accept...\n");
                continue;
            } else {
                 // Altro errore su accept
                 LOG_PERROR("accept failed");
                 // Potrebbe essere un errore temporaneo? O fatale?
                 // Per ora continuiamo, ma in un server reale potrebbe essere necessario un handling più robusto
                 sleep(1); // Evita busy-loop in caso di errore persistente
                 continue;
            }
        }

        LOG("New connection accepted, assigning fd %d\n", new_socket);

        // Trova uno slot client libero e avvia il thread handler
        pthread_mutex_lock(&client_list_mutex);
        int client_index = -1;
        for (int i = 0; i < MAX_TOTAL_CLIENTS; ++i) {
            if (!clients[i].active) {
                client_index = i;
                break;
            }
        }

        if (client_index != -1) {
            clients[client_index].active = true;
            clients[client_index].fd = new_socket;
            clients[client_index].state = CLIENT_STATE_CONNECTED;
            clients[client_index].game_id = 0;
            clients[client_index].name[0] = '\0';

            // Alloca memoria per passare l'indice al thread
            // È fondamentale allocare dinamicamente perché la variabile locale 'client_index'
            // cambierebbe prima che il nuovo thread possa leggerla.
            int *p_client_index = malloc(sizeof(int));
             if (p_client_index == NULL) {
                 LOG_PERROR("Failed to allocate memory for thread arg");
                 close(new_socket);
                 clients[client_index].active = false; // Libera lo slot
                 pthread_mutex_unlock(&client_list_mutex);
                 continue; // Prova ad accettare la prossima connessione
             }
            *p_client_index = client_index;

            if (pthread_create(&thread_id, NULL, handle_client, p_client_index) != 0) {
                LOG_PERROR("pthread_create failed");
                close(new_socket);
                clients[client_index].active = false; // Libera slot
                 free(p_client_index); // Libera memoria se create fallisce
            } else {
                pthread_detach(thread_id); // Non aspettiamo la terminazione del thread qui
                 LOG("Client assigned to index %d, handler thread created (TID: %lu).\n", client_index, (unsigned long)thread_id);
            }
        } else {
            LOG("Server full (MAX_TOTAL_CLIENTS reached), rejecting connection fd %d\n", new_socket);
            char reject_msg[] = "ERROR:Server is full. Try again later.\n";
            send(new_socket, reject_msg, strlen(reject_msg), MSG_NOSIGNAL);
            close(new_socket);
        }
        pthread_mutex_unlock(&client_list_mutex);

    } // End while(keep_running)

    LOG("Server main loop finished. Starting cleanup...\n");
    cleanup();
    return 0;
}