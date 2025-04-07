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
#include <pthread.h>

#define PORT 12345
#define BUFFER_SIZE 1024
#define MAX_TOTAL_CLIENTS 10
#define MAX_GAMES 5
#define MAX_NAME_LEN 32

typedef enum {
    CLIENT_STATE_CONNECTED,
    CLIENT_STATE_LOBBY,
    CLIENT_STATE_WAITING, // Creatore in attesa o Richiedente in attesa di risposta
    CLIENT_STATE_PLAYING
} ClientState;

typedef enum {
    GAME_STATE_EMPTY,
    GAME_STATE_WAITING,
    GAME_STATE_IN_PROGRESS,
    GAME_STATE_FINISHED
} GameState;

typedef enum { CELL_EMPTY, CELL_X, CELL_O } Cell;

typedef struct {
    int id;
    GameState state;
    Cell board[3][3];
    int player1_fd;
    int player2_fd;
    int current_turn_fd;
    char player1_name[MAX_NAME_LEN];
    char player2_name[MAX_NAME_LEN];
    int pending_joiner_fd;
    char pending_joiner_name[MAX_NAME_LEN];
} GameInfo;

typedef struct {
    int fd;
    ClientState state;
    char name[MAX_NAME_LEN];
    int game_id; // 0 if not in game or waiting, >0 otherwise
    bool active;
} ClientInfo;


GameInfo games[MAX_GAMES];
ClientInfo clients[MAX_TOTAL_CLIENTS];
int server_fd = -1;
volatile sig_atomic_t keep_running = 1;
int next_game_id = 1;

pthread_mutex_t client_list_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t game_list_mutex = PTHREAD_MUTEX_INITIALIZER;


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


void init_board(Cell board[3][3]) {
    memset(board, CELL_EMPTY, sizeof(Cell) * 3 * 3);
}

void board_to_string(Cell board[3][3], char *out_str, size_t max_len) {
    out_str[0] = '\0';
    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {
            char cell_char;
            switch (board[r][c]) {
                case CELL_X: cell_char = 'X'; break;
                case CELL_O: cell_char = 'O'; break;
                default: strcpy(out_str + strlen(out_str), "EMPTY"); goto next_cell;
            }
            char cell_str[3];
            snprintf(cell_str, sizeof(cell_str), "%c", cell_char);
            strncat(out_str, cell_str, max_len - strlen(out_str) -1);

            next_cell:
            if (r < 2 || c < 2) {
                 strncat(out_str, " ", max_len - strlen(out_str) -1);
            }
        }
    }
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

bool send_to_client(int client_fd, const char* message) {
    if (client_fd < 0) return false;
    ssize_t bytes_sent = send(client_fd, message, strlen(message), MSG_NOSIGNAL);
    if (bytes_sent <= 0) {
        if (bytes_sent == 0 || errno == EPIPE || errno == ECONNRESET) {
            LOG("Send failed (client fd %d likely disconnected): %s\n", client_fd, strerror(errno));
        } else {
            LOG_PERROR("Send failed");
        }
        return false;
    }
    return true;
}

int find_client_index(int fd) {
    for (int i = 0; i < MAX_TOTAL_CLIENTS; ++i) {
        if (clients[i].active && clients[i].fd == fd) {
            return i;
        }
    }
    return -1;
}

int find_game_index(int game_id) {
     if (game_id <= 0) return -1;
    for (int i = 0; i < MAX_GAMES; ++i) {
        if (games[i].state != GAME_STATE_EMPTY && games[i].id == game_id) {
            return i;
        }
    }
    return -1;
}


