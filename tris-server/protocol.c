#include "protocol.h"
#include "utils.h"
#include "game_logic.h" // Necessario per init_board, find_opponent_fd, broadcast_game_state, ecc.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// --- Definizioni Costanti Stringhe Protocollo ---

// Comandi Client -> Server
const char* CMD_NAME_PREFIX = "NAME ";
const char* CMD_LIST = "LIST";
const char* CMD_CREATE = "CREATE";
const char* CMD_JOIN_REQUEST_PREFIX = "JOIN_REQUEST ";
const char* CMD_ACCEPT_PREFIX = "ACCEPT ";
const char* CMD_REJECT_PREFIX = "REJECT ";
const char* CMD_MOVE_PREFIX = "MOVE ";
const char* CMD_QUIT = "QUIT";
const char* CMD_REMATCH_YES = "REMATCH YES";
const char* CMD_REMATCH_NO = "REMATCH NO";

// Comandi Server -> Client
const char* CMD_GET_NAME = "CMD:GET_NAME\n";
const char* CMD_REMATCH_OFFER = "CMD:REMATCH_OFFER\n";

// Risposte Server -> Client (RESP:)
const char* RESP_NAME_OK = "RESP:NAME_OK\n";
const char* RESP_CREATED_FMT = "RESP:CREATED %d\n";
const char* RESP_GAMES_LIST_PREFIX = "RESP:GAMES_LIST;";
const char* RESP_REQUEST_SENT_FMT = "RESP:REQUEST_SENT %d\n";
const char* RESP_JOIN_ACCEPTED_FMT = "RESP:JOIN_ACCEPTED %d %c %s\n"; // game_id, symbol, opponent_name
const char* RESP_REJECT_OK_FMT = "RESP:REJECT_OK %s\n";              // rejected_name
const char* RESP_JOIN_REJECTED_FMT = "RESP:JOIN_REJECTED %d %s\n";    // game_id, creator_name
const char* RESP_QUIT_OK = "RESP:QUIT_OK Back to lobby.\n";
const char* RESP_REMATCH_ACCEPTED_FMT = "RESP:REMATCH_ACCEPTED %d Waiting for new opponent.\n"; // Per Vincitore
const char* RESP_REMATCH_DECLINED = "RESP:REMATCH_DECLINED Back to lobby.\n";                   // Per chiunque dica NO

// Notifiche Server -> Client (NOTIFY:)
const char* NOTIFY_JOIN_REQUEST_FMT = "NOTIFY:JOIN_REQUEST %s\n";
const char* NOTIFY_GAME_START_FMT = "NOTIFY:GAME_START %d %c %s\n"; // game_id, symbol, opponent_name
const char* NOTIFY_REQUEST_CANCELLED_FMT = "NOTIFY:REQUEST_CANCELLED %s left\n"; // joiner_name
const char* NOTIFY_OPPONENT_ACCEPTED_REMATCH = "NOTIFY:OPPONENT_ACCEPTED_REMATCH Back to lobby.\n"; // Per perdente se vincitore rigioca come host
const char* NOTIFY_OPPONENT_DECLINED = "NOTIFY:OPPONENT_DECLINED Back to lobby.\n";              // Per perdente (o altro in pareggio) se l'avversario rifiuta
// Nota: NOTIFY_BOARD_PREFIX, NOTIFY_YOUR_TURN, etc. sono in game_logic.c

// Errori Server -> Client (ERROR:) - Definiti qui
const char* ERR_SERVER_FULL_GAMES = "ERROR:Server full, cannot create game (no game slots)\n";
const char* ERR_SERVER_FULL_SLOTS = "ERROR:Server is full. Try again later.\n";
const char* ERR_INVALID_MOVE_FORMAT = "ERROR:Invalid move format. Use: MOVE <row> <col>\n";
const char* ERR_INVALID_MOVE_BOUNDS = "ERROR:Invalid move (out of bounds 0-2)\n";
const char* ERR_INVALID_MOVE_OCCUPIED = "ERROR:Invalid move (cell occupied)\n";
const char* ERR_NOT_YOUR_TURN = "ERROR:Not your turn\n";
const char* ERR_GAME_NOT_FOUND = "ERROR:Game not found\n";
const char* ERR_GAME_NOT_IN_PROGRESS = "ERROR:Game not in progress\n";
const char* ERR_GAME_NOT_WAITING = "ERROR:Game is not waiting for players\n";
const char* ERR_GAME_ALREADY_STARTED = "ERROR:Game already started\n";
const char* ERR_GAME_FINISHED = "ERROR:Game has finished\n";
const char* ERR_CANNOT_JOIN_OWN_GAME = "ERROR:Cannot join your own game\n";
const char* ERR_ALREADY_PENDING = "ERROR:Game creator is busy with another join request\n";
const char* ERR_NO_PENDING_REQUEST = "ERROR:No pending join request found for that player\n";
const char* ERR_JOINER_LEFT = "ERROR:The player who requested to join is no longer available.\n";
const char* ERR_CREATOR_LEFT = "ERROR:Game creator seems disconnected.\n";
const char* ERR_NOT_IN_LOBBY = "ERROR:Command only available in LOBBY state\n";
const char* ERR_NOT_WAITING = "ERROR:Command only available in WAITING state\n";
const char* ERR_NOT_PLAYING = "ERROR:Command only available in PLAYING or finished game state\n";
const char* ERR_UNKNOWN_COMMAND_FMT = "ERROR:Unknown command or invalid state (%d) for command: %s\n";
const char* ERR_NOT_FINISHED_GAME = "ERROR:Command only available after game finished\n";
const char* ERR_NOT_THE_WINNER = "ERROR:Only the winner can decide rematch\n";
// Aggiunti anche:
const char* ERR_NOT_IN_FINISHED_OR_DRAW_GAME = "ERROR:Rematch command invalid in current game state\n";
const char* ERR_INVALID_REMATCH_CHOICE = "ERROR:Invalid rematch choice command\n";
const char* ERR_DRAW_REMATCH_ONLY_PLAYER = "ERROR:Cannot rematch after draw if not a player in the game\n";
const char* ERR_GENERIC = "ERROR:An internal server error occurred.\n"; // Generico

// --- Implementazioni Funzioni Processamento Comandi ---