void *handle_client(void *arg) {
    int client_index = *((int*)arg);
    free(arg);

    int client_fd = clients[client_index].fd;
    char buffer[BUFFER_SIZE];
    char response[BUFFER_SIZE];
    bool client_connected = true;

    LOG("Thread started for client fd %d (index %d)\n", client_fd, client_index);

    if (!send_to_client(client_fd, "CMD:GET_NAME\n")) {
         client_connected = false;
    }

    while (client_connected && keep_running) {
        memset(buffer, 0, BUFFER_SIZE);
        ssize_t bytes_read = read(client_fd, buffer, BUFFER_SIZE - 1);

        if (bytes_read <= 0) {
            if (bytes_read == 0) {
                LOG("Client fd %d (index %d, name %s) disconnected gracefully.\n", client_fd, client_index, clients[client_index].name);
            } else if (errno == EINTR && !keep_running) {
                LOG("Read interrupted by shutdown signal for client fd %d.\n", client_fd);
            } else if (errno != EINTR) {
                LOG("Read error from client fd %d (index %d, name %s): %s\n", client_fd, client_index, clients[client_index].name, strerror(errno));
            }
            client_connected = false;
            break;
        }

        buffer[strcspn(buffer, "\r\n")] = 0;
        LOG("Received from fd %d (index %d, name '%s', state %d): [%s]\n",
            client_fd, client_index, clients[client_index].name, clients[client_index].state, buffer);


        if (strncmp(buffer, "NAME ", 5) == 0 && clients[client_index].state == CLIENT_STATE_CONNECTED) {
            pthread_mutex_lock(&client_list_mutex);
            strncpy(clients[client_index].name, buffer + 5, MAX_NAME_LEN - 1);
            clients[client_index].name[MAX_NAME_LEN - 1] = '\0';
            clients[client_index].state = CLIENT_STATE_LOBBY;
            LOG("Client fd %d registered name: %s\n", client_fd, clients[client_index].name);
             send_to_client(client_fd, "RESP:NAME_OK\n");
            pthread_mutex_unlock(&client_list_mutex);
        }
        else if (strcmp(buffer, "LIST") == 0 && clients[client_index].state == CLIENT_STATE_LOBBY) {
            response[0] = '\0';
            strcat(response, "RESP:GAMES_LIST;");
            bool first = true;

            pthread_mutex_lock(&game_list_mutex);
            for (int i = 0; i < MAX_GAMES; ++i) {
                if (games[i].state == GAME_STATE_WAITING) {
                    if (!first) strcat(response, "|");
                    char game_info[100];
                    snprintf(game_info, sizeof(game_info), "%d,%s", games[i].id, games[i].player1_name);
                    strcat(response, game_info);
                    first = false;
                }
            }
            pthread_mutex_unlock(&game_list_mutex);

            strcat(response, "\n");
            if (!send_to_client(client_fd, response)) client_connected = false;
        }
        else if (strcmp(buffer, "CREATE") == 0 && clients[client_index].state == CLIENT_STATE_LOBBY) {
            int game_idx = -1;
            pthread_mutex_lock(&game_list_mutex);
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
                games[game_idx].current_turn_fd = -1;
                strncpy(games[game_idx].player1_name, clients[client_index].name, MAX_NAME_LEN);
                games[game_idx].player2_name[0] = '\0';
                games[game_idx].pending_joiner_fd = -1;
                games[game_idx].pending_joiner_name[0] = '\0';


                pthread_mutex_lock(&client_list_mutex);
                clients[client_index].state = CLIENT_STATE_WAITING;
                clients[client_index].game_id = games[game_idx].id;
                pthread_mutex_unlock(&client_list_mutex);

                snprintf(response, sizeof(response), "RESP:CREATED %d\n", games[game_idx].id);
                LOG("Client %s (fd %d) created game %d (index %d)\n", clients[client_index].name, client_fd, games[game_idx].id, game_idx);
            } else {
                strcpy(response, "ERROR:Server full, cannot create game\n");
                LOG("Cannot create game, server full (MAX_GAMES reached)\n");
            }
            pthread_mutex_unlock(&game_list_mutex);
            if (!send_to_client(client_fd, response)) client_connected = false;
        }
        else if (strncmp(buffer, "JOIN_REQUEST ", 13) == 0 && clients[client_index].state == CLIENT_STATE_LOBBY) {
            int game_id_to_join = atoi(buffer + 13);
            int game_idx = -1;
            int creator_fd = -1;

            pthread_mutex_lock(&game_list_mutex);
            pthread_mutex_lock(&client_list_mutex);

            game_idx = find_game_index(game_id_to_join);

            if (game_idx != -1 && games[game_idx].state == GAME_STATE_WAITING && games[game_idx].player1_fd != client_fd) {
                if (games[game_idx].pending_joiner_fd == -1) {
                    creator_fd = games[game_idx].player1_fd;
                    int creator_client_idx = find_client_index(creator_fd);

                    if (creator_client_idx != -1) {

                        games[game_idx].pending_joiner_fd = client_fd;
                        strncpy(games[game_idx].pending_joiner_name, clients[client_index].name, MAX_NAME_LEN - 1);
                        games[game_idx].pending_joiner_name[MAX_NAME_LEN - 1] = '\0';

                        LOG("Client %s (fd %d) requested to join game %d (index %d). Notifying creator %s (fd %d)\n",
                            clients[client_index].name, client_fd, game_id_to_join, game_idx,
                            games[game_idx].player1_name, creator_fd);


                        snprintf(response, sizeof(response), "NOTIFY:JOIN_REQUEST %s\n", clients[client_index].name);
                        if (!send_to_client(creator_fd, response)) {
                            LOG("Failed to send join request notification to creator fd %d. Cancelling request.\n", creator_fd);
                            games[game_idx].pending_joiner_fd = -1;
                            games[game_idx].pending_joiner_name[0] = '\0';
                            snprintf(response, sizeof(response), "ERROR:Could not notify game creator (maybe disconnected?).\n");
                            if (!send_to_client(client_fd, response)) client_connected = false;
                        } else {
                            snprintf(response, sizeof(response), "RESP:REQUEST_SENT %d\n", game_id_to_join);
                            if (!send_to_client(client_fd, response)) client_connected = false;
                        }
                    } else {
                         LOG("ERROR: Creator (fd %d) for game %d not found in client list during join request!\n", creator_fd, game_id_to_join);
                         snprintf(response, sizeof(response), "ERROR:Game creator seems disconnected. Cannot join game %d\n", game_id_to_join);
                         if (!send_to_client(client_fd, response)) client_connected = false;
                    }
                } else {
                    LOG("Game %d (index %d) already has a pending join request from %s (fd %d). Rejecting new request from %s (fd %d)\n",
                        game_id_to_join, game_idx,
                        games[game_idx].pending_joiner_name, games[game_idx].pending_joiner_fd,
                        clients[client_index].name, client_fd);
                    snprintf(response, sizeof(response), "ERROR:Game creator is busy with another request for game %d\n", game_id_to_join);
                    if (!send_to_client(client_fd, response)) client_connected = false;
                }
            } else {
                 if (game_idx != -1 && games[game_idx].player1_fd == client_fd) {
                     snprintf(response, sizeof(response), "ERROR:Cannot request to join your own game %d\n", game_id_to_join);
                 } else if (game_idx != -1 && games[game_idx].state != GAME_STATE_WAITING) {
                     snprintf(response, sizeof(response), "ERROR:Game %d is not waiting for players\n", game_id_to_join);
                 } else if (game_idx == -1) {
                    snprintf(response, sizeof(response), "ERROR:Game %d not found\n", game_id_to_join);
                 }
                if (!send_to_client(client_fd, response)) client_connected = false;
            }

            pthread_mutex_unlock(&client_list_mutex);
            pthread_mutex_unlock(&game_list_mutex);
        }
        else if (strncmp(buffer, "ACCEPT ", 7) == 0 && clients[client_index].state == CLIENT_STATE_WAITING) {
            char accepted_player_name[MAX_NAME_LEN];
            strncpy(accepted_player_name, buffer + 7, MAX_NAME_LEN - 1);
            accepted_player_name[MAX_NAME_LEN - 1] = '\0';

            int game_idx = -1;
            int joiner_fd = -1;
            int joiner_client_idx = -1;
            int my_game_id = clients[client_index].game_id;

            pthread_mutex_lock(&game_list_mutex);
            pthread_mutex_lock(&client_list_mutex);

            game_idx = find_game_index(my_game_id);

            if (game_idx != -1 && games[game_idx].player1_fd == client_fd) {
                if (games[game_idx].pending_joiner_fd != -1 && strcmp(games[game_idx].pending_joiner_name, accepted_player_name) == 0) {

                    joiner_fd = games[game_idx].pending_joiner_fd;
                    joiner_client_idx = find_client_index(joiner_fd);

                    if (joiner_client_idx != -1 && clients[joiner_client_idx].state == CLIENT_STATE_LOBBY) {
                         LOG("Creator %s (fd %d) ACCEPTED join request from %s (fd %d) for game %d\n",
                            clients[client_index].name, client_fd, accepted_player_name, joiner_fd, my_game_id);


                        games[game_idx].player2_fd = joiner_fd;
                        strncpy(games[game_idx].player2_name, games[game_idx].pending_joiner_name, MAX_NAME_LEN);
                        games[game_idx].state = GAME_STATE_IN_PROGRESS;
                        games[game_idx].current_turn_fd = games[game_idx].player1_fd;


                        games[game_idx].pending_joiner_fd = -1;
                        games[game_idx].pending_joiner_name[0] = '\0';


                        clients[client_index].state = CLIENT_STATE_PLAYING;
                        clients[joiner_client_idx].state = CLIENT_STATE_PLAYING;
                        clients[joiner_client_idx].game_id = my_game_id;


                        char board_str[BUFFER_SIZE];
                        board_to_string(games[game_idx].board, board_str, sizeof(board_str));


                        snprintf(response, sizeof(response), "RESP:JOIN_ACCEPTED %d O %s\n", my_game_id, games[game_idx].player1_name);
                        send_to_client(joiner_fd, response);
                        snprintf(response, sizeof(response), "NOTIFY:GAME_START %d O %s\n", my_game_id, games[game_idx].player1_name);
                        send_to_client(joiner_fd, response);
                        snprintf(response, sizeof(response), "NOTIFY:BOARD %s\n", board_str);
                        send_to_client(joiner_fd, response);


                        snprintf(response, sizeof(response), "NOTIFY:GAME_START %d X %s\n", my_game_id, games[game_idx].player2_name);
                        if (!send_to_client(client_fd, response)) { client_connected = false; }
                        snprintf(response, sizeof(response), "NOTIFY:BOARD %s\n", board_str);
                        if (!send_to_client(client_fd, response)) { client_connected = false; }
                        if (!send_to_client(client_fd, "NOTIFY:YOUR_TURN\n")) { client_connected = false; }

                    } else {
                        LOG("ERROR: Accepted joiner %s (fd %d) not found or not in lobby state for game %d. Clearing pending request.\n", accepted_player_name, joiner_fd, my_game_id);
                        snprintf(response, sizeof(response), "ERROR:The player %s disconnected or is busy.\n", accepted_player_name);
                        if (!send_to_client(client_fd, response)) client_connected = false;
                        games[game_idx].pending_joiner_fd = -1;
                        games[game_idx].pending_joiner_name[0] = '\0';
                    }
                } else {
                    LOG("ERROR: No valid pending request found for name '%s' in game %d when creator %s tried to ACCEPT.\n", accepted_player_name, my_game_id, clients[client_index].name);
                    snprintf(response, sizeof(response), "ERROR:No pending request found for '%s' or request expired.\n", accepted_player_name);
                    if (!send_to_client(client_fd, response)) client_connected = false;
                }
            } else {
                LOG("ERROR: Client %s (fd %d) tried to ACCEPT for game %d but is not creator or game not waiting.\n", clients[client_index].name, client_fd, my_game_id);
                snprintf(response, sizeof(response), "ERROR:Cannot accept request for this game (not creator or game not ready).\n");
                if (!send_to_client(client_fd, response)) client_connected = false;
            }

            pthread_mutex_unlock(&client_list_mutex);
            pthread_mutex_unlock(&game_list_mutex);
        }
         else if (strncmp(buffer, "REJECT ", 7) == 0 && clients[client_index].state == CLIENT_STATE_WAITING) {
            char rejected_player_name[MAX_NAME_LEN];
            strncpy(rejected_player_name, buffer + 7, MAX_NAME_LEN - 1);
            rejected_player_name[MAX_NAME_LEN - 1] = '\0';

            int game_idx = -1;
            int joiner_fd = -1;
            int my_game_id = clients[client_index].game_id;

            pthread_mutex_lock(&game_list_mutex);
            pthread_mutex_lock(&client_list_mutex);

            game_idx = find_game_index(my_game_id);

            if (game_idx != -1 && games[game_idx].player1_fd == client_fd) {
                 if (games[game_idx].pending_joiner_fd != -1 && strcmp(games[game_idx].pending_joiner_name, rejected_player_name) == 0) {
                     joiner_fd = games[game_idx].pending_joiner_fd;

                     LOG("Creator %s (fd %d) REJECTED join request from %s (fd %d) for game %d\n",
                         clients[client_index].name, client_fd, rejected_player_name, joiner_fd, my_game_id);

                     snprintf(response, sizeof(response), "RESP:JOIN_REJECTED %d %s\n", my_game_id, clients[client_index].name);
                     send_to_client(joiner_fd, response);

                     games[game_idx].pending_joiner_fd = -1;
                     games[game_idx].pending_joiner_name[0] = '\0';


                     snprintf(response, sizeof(response), "RESP:REJECT_OK %s\n", rejected_player_name);
                     if (!send_to_client(client_fd, response)) client_connected = false;

                 } else {
                    LOG("ERROR: No valid pending request found for name '%s' in game %d when creator %s tried to REJECT.\n", rejected_player_name, my_game_id, clients[client_index].name);
                    snprintf(response, sizeof(response), "ERROR:No pending request found for '%s' or request expired.\n", rejected_player_name);
                    if (!send_to_client(client_fd, response)) client_connected = false;
                }
            } else {
                LOG("ERROR: Client %s (fd %d) tried to REJECT for game %d but is not creator or game not waiting.\n", clients[client_index].name, client_fd, my_game_id);
                snprintf(response, sizeof(response), "ERROR:Cannot reject request for this game (not creator or game not ready).\n");
                if (!send_to_client(client_fd, response)) client_connected = false;
            }

            pthread_mutex_unlock(&client_list_mutex);
            pthread_mutex_unlock(&game_list_mutex);
        }
        else if (strncmp(buffer, "MOVE ", 5) == 0 && clients[client_index].state == CLIENT_STATE_PLAYING) {
            int r, c;
            if (sscanf(buffer + 5, "%d %d", &r, &c) == 2) {
                int game_idx = -1;
                int opponent_fd = -1;
                Cell my_symbol = CELL_EMPTY;
                bool move_ok = false;
                bool game_ended = false;
                char end_reason[20] = "";

                pthread_mutex_lock(&game_list_mutex);
                game_idx = find_game_index(clients[client_index].game_id);

                if (game_idx != -1 && games[game_idx].state == GAME_STATE_IN_PROGRESS) {
                    if (games[game_idx].current_turn_fd == client_fd) {
                        if (r >= 0 && r < 3 && c >= 0 && c < 3 && games[game_idx].board[r][c] == CELL_EMPTY) {
                            my_symbol = (client_fd == games[game_idx].player1_fd) ? CELL_X : CELL_O;
                            games[game_idx].board[r][c] = my_symbol;
                            move_ok = true;
                            LOG("Player %s (fd %d) made move %d,%d in game %d\n", clients[client_index].name, client_fd, r, c, clients[client_index].game_id);

                            if (check_winner(games[game_idx].board, my_symbol)) {
                                game_ended = true;
                                strcpy(end_reason, "WIN");
                                games[game_idx].state = GAME_STATE_FINISHED;
                                LOG("Game %d finished. Winner: %s\n", clients[client_index].game_id, clients[client_index].name);
                            } else if (board_full(games[game_idx].board)) {
                                game_ended = true;
                                strcpy(end_reason, "DRAW");
                                games[game_idx].state = GAME_STATE_FINISHED;
                                LOG("Game %d finished. Draw.\n", clients[client_index].game_id);
                            }

                             opponent_fd = (client_fd == games[game_idx].player1_fd) ? games[game_idx].player2_fd : games[game_idx].player1_fd;
                            if (!game_ended) {
                                games[game_idx].current_turn_fd = opponent_fd;
                            } else {
                                games[game_idx].current_turn_fd = -1;
                            }

                        } else {
                             strcpy(response, "ERROR:Invalid move (occupied or out of bounds)\n");
                             if (!send_to_client(client_fd, response)) client_connected = false;
                        }
                    } else {
                         strcpy(response, "ERROR:Not your turn\n");
                         if (!send_to_client(client_fd, response)) client_connected = false;
                    }
                } else {
                     strcpy(response, "ERROR:Game not found or not in progress\n");
                     if (!send_to_client(client_fd, response)) client_connected = false;
                }

                if (move_ok) {
                    char board_str[BUFFER_SIZE];
                    board_to_string(games[game_idx].board, board_str, sizeof(board_str));

                    snprintf(response, sizeof(response), "NOTIFY:BOARD %s\n", board_str);
                    if (!send_to_client(client_fd, response)) client_connected = false;
                    if (opponent_fd != -1) send_to_client(opponent_fd, response);

                    if (game_ended) {
                         snprintf(response, sizeof(response), "NOTIFY:GAMEOVER %s\n", (strcmp(end_reason,"WIN")==0) ? "WIN" : "DRAW");
                         if (!send_to_client(client_fd, response)) client_connected = false;
                         snprintf(response, sizeof(response), "NOTIFY:GAMEOVER %s\n", (strcmp(end_reason,"WIN")==0) ? "LOSE" : "DRAW");
                          if (opponent_fd != -1) send_to_client(opponent_fd, response);

                        pthread_mutex_lock(&client_list_mutex);
                         clients[client_index].state = CLIENT_STATE_LOBBY;
                         clients[client_index].game_id = 0;
                         int opponent_idx = find_client_index(opponent_fd);
                         if (opponent_idx != -1) {
                              clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                              clients[opponent_idx].game_id = 0;
                         }
                        pthread_mutex_unlock(&client_list_mutex);

                         games[game_idx].state = GAME_STATE_EMPTY;
                         games[game_idx].player1_fd = -1;
                         games[game_idx].player2_fd = -1;
                         games[game_idx].current_turn_fd = -1;
                         games[game_idx].player1_name[0] = '\0';
                         games[game_idx].player2_name[0] = '\0';
                         games[game_idx].pending_joiner_fd = -1;
                         games[game_idx].pending_joiner_name[0] = '\0';

                    } else {
                         if (opponent_fd != -1) send_to_client(opponent_fd, "NOTIFY:YOUR_TURN\n");
                    }
                }
                pthread_mutex_unlock(&game_list_mutex);

            } else {
                 strcpy(response, "ERROR:Invalid move format. Use: MOVE <row> <col>\n");
                 if (!send_to_client(client_fd, response)) client_connected = false;
            }
        }
         else if (strcmp(buffer, "QUIT") == 0) {
             LOG("Client %s (fd %d) requested QUIT. Current state: %d\n", clients[client_index].name, client_fd, clients[client_index].state);

             ClientState currentState;
             int currentGameId;
             pthread_mutex_lock(&client_list_mutex);
             currentState = clients[client_index].state;
             currentGameId = clients[client_index].game_id;
             pthread_mutex_unlock(&client_list_mutex);


             if (currentState == CLIENT_STATE_PLAYING || currentState == CLIENT_STATE_WAITING) {
                 LOG("Client %s (fd %d) is leaving game %d and returning to lobby.\n", clients[client_index].name, client_fd, currentGameId);

                 pthread_mutex_lock(&client_list_mutex);
                 pthread_mutex_lock(&game_list_mutex);

                 int game_idx = find_game_index(currentGameId);
                 if (game_idx != -1 && (games[game_idx].state == GAME_STATE_WAITING || games[game_idx].state == GAME_STATE_IN_PROGRESS)) {
                      int opponent_fd = -1;
                      int pending_joiner_notify_fd = -1;

                      if (games[game_idx].player1_fd == client_fd) { // Creator is quitting
                          opponent_fd = games[game_idx].player2_fd; // Opponent in PLAYING state
                          pending_joiner_notify_fd = games[game_idx].pending_joiner_fd; // Player waiting in WAITING state
                      } else if (games[game_idx].player2_fd == client_fd) { // Player 2 is quitting
                          opponent_fd = games[game_idx].player1_fd;
                      }


                     if (opponent_fd != -1) {
                         send_to_client(opponent_fd, "NOTIFY:OPPONENT_LEFT\n");
                         int opponent_idx = find_client_index(opponent_fd);
                         if (opponent_idx != -1) {
                             clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                             clients[opponent_idx].game_id = 0;
                             LOG("Opponent %s (fd %d) moved back to LOBBY.\n", clients[opponent_idx].name, opponent_fd);
                         }
                     }
                     if (pending_joiner_notify_fd != -1) {
                         snprintf(response, sizeof(response), "ERROR:Game %d was cancelled by the creator.\n", games[game_idx].id);
                         send_to_client(pending_joiner_notify_fd, response);
                         LOG("Notified pending joiner %s (fd %d) that game %d was cancelled.\n", games[game_idx].pending_joiner_name, pending_joiner_notify_fd, games[game_idx].id);

                     }

                     games[game_idx].state = GAME_STATE_EMPTY;
                     games[game_idx].player1_fd = -1;
                     games[game_idx].player2_fd = -1;
                     games[game_idx].player1_name[0] = '\0';
                     games[game_idx].player2_name[0] = '\0';
                     games[game_idx].current_turn_fd = -1;
                     games[game_idx].pending_joiner_fd = -1;
                     games[game_idx].pending_joiner_name[0] = '\0';
                     LOG("Game %d (index %d) reset due to player leaving.\n", currentGameId, game_idx);
                 } else {
                      LOG("Client %s (fd %d) sent QUIT while in game %d, but game not found or finished.\n", clients[client_index].name, client_fd, currentGameId);
                 }


                 clients[client_index].state = CLIENT_STATE_LOBBY;
                 clients[client_index].game_id = 0;

                 pthread_mutex_unlock(&game_list_mutex);
                 pthread_mutex_unlock(&client_list_mutex);


             } else {
                 LOG("Client %s (fd %d) sent QUIT from lobby/connected state. Disconnecting session.\n", clients[client_index].name, client_fd);
                 client_connected = false; // Disconnect the client
             }
         }
        else {
            if (clients[client_index].state != CLIENT_STATE_CONNECTED) {
                 snprintf(response, sizeof(response), "ERROR:Unknown command or invalid state for command: %s\n", buffer);
                 if (!send_to_client(client_fd, response)) client_connected = false;
            }
        }
    }

    LOG("Cleaning up client fd %d (index %d, name '%s')\n", client_fd, client_index, clients[client_index].name);
    close(client_fd);

    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);

    int game_id = clients[client_index].game_id;
    int old_state = clients[client_index].state;

    clients[client_index].active = false;
    clients[client_index].fd = -1;
    clients[client_index].game_id = 0;
    clients[client_index].state = CLIENT_STATE_CONNECTED;
    clients[client_index].name[0] = '\0';

    if (game_id > 0) {
        int game_idx = find_game_index(game_id);
        if (game_idx != -1) {
             if (games[game_idx].pending_joiner_fd == client_fd) {
                 LOG("Pending joiner %s (fd %d) disconnected for game %d. Resetting pending status.\n",
                      games[game_idx].pending_joiner_name, client_fd, game_id);
                 games[game_idx].pending_joiner_fd = -1;
                 games[game_idx].pending_joiner_name[0] = '\0';
             }
             else if ((games[game_idx].state == GAME_STATE_WAITING || games[game_idx].state == GAME_STATE_IN_PROGRESS) &&
                       (games[game_idx].player1_fd == client_fd || games[game_idx].player2_fd == client_fd))
             {
                 LOG("Client disconnected while in game %d (index %d, state %d). Notifying opponent/pending and cleaning up game.\n",
                       game_id, game_idx, games[game_idx].state);
                 int opponent_fd = -1;
                 int pending_joiner_notify_fd = -1;

                 if (games[game_idx].player1_fd == client_fd) { // Creator disconnected
                     opponent_fd = games[game_idx].player2_fd;
                     pending_joiner_notify_fd = games[game_idx].pending_joiner_fd;
                 } else if (games[game_idx].player2_fd == client_fd) { // Player 2 disconnected
                     opponent_fd = games[game_idx].player1_fd;
                 }


                 if (opponent_fd != -1) {
                     send_to_client(opponent_fd, "NOTIFY:OPPONENT_LEFT\n");
                     int opponent_idx = find_client_index(opponent_fd);
                     if (opponent_idx != -1) {
                         clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                         clients[opponent_idx].game_id = 0;
                         LOG("Opponent %s (fd %d) moved back to LOBBY due to disconnect.\n", clients[opponent_idx].name, opponent_fd);
                     }
                 }
                 if (pending_joiner_notify_fd != -1) {
                     snprintf(response, sizeof(response), "ERROR:Game %d was cancelled because the creator disconnected.\n", games[game_idx].id);
                     send_to_client(pending_joiner_notify_fd, response);
                      LOG("Notified pending joiner %s (fd %d) game %d cancelled due to creator disconnect.\n",
                          games[game_idx].pending_joiner_name, pending_joiner_notify_fd, game_id);
                 }


                 games[game_idx].state = GAME_STATE_EMPTY;
                 games[game_idx].player1_fd = -1;
                 games[game_idx].player2_fd = -1;
                 games[game_idx].player1_name[0] = '\0';
                 games[game_idx].player2_name[0] = '\0';
                 games[game_idx].current_turn_fd = -1;
                 games[game_idx].pending_joiner_fd = -1;
                 games[game_idx].pending_joiner_name[0] = '\0';
                 LOG("Game %d (index %d) reset due to player disconnect.\n", game_id, game_idx);
            }
        }
    }

    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    LOG("Thread finished for client fd %d.\n", client_fd);
    return NULL;
}


void handle_signal(int signal) {
    keep_running = 0;
    char msg[] = "\n!!! Signal received, initiating server shutdown... !!!\n";
    write(STDOUT_FILENO, msg, strlen(msg));
     if (server_fd != -1) {
         shutdown(server_fd, SHUT_RDWR);
         close(server_fd);
         server_fd = -1;
     }
}


void cleanup() {
    LOG("Cleaning up resources...\n");

    pthread_mutex_lock(&client_list_mutex);
    for (int i = 0; i < MAX_TOTAL_CLIENTS; i++) {
        if (clients[i].active && clients[i].fd != -1) {
            send_to_client(clients[i].fd, "NOTIFY:SERVER_SHUTDOWN\n");
            close(clients[i].fd);
            clients[i].fd = -1;
             LOG("Closed connection for client %d (name: %s)\n", i , clients[i].name);
        }
        clients[i].active = false;
    }
     pthread_mutex_unlock(&client_list_mutex);

    if (server_fd != -1) {
        close(server_fd);
        server_fd = -1;
    }

     pthread_mutex_destroy(&client_list_mutex);
     pthread_mutex_destroy(&game_list_mutex);

    LOG("Server stopped.\n");
}


int main() {
    struct sockaddr_in address;
    int addrlen = sizeof(address);

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = handle_signal;
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGTERM, &action, NULL);
    signal(SIGPIPE, SIG_IGN);


    pthread_mutex_lock(&client_list_mutex);
    for(int i=0; i<MAX_TOTAL_CLIENTS; ++i) clients[i].active = false;
    pthread_mutex_unlock(&client_list_mutex);

    pthread_mutex_lock(&game_list_mutex);
    for(int i=0; i<MAX_GAMES; ++i) games[i].state = GAME_STATE_EMPTY;
    pthread_mutex_unlock(&game_list_mutex);


    if ((server_fd = socket(AF_INET, SOCK_STREAM, 0)) == 0) {
        LOG_PERROR("socket failed");
        exit(EXIT_FAILURE);
    }
    int opt = 1;
    if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt))) {
        LOG_PERROR("setsockopt SO_REUSEADDR failed");
    }
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(PORT);
    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address))<0) {
        LOG_PERROR("bind failed");
        close(server_fd);
        exit(EXIT_FAILURE);
    }
    if (listen(server_fd, MAX_TOTAL_CLIENTS) < 0) {
        LOG_PERROR("listen failed");
        close(server_fd);
        exit(EXIT_FAILURE);
    }

    LOG("Server listening on port %d... (Max Clients: %d, Max Games: %d)\n", PORT, MAX_TOTAL_CLIENTS, MAX_GAMES);

    while (keep_running) {
        int new_socket = accept(server_fd, (struct sockaddr *)&address, (socklen_t*)&addrlen);

        if (new_socket < 0) {
            if (!keep_running && (errno == EINTR || errno == EBADF)) {
                LOG("Accept interrupted by shutdown signal or closed socket.\n");
            } else if (errno != EINTR) {
                 LOG_PERROR("accept failed");
            }
             if (!keep_running) break;
            continue;
        }

        LOG("New connection accepted, assigning fd %d\n", new_socket);

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

            pthread_t thread_id;
            int *p_client_index = malloc(sizeof(int));
             if (p_client_index == NULL) {
                 LOG_PERROR("Failed to allocate memory for thread arg");
                 close(new_socket);
                 clients[client_index].active = false;
                 pthread_mutex_unlock(&client_list_mutex);
                 continue;
             }
            *p_client_index = client_index;

            if (pthread_create(&thread_id, NULL, handle_client, p_client_index) != 0) {
                LOG_PERROR("pthread_create failed");
                close(new_socket);
                clients[client_index].active = false;
                 free(p_client_index);
            } else {
                pthread_detach(thread_id);
                 LOG("Client assigned to index %d, handler thread created.\n", client_index);
            }
        } else {
            LOG("Server full (MAX_TOTAL_CLIENTS reached), rejecting connection fd %d\n", new_socket);
            char reject_msg[] = "ERROR:Server is full. Try again later.\n";
            send(new_socket, reject_msg, strlen(reject_msg), MSG_NOSIGNAL);
            close(new_socket);
        }
        pthread_mutex_unlock(&client_list_mutex);

    }

    cleanup();
    return 0;
}