void process_name_command(int client_idx, const char* name_arg) {
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !name_arg) return;
    pthread_mutex_lock(&client_list_mutex);
    if (clients[client_idx].active && clients[client_idx].state == CLIENT_STATE_CONNECTED) {
        strncpy(clients[client_idx].name, name_arg, MAX_NAME_LEN - 1);
        clients[client_idx].name[MAX_NAME_LEN - 1] = '\0';
        clients[client_idx].state = CLIENT_STATE_LOBBY;
        int fd = clients[client_idx].fd;
        LOG("Client fd %d (idx %d) registered name: %s\n", fd, client_idx, clients[client_idx].name);
        pthread_mutex_unlock(&client_list_mutex);
        send_to_client(fd, RESP_NAME_OK);
    } else {
        int fd = clients[client_idx].active ? clients[client_idx].fd : -1;
        ClientState state = clients[client_idx].active ? clients[client_idx].state : CLIENT_STATE_CONNECTED; // Default state if inactive
        pthread_mutex_unlock(&client_list_mutex);
        LOG("Client fd %d (idx %d) sent NAME in wrong state (%d) or inactive.\n", fd, client_idx, state);
    }
}

void process_list_command(int client_idx) {
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS) return;
     char response[BUFFER_SIZE * 2];
     response[0] = '\0';
     strcat(response, RESP_GAMES_LIST_PREFIX);
     bool first_game = true;
     int client_fd = -1;
     pthread_mutex_lock(&client_list_mutex);
     if(!clients[client_idx].active) { pthread_mutex_unlock(&client_list_mutex); return; }
     client_fd = clients[client_idx].fd;
     pthread_mutex_unlock(&client_list_mutex);

     pthread_mutex_lock(&game_list_mutex);
     for (int i = 0; i < MAX_GAMES; ++i) {
         if (games[i].state != GAME_STATE_EMPTY) {
             if (!first_game) strncat(response, "|", sizeof(response) - strlen(response) - 1);
             char state_str[20];
             switch (games[i].state) {
                 case GAME_STATE_WAITING:    strcpy(state_str, "Waiting"); break;
                 case GAME_STATE_IN_PROGRESS:strcpy(state_str, "In Progress"); break;
                 case GAME_STATE_FINISHED:   strcpy(state_str, "Finished"); break;
                 default:                    strcpy(state_str, "Unknown"); break;
             }
             char game_info[150];
             snprintf(game_info, sizeof(game_info), "%d,%s,%s", games[i].id, games[i].player1_name[0] ? games[i].player1_name : "?", state_str);
             if ((games[i].state == GAME_STATE_IN_PROGRESS || games[i].state == GAME_STATE_FINISHED) && games[i].player2_name[0]) {
                 strncat(game_info, ",", sizeof(game_info) - strlen(game_info) - 1);
                 strncat(game_info, games[i].player2_name, sizeof(game_info) - strlen(game_info) - 1);
             }
             strncat(response, game_info, sizeof(response) - strlen(response) - 1);
             first_game = false;
         }
     }
     pthread_mutex_unlock(&game_list_mutex);
     strncat(response, "\n", sizeof(response) - strlen(response) - 1);
     send_to_client(client_fd, response);
}

void process_create_command(int client_idx) {
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS) return;
    char response[BUFFER_SIZE];
    int game_idx = -1;
    int created_game_id = -1;
    int client_fd = -1;
    // bool success = false; // --- VARIABILE RIMOSSA ---
    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);
    if (!clients[client_idx].active || clients[client_idx].state != CLIENT_STATE_LOBBY) {
        client_fd = clients[client_idx].active ? clients[client_idx].fd : -1;
        ClientState state = clients[client_idx].active ? clients[client_idx].state : CLIENT_STATE_LOBBY; // Read state safely
        LOG("Client fd %d (idx %d) sent CREATE in wrong state (%d).\n", client_fd, client_idx, state);
        snprintf(response, sizeof(response), "%s\n", ERR_NOT_IN_LOBBY); // Include ERR prefix
        goto create_cleanup;
    }
    client_fd = clients[client_idx].fd;
    for (int i = 0; i < MAX_GAMES; ++i) {
        if (games[i].state == GAME_STATE_EMPTY) { game_idx = i; break; }
    }
    if (game_idx != -1) {
        LOG("Using empty game slot %d for new game.\n", game_idx);
        created_game_id = next_game_id++;
        games[game_idx].id = created_game_id;
        games[game_idx].state = GAME_STATE_WAITING;
        init_board(games[game_idx].board); // Da game_logic.h
        games[game_idx].player1_fd = client_fd;
        games[game_idx].player2_fd = -1;
        games[game_idx].current_turn_fd = -1;
        games[game_idx].winner_fd = -1;
        games[game_idx].player1_accepted_rematch = REMATCH_CHOICE_PENDING; // Init con Enum
        games[game_idx].player2_accepted_rematch = REMATCH_CHOICE_PENDING; // Init con Enum
        strncpy(games[game_idx].player1_name, clients[client_idx].name, MAX_NAME_LEN); games[game_idx].player1_name[MAX_NAME_LEN-1] = '\0';
        games[game_idx].player2_name[0] = '\0';
        games[game_idx].pending_joiner_fd = -1; games[game_idx].pending_joiner_name[0] = '\0';
        clients[client_idx].state = CLIENT_STATE_WAITING;
        clients[client_idx].game_id = created_game_id;
        snprintf(response, sizeof(response), RESP_CREATED_FMT, created_game_id);
        // success = true; // --- ASSEGNAZIONE RIMOSSA ---
        LOG("Game %d created by %s (fd %d) in slot %d.\n", created_game_id, games[game_idx].player1_name, client_fd, game_idx);
    } else {
        snprintf(response, sizeof(response), "%s\n", ERR_SERVER_FULL_GAMES);
         LOG("Failed to create game for %s (fd %d): No empty game slots.\n", clients[client_idx].name, client_fd);
    }
create_cleanup:
    pthread_mutex_unlock(&game_list_mutex); pthread_mutex_unlock(&client_list_mutex);
    if (client_fd >= 0) { send_to_client(client_fd, response); }
}

void process_join_request_command(int client_idx, const char* game_id_str) {
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !game_id_str) return;
    int game_id_to_join = atoi(game_id_str);
    int game_idx = -1; int creator_fd = -1; int requester_fd = -1;
    char requester_name[MAX_NAME_LEN] = {0};
    char response_requester[BUFFER_SIZE]; char notify_creator[BUFFER_SIZE];
    bool proceed = false;
    pthread_mutex_lock(&client_list_mutex); pthread_mutex_lock(&game_list_mutex);
    if (!clients[client_idx].active || clients[client_idx].state != CLIENT_STATE_LOBBY) {
        requester_fd = clients[client_idx].active ? clients[client_idx].fd : -1;
        snprintf(response_requester, sizeof(response_requester), "%s\n", ERR_NOT_IN_LOBBY);
        goto join_req_cleanup;
    }
    requester_fd = clients[client_idx].fd; strncpy(requester_name, clients[client_idx].name, MAX_NAME_LEN -1); requester_name[MAX_NAME_LEN-1] = '\0';
    game_idx = find_game_index_unsafe(game_id_to_join);
    if (game_idx == -1) { snprintf(response_requester, sizeof(response_requester), "%s %d\n", ERR_GAME_NOT_FOUND, game_id_to_join); goto join_req_cleanup; }
    GameInfo* game = &games[game_idx]; creator_fd = game->player1_fd;
    if (game->state != GAME_STATE_WAITING) { snprintf(response_requester, sizeof(response_requester), "%s\n", ERR_GAME_NOT_WAITING); goto join_req_cleanup; }
    if (creator_fd == requester_fd) { snprintf(response_requester, sizeof(response_requester), "%s\n", ERR_CANNOT_JOIN_OWN_GAME); goto join_req_cleanup; }
    if (game->pending_joiner_fd != -1) { snprintf(response_requester, sizeof(response_requester), "%s\n", ERR_ALREADY_PENDING); goto join_req_cleanup; }
    if (creator_fd < 0) { snprintf(response_requester, sizeof(response_requester), "%s\n", ERR_CREATOR_LEFT); goto join_req_cleanup; }
    game->pending_joiner_fd = requester_fd; strncpy(game->pending_joiner_name, requester_name, MAX_NAME_LEN -1); game->pending_joiner_name[MAX_NAME_LEN - 1] = '\0';
    snprintf(notify_creator, sizeof(notify_creator), NOTIFY_JOIN_REQUEST_FMT, requester_name);
    snprintf(response_requester, sizeof(response_requester), RESP_REQUEST_SENT_FMT, game_id_to_join);
    LOG("Client %s requested join game %d. Notifying creator %s\n", requester_name, game_id_to_join, game->player1_name);
    proceed = true;
join_req_cleanup:
    pthread_mutex_unlock(&game_list_mutex); pthread_mutex_unlock(&client_list_mutex);
    if (proceed) {
        if (!send_to_client(creator_fd, notify_creator)) {
            LOG("Failed send join req to creator fd %d. Cancelling.\n", creator_fd);
            pthread_mutex_lock(&game_list_mutex);
            if (game_idx != -1 && find_game_index_unsafe(game_id_to_join) == game_idx && games[game_idx].pending_joiner_fd == requester_fd) { // Recheck validity
                games[game_idx].pending_joiner_fd = -1; games[game_idx].pending_joiner_name[0] = '\0';
            }
            pthread_mutex_unlock(&game_list_mutex);
            snprintf(response_requester, sizeof(response_requester), "%s\n", ERR_CREATOR_LEFT);
            send_to_client(requester_fd, response_requester);
        } else { send_to_client(requester_fd, response_requester); }
    } else if (requester_fd >= 0) { send_to_client(requester_fd, response_requester); }
}

void process_accept_command(int client_idx, const char* accepted_player_name) {
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !accepted_player_name) return;
     int creator_idx = client_idx; int creator_fd = -1; int joiner_idx = -1; int joiner_fd = -1;
     int game_idx = -1; int current_game_id = -1;
     char response_creator[BUFFER_SIZE]={0}; char response_joiner[BUFFER_SIZE]={0};
     char notify_creator_start[BUFFER_SIZE]={0}; char notify_joiner_start[BUFFER_SIZE]={0};
     bool start_game = false;
     pthread_mutex_lock(&client_list_mutex); pthread_mutex_lock(&game_list_mutex);
     if (!clients[creator_idx].active || clients[creator_idx].state != CLIENT_STATE_WAITING) {
         snprintf(response_creator, sizeof(response_creator), "%s\n", ERR_NOT_WAITING); goto accept_cleanup;
     }
     creator_fd = clients[creator_idx].fd; current_game_id = clients[creator_idx].game_id;
     game_idx = find_game_index_unsafe(current_game_id);
     if (game_idx == -1) { snprintf(response_creator, sizeof(response_creator), "%s %d\n", ERR_GAME_NOT_FOUND, current_game_id); goto accept_cleanup; }
     GameInfo* game = &games[game_idx];
     if (game->state != GAME_STATE_WAITING) { snprintf(response_creator, sizeof(response_creator), "%s\n", ERR_GAME_NOT_WAITING); goto accept_cleanup; }
     if (game->player1_fd != creator_fd) { snprintf(response_creator, sizeof(response_creator), "ERROR:Not creator\n"); goto accept_cleanup; }
     if (game->pending_joiner_fd < 0 || strcmp(game->pending_joiner_name, accepted_player_name) != 0) {
         snprintf(response_creator, sizeof(response_creator), "%s '%s'\n", ERR_NO_PENDING_REQUEST, accepted_player_name); goto accept_cleanup;
     }
     joiner_fd = game->pending_joiner_fd;
     joiner_idx = find_client_index_unsafe(joiner_fd);
     if (joiner_idx == -1 || !clients[joiner_idx].active) {
         snprintf(response_creator, sizeof(response_creator), "%s\n", ERR_JOINER_LEFT);
         game->pending_joiner_fd = -1; game->pending_joiner_name[0] = '\0';
         goto accept_cleanup;
     }
     LOG("Creator %s ACCEPTED join from %s for game %d\n", clients[creator_idx].name, accepted_player_name, current_game_id);
     game->player2_fd = joiner_fd; strncpy(game->player2_name, accepted_player_name, MAX_NAME_LEN -1); game->player2_name[MAX_NAME_LEN - 1] = '\0';
     game->state = GAME_STATE_IN_PROGRESS; game->current_turn_fd = creator_fd;
     game->pending_joiner_fd = -1; game->pending_joiner_name[0] = '\0';
     clients[creator_idx].state = CLIENT_STATE_PLAYING;
     clients[joiner_idx].state = CLIENT_STATE_PLAYING; clients[joiner_idx].game_id = current_game_id;
     snprintf(response_joiner, sizeof(response_joiner), RESP_JOIN_ACCEPTED_FMT, current_game_id, 'O', clients[creator_idx].name);
     snprintf(notify_joiner_start, sizeof(notify_joiner_start), NOTIFY_GAME_START_FMT, current_game_id, 'O', clients[creator_idx].name);
     snprintf(notify_creator_start, sizeof(notify_creator_start), NOTIFY_GAME_START_FMT, current_game_id, 'X', accepted_player_name);
     start_game = true;
accept_cleanup:
     pthread_mutex_unlock(&game_list_mutex); pthread_mutex_unlock(&client_list_mutex);
     if (start_game) {
         send_to_client(joiner_fd, response_joiner); send_to_client(joiner_fd, notify_joiner_start);
         send_to_client(creator_fd, notify_creator_start);
         pthread_mutex_lock(&game_list_mutex);
         if(game_idx != -1) broadcast_game_state(game_idx); // Check index validity again
         pthread_mutex_unlock(&game_list_mutex);
     } else if (creator_fd >= 0) { send_to_client(creator_fd, response_creator); }
}

void process_reject_command(int client_idx, const char* rejected_player_name) {
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !rejected_player_name) return;
     int creator_idx = client_idx; int creator_fd = -1; int joiner_fd = -1;
     int game_idx = -1; int current_game_id = -1;
     char response_creator[BUFFER_SIZE]={0}; char response_joiner[BUFFER_SIZE]={0};
     bool rejected = false;
     pthread_mutex_lock(&client_list_mutex); pthread_mutex_lock(&game_list_mutex);
     if (!clients[creator_idx].active || clients[creator_idx].state != CLIENT_STATE_WAITING) {
        snprintf(response_creator, sizeof(response_creator), "%s\n", ERR_NOT_WAITING); goto reject_cleanup;
     }
     creator_fd = clients[creator_idx].fd; current_game_id = clients[creator_idx].game_id;
     game_idx = find_game_index_unsafe(current_game_id);
     if (game_idx == -1) { snprintf(response_creator, sizeof(response_creator), "%s %d\n", ERR_GAME_NOT_FOUND, current_game_id); goto reject_cleanup; }
     GameInfo* game = &games[game_idx];
     if (game->state != GAME_STATE_WAITING) { snprintf(response_creator, sizeof(response_creator), "%s\n", ERR_GAME_NOT_WAITING); goto reject_cleanup; }
     if (game->player1_fd != creator_fd) { snprintf(response_creator, sizeof(response_creator), "ERROR:Not creator\n"); goto reject_cleanup; }
     if (game->pending_joiner_fd < 0 || strcmp(game->pending_joiner_name, rejected_player_name) != 0) {
        snprintf(response_creator, sizeof(response_creator), "%s '%s'\n", ERR_NO_PENDING_REQUEST, rejected_player_name); goto reject_cleanup;
     }
     joiner_fd = game->pending_joiner_fd;
     LOG("Creator %s REJECTED join from %s for game %d\n", clients[creator_idx].name, rejected_player_name, current_game_id);
     game->pending_joiner_fd = -1; game->pending_joiner_name[0] = '\0';
     snprintf(response_joiner, sizeof(response_joiner), RESP_JOIN_REJECTED_FMT, current_game_id, clients[creator_idx].name);
     snprintf(response_creator, sizeof(response_creator), RESP_REJECT_OK_FMT, rejected_player_name);
     rejected = true;
reject_cleanup:
     pthread_mutex_unlock(&game_list_mutex); pthread_mutex_unlock(&client_list_mutex);
     if (rejected) {
         send_to_client(joiner_fd, response_joiner); send_to_client(creator_fd, response_creator);
     } else if (creator_fd >= 0) { send_to_client(creator_fd, response_creator); }
}

void process_move_command(int client_idx, const char* move_args) {
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !move_args) return;
    int r, c;
    int player_fd = -1;
    int game_idx = -1; int current_game_id = -1;
    char response[BUFFER_SIZE] = {0};
    bool move_made = false; bool game_over = false;
    char game_over_status_self[20] = {0}; char game_over_status_opponent[20] = {0};
    int winner_fd_if_game_over = -1;
    int opponent_fd_if_game_over = -1;
    int opponent_idx_if_game_over = -1; // **NUOVO: Indice dell'avversario**
    char client_name[MAX_NAME_LEN] = {0};

    if (sscanf(move_args, "%d %d", &r, &c) != 2) {
        pthread_mutex_lock(&client_list_mutex);
        if(clients[client_idx].active) player_fd = clients[client_idx].fd;
        pthread_mutex_unlock(&client_list_mutex);
        if(player_fd >= 0) snprintf(response, sizeof(response), "%s\n", ERR_INVALID_MOVE_FORMAT);
        goto move_error_exit;
    }

    pthread_mutex_lock(&client_list_mutex); pthread_mutex_lock(&game_list_mutex);

    // Controlli sullo stato del client e della partita (come prima)
    if (!clients[client_idx].active) { goto move_cleanup; }
     player_fd = clients[client_idx].fd;
     strncpy(client_name, clients[client_idx].name, MAX_NAME_LEN-1);
     client_name[MAX_NAME_LEN - 1] = '\0';

    if (clients[client_idx].state != CLIENT_STATE_PLAYING) {
        snprintf(response, sizeof(response), "%s\n", ERR_NOT_PLAYING);
        goto move_cleanup;
    }
    current_game_id = clients[client_idx].game_id;
    if(current_game_id <= 0) {
        snprintf(response, sizeof(response), "%s\n", ERR_NOT_PLAYING);
        goto move_cleanup;
    }
    game_idx = find_game_index_unsafe(current_game_id);
    if (game_idx == -1) {
        snprintf(response, sizeof(response), "%s %d\n", ERR_GAME_NOT_FOUND, current_game_id);
        // Mettere il client in LOBBY se il suo game_id punta a un gioco inesistente
        clients[client_idx].state = CLIENT_STATE_LOBBY; clients[client_idx].game_id = 0;
        goto move_cleanup;
     }

    GameInfo* game = &games[game_idx];
    if (game->state != GAME_STATE_IN_PROGRESS) {
        snprintf(response, sizeof(response), "%s\n", ERR_GAME_NOT_IN_PROGRESS);
        goto move_cleanup;
     }
    if (game->current_turn_fd != player_fd) {
        snprintf(response, sizeof(response), "%s\n", ERR_NOT_YOUR_TURN);
        goto move_cleanup;
     }
    if (r < 0 || r > 2 || c < 0 || c > 2) {
        snprintf(response, sizeof(response), "%s\n", ERR_INVALID_MOVE_BOUNDS);
        goto move_cleanup;
     }
    if (game->board[r][c] != CELL_EMPTY) {
        snprintf(response, sizeof(response), "%s\n", ERR_INVALID_MOVE_OCCUPIED);
        goto move_cleanup;
    }

    // --- Mossa valida, aggiorna stato ---
    Cell player_symbol = (player_fd == game->player1_fd) ? CELL_X : CELL_O;
    game->board[r][c] = player_symbol;
    LOG("Player '%s' (fd %d, %c) moved to %d,%d in game %d.\n", client_name, player_fd, (player_symbol==CELL_X ? 'X':'O'), r, c, current_game_id);
    move_made = true;
    opponent_fd_if_game_over = find_opponent_fd(game, player_fd);
    opponent_idx_if_game_over = find_client_index_unsafe(opponent_fd_if_game_over); // **NUOVO: Trova indice avversario**

    // Controlla vittoria / pareggio
    if (check_winner(game->board, player_symbol)) {
        game_over = true; game->state = GAME_STATE_FINISHED; game->current_turn_fd = -1;
        game->winner_fd = player_fd; winner_fd_if_game_over = player_fd;
        strcpy(game_over_status_self, "WIN"); strcpy(game_over_status_opponent, "LOSE");
        LOG("Game %d over. Winner: '%s' (fd %d).\n", current_game_id, client_name, player_fd);

        // **MODIFICA**: Se l'avversario (perdente) è ancora valido, mettilo in LOBBY sul server
        if (opponent_idx_if_game_over != -1 && clients[opponent_idx_if_game_over].active) {
             LOG("Setting loser '%s' (fd %d, idx %d) state to LOBBY on server.\n",
                  clients[opponent_idx_if_game_over].name, opponent_fd_if_game_over, opponent_idx_if_game_over);
             clients[opponent_idx_if_game_over].state = CLIENT_STATE_LOBBY;
             clients[opponent_idx_if_game_over].game_id = 0;
        }

    } else if (board_full(game->board)) {
        game_over = true; game->state = GAME_STATE_FINISHED; game->current_turn_fd = -1;
        game->winner_fd = -1; winner_fd_if_game_over = -1; // Pareggio
        strcpy(game_over_status_self, "DRAW"); strcpy(game_over_status_opponent, "DRAW");
        LOG("Game %d over. Result: DRAW.\n", current_game_id);
    } else {
        game->current_turn_fd = opponent_fd_if_game_over;
        // LOG("Game %d: Turn passed to opponent (fd %d).\n", current_game_id, opponent_fd_if_game_over); // Meno log verboso
    }

move_cleanup:
    // Rilascia i lock PRIMA di inviare i messaggi
    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    // Invia risposte e notifiche fuori lock
    if (move_made) {
        // Blocco per inviare stato board
        pthread_mutex_lock(&game_list_mutex);
        if(find_game_index_unsafe(current_game_id) == game_idx) {
             broadcast_game_state(game_idx);
        }
        pthread_mutex_unlock(&game_list_mutex);

        if (game_over) {
            char notify_self[BUFFER_SIZE], notify_opponent[BUFFER_SIZE];
            snprintf(notify_self, sizeof(notify_self), "NOTIFY:GAMEOVER %s\n", game_over_status_self);
            snprintf(notify_opponent, sizeof(notify_opponent), "NOTIFY:GAMEOVER %s\n", game_over_status_opponent);

            send_to_client(player_fd, notify_self);
            if (opponent_fd_if_game_over >= 0) send_to_client(opponent_fd_if_game_over, notify_opponent);

            // Invia offerta rematch / manda perdente in lobby
            if (winner_fd_if_game_over != -1) { // C'è un vincitore (W/L case)
                // Invia offerta SOLO al vincitore
                LOG("Sending rematch offer to winner '%s' (fd %d) for game %d\n", client_name, winner_fd_if_game_over, current_game_id);
                send_to_client(winner_fd_if_game_over, CMD_REMATCH_OFFER);

                // **MODIFICA**: Invia comando per tornare in lobby SUBITO al perdente
                if (opponent_fd_if_game_over >= 0) {
                     LOG("Sending loser (fd %d) direct 'back to lobby' command (RESP:REMATCH_DECLINED) for game %d\n", opponent_fd_if_game_over, current_game_id);
                     send_to_client(opponent_fd_if_game_over, RESP_REMATCH_DECLINED); // Questo dirà al client di tornare in lobby
                }

            } else { // Pareggio
                LOG("Sending rematch offer to both players (fd %d, fd %d) for DRAW game %d\n", player_fd, opponent_fd_if_game_over, current_game_id);
                send_to_client(player_fd, CMD_REMATCH_OFFER);
                if(opponent_fd_if_game_over >= 0) send_to_client(opponent_fd_if_game_over, CMD_REMATCH_OFFER);
            }
        }
    } else { // Errore di mossa, invia messaggio di errore solo al client che ha inviato la mossa
        move_error_exit:
        if (player_fd >= 0 && response[0] != '\0') {
             send_to_client(player_fd, response);
         }
    }
}



void process_rematch_command(int client_idx, const char* choice) {
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !choice) return;

    int caller_fd = -1; int current_game_id = 0; int game_idx = -1;
    int p1_fd = -1, p2_fd = -1; int p1_idx = -1, p2_idx = -1;
    int opponent_fd = -1, opponent_idx = -1;
    char caller_name[MAX_NAME_LEN];
    char response_caller[BUFFER_SIZE] = {0};
    char notify_opponent_on_fail[BUFFER_SIZE] = {0}; // Usato SOLO se P2 sceglie YES ma P1 aveva già detto NO o viceversa
    char error_response[BUFFER_SIZE] = {0};
    char notify_p1_start[BUFFER_SIZE] = {0}; char notify_p2_start[BUFFER_SIZE] = {0};
    bool send_direct_response_to_caller = false;
    bool send_fail_response_to_caller_due_to_opponent_no = false;
    bool restart_draw_game = false;
    bool reset_game_on_exit = false; // Flag per indicare se resettare lo slot

    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);

    // Blocco iniziale per ottenere informazioni sul client e sulla partita (come prima)
    if (!clients[client_idx].active) { goto rematch_cleanup_nolock; }
    caller_fd = clients[client_idx].fd; current_game_id = clients[client_idx].game_id;
    strncpy(caller_name, clients[client_idx].name, MAX_NAME_LEN); caller_name[MAX_NAME_LEN - 1] = '\0';
    if (current_game_id <= 0) { snprintf(error_response, sizeof(error_response), "%s\n", ERR_GENERIC); goto rematch_cleanup; }
    game_idx = find_game_index_unsafe(current_game_id);
    if (game_idx == -1) { snprintf(error_response, sizeof(error_response), "%s %d\n", ERR_GAME_NOT_FOUND, current_game_id); goto rematch_cleanup; }
    GameInfo* game = &games[game_idx];
    // Assicurati che lo stato del client sia PLAYING e quello della partita FINISHED
    if (clients[client_idx].state != CLIENT_STATE_PLAYING || game->state != GAME_STATE_FINISHED) {
        snprintf(error_response, sizeof(error_response), "%s\n", ERR_NOT_IN_FINISHED_OR_DRAW_GAME); // Errore più specifico
         LOG("Rematch command from '%s' (fd %d) for game %d ignored: Client state %d, Game state %d.\n",
             caller_name, caller_fd, current_game_id, clients[client_idx].state, game->state);
        goto rematch_cleanup;
    }

    p1_fd = game->player1_fd; p2_fd = game->player2_fd;
    p1_idx = find_client_index_unsafe(p1_fd); p2_idx = find_client_index_unsafe(p2_fd);
    bool is_caller_p1 = (caller_fd == p1_fd);
    bool is_caller_p2 = (caller_fd == p2_fd);
    bool is_draw = (game->winner_fd == -1);
    bool is_caller_winner = (!is_draw && game->winner_fd == caller_fd);

    // Controlli validità (come prima)
    if (!is_caller_p1 && !is_caller_p2) { // Dovrebbe essere impossibile se fd è corretto, ma sicurezza
        snprintf(error_response, sizeof(error_response), "%s\n", ERR_DRAW_REMATCH_ONLY_PLAYER); // Errore più specifico
        goto rematch_cleanup;
    }
    // L'offerta arriva solo a vincitore e in caso di pareggio
    if (!is_draw && !is_caller_winner) { // Chiamante è il perdente, non dovrebbe ricevere l'offerta
        snprintf(error_response, sizeof(error_response), "%s\n", ERR_NOT_THE_WINNER); // Perdente non decide
         LOG("Rematch command from loser '%s' (fd %d) ignored for game %d.\n", caller_name, caller_fd, current_game_id);
        clients[client_idx].state = CLIENT_STATE_LOBBY; // Manda comunque il perdente in lobby se prova a rispondere
        clients[client_idx].game_id = 0;
        goto rematch_cleanup;
    }

    // Trova l'avversario (come prima)
    opponent_fd = find_opponent_fd(game, caller_fd);
    opponent_idx = find_client_index_unsafe(opponent_fd); // Può essere -1 se disconnesso

    // Inizializza/Controlla flag rematch su prima risposta a DRAW
    // Se è la prima risposta al pareggio in assoluto, entrambi i flag dovrebbero essere PENDING
    // Questa logica serve per evitare che stati vecchi interferiscano
     if (is_draw) {
         if (game->player1_accepted_rematch == REMATCH_CHOICE_PENDING && game->player2_accepted_rematch == REMATCH_CHOICE_PENDING) {
             LOG("First rematch choice for DRAW game %d received from %s\n", game->id, caller_name);
         }
     }


    // *** LOGICA DECISIONE MODIFICATA ***
    if (strcmp(choice, CMD_REMATCH_YES) == 0) {
        if (is_draw) {
            LOG("Rematch DRAW YES from %s (fd %d) for game %d\n", caller_name, caller_fd, game->id);
            RematchChoice opponent_choice;
            if (is_caller_p1) {
                game->player1_accepted_rematch = REMATCH_CHOICE_YES;
                opponent_choice = game->player2_accepted_rematch;
            } else { // is_caller_p2
                game->player2_accepted_rematch = REMATCH_CHOICE_YES;
                opponent_choice = game->player1_accepted_rematch;
            }

            if (opponent_choice == REMATCH_CHOICE_YES) {
                // Entrambi YES -> Riavvia partita Draw
                LOG("Rematch DRAW ACCEPTED by both in game %d!\n", game->id);
                game->state = GAME_STATE_IN_PROGRESS; init_board(game->board);
                // Determina chi inizia (potrebbe alternare o essere sempre P1)
                // Per semplicità, facciamo iniziare sempre P1 (chi ha creato originariamente)
                game->current_turn_fd = game->player1_fd >= 0 ? game->player1_fd : game->player2_fd; // Fallback a P2 se P1 si fosse disconnesso nel frattempo (improbabile)
                game->winner_fd = -1;
                game->player1_accepted_rematch = REMATCH_CHOICE_PENDING; // Reset per prossimo game over
                game->player2_accepted_rematch = REMATCH_CHOICE_PENDING; // Reset per prossimo game over
                if(p1_idx != -1 && clients[p1_idx].active) clients[p1_idx].state = CLIENT_STATE_PLAYING; // Rimangono in PLAYING
                if(p2_idx != -1 && clients[p2_idx].active) clients[p2_idx].state = CLIENT_STATE_PLAYING; // Rimangono in PLAYING
                // Prepara notifiche START
                snprintf(notify_p1_start, sizeof(notify_p1_start), NOTIFY_GAME_START_FMT, game->id, 'X', (game->player2_name[0] ? game->player2_name : "?"));
                snprintf(notify_p2_start, sizeof(notify_p2_start), NOTIFY_GAME_START_FMT, game->id, 'O', (game->player1_name[0] ? game->player1_name : "?"));
                restart_draw_game = true; // Invierà notifiche START dopo unlock
                // Nessuna risposta diretta a chi ha inviato l'ultimo YES, riceverà GAME_START
            } else if (opponent_choice == REMATCH_CHOICE_NO) {
                // Tu (caller) dici YES, ma l'avversario ha GIA' detto NO
                LOG("Rematch DRAW failed for game %d: %s said YES, but opponent already declined.\n", game->id, caller_name);
                clients[client_idx].state = CLIENT_STATE_LOBBY; clients[client_idx].game_id = 0;
                snprintf(response_caller, sizeof(response_caller), "%s", RESP_REMATCH_DECLINED); // Tu ricevi Declined perché l'altro ha detto NO
                // L'avversario è già stato messo in lobby (presumibilmente) quando ha detto NO
                send_fail_response_to_caller_due_to_opponent_no = true; // Manda RESP_REMATCH_DECLINED al chiamante dopo unlock
                reset_game_on_exit = true; // La partita finisce, resetta lo slot
            } else { // opponent_choice == REMATCH_CHOICE_PENDING
                // Tu (caller) dici YES, avversario non ha ancora scelto. Aspetta.
                LOG("Rematch DRAW: %s said YES for game %d. Waiting for opponent's choice.\n", caller_name, game->id);
                // Nessuna risposta immediata inviata al chiamante che ha detto YES. Il client deve attendere.
            }

        } else { // Vincitore dice YES
            LOG("Rematch WINNER YES from %s (fd %d) for game %d\n", caller_name, caller_fd, game->id);
            // Riconfigura la partita esistente per aspettare un nuovo avversario
            game->state = GAME_STATE_WAITING;
            // Il vincitore rimane P1
            game->player1_fd = caller_fd;
            // Rimuovi il vecchio P2
            game->player2_fd = -1; game->player2_name[0] = '\0';
            init_board(game->board);
            game->current_turn_fd = -1; game->winner_fd = -1;
            game->player1_accepted_rematch = REMATCH_CHOICE_PENDING; // Reset flags
            game->player2_accepted_rematch = REMATCH_CHOICE_PENDING; // Reset flags

            clients[client_idx].state = CLIENT_STATE_WAITING; // Vincitore ora è in attesa

            if(opponent_idx != -1 && clients[opponent_idx].active && clients[opponent_idx].fd == opponent_fd) { // Controlla se avversario (perdente) è ancora attivo e valido
                clients[opponent_idx].state = CLIENT_STATE_LOBBY; clients[opponent_idx].game_id = 0;
                // Invia notifica al perdente CHE VA IN LOBBY perché il vincitore rigiocherà
                snprintf(notify_opponent_on_fail, sizeof(notify_opponent_on_fail), "%s", NOTIFY_OPPONENT_ACCEPTED_REMATCH);
                LOG("Moving loser '%s' (fd %d) to LOBBY as winner accepted rematch for game %d.\n", clients[opponent_idx].name, opponent_fd, game->id);
            } else {
                LOG("Loser (opponent_fd %d) already inactive or invalid for game %d when winner accepted rematch.\n", opponent_fd, game->id);
            }
            // Conferma al vincitore che ora è host
            snprintf(response_caller, sizeof(response_caller), RESP_REMATCH_ACCEPTED_FMT, game->id);
            send_direct_response_to_caller = true; // Manda RESP_REMATCH_ACCEPTED e NOTIFY_OPPONENT_ACCEPTED_REMATCH dopo unlock
            // Non resettare lo slot qui, il gioco continua in stato WAITING
        }

    } else if (strcmp(choice, CMD_REMATCH_NO) == 0) {
        LOG("Rematch NO from %s (fd %d) for game %d\n", caller_name, caller_fd, game->id);

        // Chiunque dica NO va subito in Lobby e riceve RESP_REMATCH_DECLINED
        clients[client_idx].state = CLIENT_STATE_LOBBY;
        clients[client_idx].game_id = 0;
        snprintf(response_caller, sizeof(response_caller), "%s", RESP_REMATCH_DECLINED);
        send_direct_response_to_caller = true; // Flag per inviare questa risposta al chiamante

        if (is_draw) {
            LOG("Handling DRAW NO from %s for game %d\n", caller_name, game->id);
            RematchChoice opponent_choice;
            if (is_caller_p1) {
                game->player1_accepted_rematch = REMATCH_CHOICE_NO;
                 opponent_choice = game->player2_accepted_rematch; // *** CORRETTO TYPO QUI ***
            } else { // is_caller_p2
                game->player2_accepted_rematch = REMATCH_CHOICE_NO;
                opponent_choice = game->player1_accepted_rematch;
            }

            // Controlla se l'avversario aveva già deciso
            if (opponent_choice == REMATCH_CHOICE_PENDING) {
                 // Avversario non ha ancora scelto.
                 LOG("Game %d: %s said NO (draw). Opponent PENDING. Opponent will be notified upon their choice.\n", game->id, caller_name);
                 // Non mandare nulla all'avversario ora. Non resettare ancora lo slot.
            } else { // Opponent has already chosen (YES or NO, doesn't matter because caller said NO)
                 // La partita finisce definitivamente. Metti l'avversario in LOBBY e notificalo.
                 LOG("Game %d: %s said NO (draw). Opponent had already chosen (%d). Notifying opponent and resetting slot.\n", game->id, caller_name, opponent_choice);
                 if (opponent_idx != -1 && clients[opponent_idx].active && clients[opponent_idx].fd == opponent_fd) {
                     clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                     clients[opponent_idx].game_id = 0;
                     snprintf(notify_opponent_on_fail, sizeof(notify_opponent_on_fail), "%s", NOTIFY_OPPONENT_DECLINED); // Ora è sicuro notificarlo
                     LOG("Moving opponent '%s' (fd %d) to LOBBY (draw-rematch declined by %s).\n", clients[opponent_idx].name, opponent_fd, caller_name);
                 }
                 reset_game_on_exit = true; // Reset slot, la partita è finita
            }

        } else { // Chiamante era il VINCITORE e dice NO
            LOG("Handling WINNER NO from %s for game %d\n", caller_name, game->id);
            // Metti il perdente (opponent) in Lobby e notificalo
             if(opponent_idx != -1 && clients[opponent_idx].active && clients[opponent_idx].fd == opponent_fd){
                clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                clients[opponent_idx].game_id = 0;
                snprintf(notify_opponent_on_fail, sizeof(notify_opponent_on_fail), "%s", NOTIFY_OPPONENT_DECLINED); // Notifica il PERDENTE
                LOG("Moving loser '%s' (fd %d) to LOBBY as winner declined rematch for game %d.\n", clients[opponent_idx].name, opponent_fd, game->id);
            } else {
                 LOG("Loser (opponent_fd %d) already inactive or invalid when winner declined rematch for game %d.\n", opponent_fd, game->id);
            }
            reset_game_on_exit = true; // La partita finisce qui, resetta lo slot
        }

    } else { // Comando non valido
        snprintf(error_response, sizeof(error_response), "%s\n", ERR_INVALID_REMATCH_CHOICE);
    }

rematch_cleanup:
     // Se dobbiamo resettare lo slot, facciamolo ora, dentro il lock della game list
     if (reset_game_on_exit && game_idx != -1) {
          LOG("Resetting game slot %d due to rematch outcome (NO decision or YES vs NO).\n", game_idx);
         reset_game_slot_to_empty_unsafe(game_idx);
     }
    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    // --- Invio messaggi FUORI DAI LOCK ---
    if (restart_draw_game) {
        // Notifica inizio nuova partita a entrambi
        if(p1_fd >= 0 && notify_p1_start[0]) send_to_client(p1_fd, notify_p1_start);
        if(p2_fd >= 0 && notify_p2_start[0]) send_to_client(p2_fd, notify_p2_start);
        // Invia board iniziale
        if(game_idx != -1) { // game_idx dovrebbe essere ancora valido (non è stato resettato)
             pthread_mutex_lock(&game_list_mutex); // Lock di nuovo solo per broadcast
             // Assicurati che lo stato sia ancora corretto prima di inviare
             // (Anche se dovrebbe esserlo, check di sicurezza)
             if(find_game_index_unsafe(current_game_id) == game_idx && games[game_idx].state == GAME_STATE_IN_PROGRESS){
                 broadcast_game_state(game_idx);
             } else {
                  LOG("WARN: Game %d state changed before broadcasting state after draw rematch start.\n", current_game_id);
             }
             pthread_mutex_unlock(&game_list_mutex);
        }
    } else if (send_fail_response_to_caller_due_to_opponent_no) {
        // Usato quando caller dice YES a draw, ma opponent aveva detto NO
        if (caller_fd >= 0 && response_caller[0]) send_to_client(caller_fd, response_caller);
    } else if (send_direct_response_to_caller) {
         // Usato per tutte le altre risposte dirette (YES del vincitore, qualsiasi NO, ecc.)
         // Invia la risposta diretta al chiamante
         if (caller_fd >= 0 && response_caller[0]) send_to_client(caller_fd, response_caller);
         // Invia la notifica all'avversario, SE C'E' (impostata solo se necessario)
         if (opponent_fd >= 0 && notify_opponent_on_fail[0]) send_to_client(opponent_fd, notify_opponent_on_fail);
    } else if (error_response[0]) { // Se c'è stato un errore di validazione iniziale
        if (caller_fd >= 0) send_to_client(caller_fd, error_response);
    }
    // Nessun invio esplicito qui se Caller(Draw) dice YES e Opponent(Draw) è PENDING

rematch_cleanup_nolock:
    return; // Uscita senza mutex se client era inattivo all'inizio
}


bool process_quit_command(int client_idx) {
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS) return false;
     int client_fd = -1; char client_name[MAX_NAME_LEN]; ClientState current_state; int current_game_id;
     bool request_disconnect = false;
     pthread_mutex_lock(&client_list_mutex);
     if (!clients[client_idx].active) { pthread_mutex_unlock(&client_list_mutex); return true; }
     client_fd = clients[client_idx].fd; strncpy(client_name, clients[client_idx].name, MAX_NAME_LEN-1); client_name[MAX_NAME_LEN - 1] = '\0';
     current_state = clients[client_idx].state; current_game_id = clients[client_idx].game_id;
     pthread_mutex_unlock(&client_list_mutex);

     LOG("Client %s sent QUIT. State: %d, Game ID: %d\n", client_name, current_state, current_game_id);

     // Se PLAYING (anche post-partita) o WAITING, gestisci uscita da contesto gioco
     if (current_state == CLIENT_STATE_PLAYING || current_state == CLIENT_STATE_WAITING) {
         LOG("Client %s leaving game %d context via QUIT.\n", client_name, current_game_id);
         pthread_mutex_lock(&client_list_mutex); pthread_mutex_lock(&game_list_mutex);
         int game_idx = find_game_index_unsafe(current_game_id);
         if (game_idx != -1) {
             handle_player_leaving_game(game_idx, client_fd, client_name);
         } else {
             LOG("QUIT: Game %d not found for client %s.\n", current_game_id, client_name);
         }
         // Indipendentemente da handle_player_leaving_game, metti chi ha inviato QUIT in lobby
         if (clients[client_idx].active) { // Ricontrolla
             clients[client_idx].state = CLIENT_STATE_LOBBY; clients[client_idx].game_id = 0;
             LOG("Client %s moved to LOBBY after QUIT.\n", client_name);
         }
         pthread_mutex_unlock(&game_list_mutex); pthread_mutex_unlock(&client_list_mutex);
         send_to_client(client_fd, RESP_QUIT_OK); // Conferma LOBBY
         request_disconnect = false; // Non disconnettere il client
     } else { // Altrimenti (LOBBY, CONNECTED) QUIT significa disconnessione
         LOG("Client %s sent QUIT from state %d. Interpreting as disconnect.\n", client_name, current_state);
         request_disconnect = true;
     }
     return request_disconnect;
}

void send_unknown_command_error(int client_idx, const char* received_command, ClientState current_state) {
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS) return;
     int client_fd = -1;
     pthread_mutex_lock(&client_list_mutex);
     if (clients[client_idx].active) client_fd = clients[client_idx].fd;
     pthread_mutex_unlock(&client_list_mutex);
     if (client_fd >= 0) {
         char error_resp[BUFFER_SIZE];
         snprintf(error_resp, sizeof(error_resp), ERR_UNKNOWN_COMMAND_FMT,
                  current_state, received_command ? received_command : "<empty>");
         strncat(error_resp, "\n", sizeof(error_resp) - strlen(error_resp) -1); // Assicura newline
         send_to_client(client_fd, error_resp);
     }
}