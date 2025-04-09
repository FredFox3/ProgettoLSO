#include "protocol.h"
#include "utils.h"
#include "game_logic.h" // Necessario per manipolare giochi e usare helpers
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
const char* RESP_JOIN_ACCEPTED_FMT = "RESP:JOIN_ACCEPTED %d %c %s\n";
const char* RESP_REJECT_OK_FMT = "RESP:REJECT_OK %s\n";
const char* RESP_JOIN_REJECTED_FMT = "RESP:JOIN_REJECTED %d %s\n";
const char* RESP_QUIT_OK = "RESP:QUIT_OK Back to lobby.\n";
const char* RESP_REMATCH_ACCEPTED_FMT = "RESP:REMATCH_ACCEPTED %d Waiting for new opponent.\n";
const char* RESP_REMATCH_DECLINED = "RESP:REMATCH_DECLINED Back to lobby.\n";

// Notifiche Server -> Client (NOTIFY:)
const char* NOTIFY_JOIN_REQUEST_FMT = "NOTIFY:JOIN_REQUEST %s\n";
const char* NOTIFY_GAME_START_FMT = "NOTIFY:GAME_START %d %c %s\n";
const char* NOTIFY_REQUEST_CANCELLED_FMT = "NOTIFY:REQUEST_CANCELLED %s left\n";
const char* NOTIFY_OPPONENT_ACCEPTED_REMATCH = "NOTIFY:OPPONENT_ACCEPTED_REMATCH Back to lobby.\n";
const char* NOTIFY_OPPONENT_DECLINED = "NOTIFY:OPPONENT_DECLINED Back to lobby.\n";
// Nota: NOTIFY_BOARD_PREFIX, NOTIFY_YOUR_TURN, NOTIFY_GAMEOVER_*, NOTIFY_OPPONENT_LEFT
//       sono definite in game_logic.c dove sono primariamente usate.

// Errori Server -> Client (ERROR:)
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
    // ... (Implementazione come prima) ...
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS) return;
     char response[BUFFER_SIZE * 2];
     response[0] = '\0';
     strcat(response, RESP_GAMES_LIST_PREFIX);
     bool first_game = true;
     int client_fd = -1;
     pthread_mutex_lock(&client_list_mutex);
     if(!clients[client_idx].active) {
         pthread_mutex_unlock(&client_list_mutex);
         return;
     }
     client_fd = clients[client_idx].fd;
     pthread_mutex_unlock(&client_list_mutex);

     pthread_mutex_lock(&game_list_mutex);
     for (int i = 0; i < MAX_GAMES; ++i) {
         if (games[i].state != GAME_STATE_EMPTY) {
             if (!first_game) {
                 strncat(response, "|", sizeof(response) - strlen(response) - 1);
             }
             char state_str[20];
             switch (games[i].state) {
                 case GAME_STATE_WAITING:    strcpy(state_str, "Waiting"); break;
                 case GAME_STATE_IN_PROGRESS:strcpy(state_str, "In Progress"); break;
                 case GAME_STATE_FINISHED:   strcpy(state_str, "Finished"); break;
                 default:                    strcpy(state_str, "Unknown"); break;
             }
             char game_info[150];
             snprintf(game_info, sizeof(game_info), "%d,%s,%s",
                      games[i].id,
                      games[i].player1_name[0] ? games[i].player1_name : "?",
                      state_str);
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
    // ... (Implementazione come prima, assicurando init winner_fd = -1) ...
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS) return;
     char response[BUFFER_SIZE];
     int game_idx = -1;
     int created_game_id = -1;
     int client_fd = -1;
     bool success = false;
     pthread_mutex_lock(&client_list_mutex);
     pthread_mutex_lock(&game_list_mutex);
     if (!clients[client_idx].active || clients[client_idx].state != CLIENT_STATE_LOBBY) {
         client_fd = clients[client_idx].active ? clients[client_idx].fd : -1;
         ClientState state = clients[client_idx].active ? clients[client_idx].state : CLIENT_STATE_CONNECTED;
         snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_NOT_IN_LOBBY);
         LOG("Client %s (fd %d, idx %d) tried CREATE in wrong state (%d).\n",
             clients[client_idx].name, client_fd, client_idx, state);
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
         init_board(games[game_idx].board);
         games[game_idx].player1_fd = client_fd;
         games[game_idx].player2_fd = -1;
         games[game_idx].current_turn_fd = -1;
         games[game_idx].winner_fd = -1; // Importante!
         strncpy(games[game_idx].player1_name, clients[client_idx].name, MAX_NAME_LEN);
         games[game_idx].player1_name[MAX_NAME_LEN - 1] = '\0';
         games[game_idx].player2_name[0] = '\0';
         games[game_idx].pending_joiner_fd = -1;
         games[game_idx].pending_joiner_name[0] = '\0';
         clients[client_idx].state = CLIENT_STATE_WAITING;
         clients[client_idx].game_id = created_game_id;
         snprintf(response, sizeof(response), RESP_CREATED_FMT, created_game_id);
         LOG("Client %s (fd %d) created game %d in slot index %d\n",
             clients[client_idx].name, client_fd, created_game_id, game_idx);
         success = true;
     } else {
         snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_SERVER_FULL_GAMES);
         LOG("Cannot create game for %s (fd %d), server full\n", clients[client_idx].name, client_fd);
     }
create_cleanup:
     pthread_mutex_unlock(&game_list_mutex);
     pthread_mutex_unlock(&client_list_mutex);
     if (client_fd >= 0) { send_to_client(client_fd, response); }
}

void process_join_request_command(int client_idx, const char* game_id_str) {
    // ... (Implementazione come prima) ...
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !game_id_str) return;
     int game_id_to_join = atoi(game_id_str);
     int game_idx = -1;
     int creator_fd = -1;
     int requester_fd = -1;
     char requester_name[MAX_NAME_LEN] = {0};
     char response_requester[BUFFER_SIZE];
     char notify_creator[BUFFER_SIZE];
     bool proceed = false;
     pthread_mutex_lock(&client_list_mutex);
     pthread_mutex_lock(&game_list_mutex);
     if (!clients[client_idx].active || clients[client_idx].state != CLIENT_STATE_LOBBY) {
         requester_fd = clients[client_idx].active ? clients[client_idx].fd : -1;
         snprintf(response_requester, sizeof(response_requester), "%s%s\n", RESP_ERROR_PREFIX, ERR_NOT_IN_LOBBY);
         goto join_req_cleanup;
     }
     requester_fd = clients[client_idx].fd;
     strncpy(requester_name, clients[client_idx].name, MAX_NAME_LEN -1);
     game_idx = find_game_index_unsafe(game_id_to_join);
     if (game_idx == -1) {
         snprintf(response_requester, sizeof(response_requester), "%s%s %d\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_FOUND, game_id_to_join);
         goto join_req_cleanup;
     }
     GameInfo* game = &games[game_idx];
     creator_fd = game->player1_fd;
     if (game->state != GAME_STATE_WAITING) {
         snprintf(response_requester, sizeof(response_requester), "%s%s\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_WAITING);
         goto join_req_cleanup;
     }
     if (creator_fd == requester_fd) {
         snprintf(response_requester, sizeof(response_requester), "%s%s\n", RESP_ERROR_PREFIX, ERR_CANNOT_JOIN_OWN_GAME);
         goto join_req_cleanup;
     }
     if (game->pending_joiner_fd != -1) {
         snprintf(response_requester, sizeof(response_requester), "%s%s\n", RESP_ERROR_PREFIX, ERR_ALREADY_PENDING);
         goto join_req_cleanup;
     }
      if (creator_fd < 0) {
          snprintf(response_requester, sizeof(response_requester), "%s%s\n", RESP_ERROR_PREFIX, ERR_CREATOR_LEFT);
          goto join_req_cleanup;
      }
     game->pending_joiner_fd = requester_fd;
     strncpy(game->pending_joiner_name, requester_name, MAX_NAME_LEN -1);
     game->pending_joiner_name[MAX_NAME_LEN - 1] = '\0';
     snprintf(notify_creator, sizeof(notify_creator), NOTIFY_JOIN_REQUEST_FMT, requester_name);
     snprintf(response_requester, sizeof(response_requester), RESP_REQUEST_SENT_FMT, game_id_to_join);
     LOG("Client %s requested join game %d. Notifying creator %s\n", requester_name, game_id_to_join, game->player1_name);
     proceed = true;
join_req_cleanup:
     pthread_mutex_unlock(&game_list_mutex);
     pthread_mutex_unlock(&client_list_mutex);
     if (proceed) {
         if (!send_to_client(creator_fd, notify_creator)) {
             LOG("Failed send join req to creator fd %d. Cancelling.\n", creator_fd);
             pthread_mutex_lock(&game_list_mutex);
             if (game_idx != -1 && games[game_idx].pending_joiner_fd == requester_fd) { // Check index is still valid
                games[game_idx].pending_joiner_fd = -1; games[game_idx].pending_joiner_name[0] = '\0';
             }
             pthread_mutex_unlock(&game_list_mutex);
             snprintf(response_requester, sizeof(response_requester), "%s%s\n", RESP_ERROR_PREFIX, ERR_CREATOR_LEFT);
             send_to_client(requester_fd, response_requester);
         } else {
             send_to_client(requester_fd, response_requester);
         }
     } else if (requester_fd >= 0) {
         send_to_client(requester_fd, response_requester);
     }
}

void process_accept_command(int client_idx, const char* accepted_player_name) {
    // ... (Implementazione come prima) ...
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !accepted_player_name) return;
     int creator_idx = client_idx; int creator_fd = -1; int joiner_idx = -1; int joiner_fd = -1;
     int game_idx = -1; int current_game_id = -1;
     char response_creator[BUFFER_SIZE]; char response_joiner[BUFFER_SIZE];
     char notify_creator_start[BUFFER_SIZE]; char notify_joiner_start[BUFFER_SIZE];
     bool start_game = false;
     pthread_mutex_lock(&client_list_mutex); pthread_mutex_lock(&game_list_mutex);
     if (!clients[creator_idx].active || clients[creator_idx].state != CLIENT_STATE_WAITING) {
         creator_fd = clients[creator_idx].active ? clients[creator_idx].fd : -1;
         snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, ERR_NOT_WAITING);
         goto accept_cleanup;
     }
     creator_fd = clients[creator_idx].fd; current_game_id = clients[creator_idx].game_id;
     game_idx = find_game_index_unsafe(current_game_id);
     if (game_idx == -1) {
         snprintf(response_creator, sizeof(response_creator), "%s%s %d\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_FOUND, current_game_id);
         goto accept_cleanup;
     }
     GameInfo* game = &games[game_idx];
     if (game->state != GAME_STATE_WAITING) {
         snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_WAITING);
         goto accept_cleanup;
     }
     if (game->player1_fd != creator_fd) {
        snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, "You are not the creator of this game.");
        goto accept_cleanup;
     }
     if (game->pending_joiner_fd < 0 || strcmp(game->pending_joiner_name, accepted_player_name) != 0) {
         snprintf(response_creator, sizeof(response_creator), "%s%s '%s'.\n", RESP_ERROR_PREFIX, ERR_NO_PENDING_REQUEST, accepted_player_name);
         goto accept_cleanup;
     }
     joiner_fd = game->pending_joiner_fd;
     joiner_idx = find_client_index_unsafe(joiner_fd);
     if (joiner_idx == -1 || !clients[joiner_idx].active) {
         snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, ERR_JOINER_LEFT);
         game->pending_joiner_fd = -1; game->pending_joiner_name[0] = '\0';
         goto accept_cleanup;
     }
     LOG("Creator %s ACCEPTED join from %s for game %d\n", clients[creator_idx].name, accepted_player_name, current_game_id);
     game->player2_fd = joiner_fd;
     strncpy(game->player2_name, accepted_player_name, MAX_NAME_LEN -1); game->player2_name[MAX_NAME_LEN - 1] = '\0';
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
         broadcast_game_state(game_idx);
         pthread_mutex_unlock(&game_list_mutex);
     } else if (creator_fd >= 0) {
         send_to_client(creator_fd, response_creator);
     }
}

void process_reject_command(int client_idx, const char* rejected_player_name) {
    // ... (Implementazione come prima) ...
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !rejected_player_name) return;
     int creator_idx = client_idx; int creator_fd = -1; int joiner_fd = -1;
     int game_idx = -1; int current_game_id = -1;
     char response_creator[BUFFER_SIZE]; char response_joiner[BUFFER_SIZE];
     bool rejected = false;
     pthread_mutex_lock(&client_list_mutex); pthread_mutex_lock(&game_list_mutex);
     if (!clients[creator_idx].active || clients[creator_idx].state != CLIENT_STATE_WAITING) {
         creator_fd = clients[creator_idx].active ? clients[creator_idx].fd : -1;
         snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, ERR_NOT_WAITING);
         goto reject_cleanup;
     }
     creator_fd = clients[creator_idx].fd; current_game_id = clients[creator_idx].game_id;
     game_idx = find_game_index_unsafe(current_game_id);
     if (game_idx == -1) {
         snprintf(response_creator, sizeof(response_creator), "%s%s %d\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_FOUND, current_game_id);
         goto reject_cleanup;
     }
     GameInfo* game = &games[game_idx];
     if (game->state != GAME_STATE_WAITING) {
         snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_WAITING);
         goto reject_cleanup;
     }
      if (game->player1_fd != creator_fd) {
         snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, "Not your game");
         goto reject_cleanup;
      }
      if (game->pending_joiner_fd < 0 || strcmp(game->pending_joiner_name, rejected_player_name) != 0) {
          snprintf(response_creator, sizeof(response_creator), "%s%s '%s'.\n", RESP_ERROR_PREFIX, ERR_NO_PENDING_REQUEST, rejected_player_name);
          goto reject_cleanup;
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
     } else if (creator_fd >= 0) {
         send_to_client(creator_fd, response_creator);
     }
}

void process_move_command(int client_idx, const char* move_args) {
    // ... (Implementazione come aggiornata in precedenza) ...
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !move_args) return;
     int r, c;
     int player_fd = -1; int opponent_fd = -1; int opponent_idx = -1;
     int game_idx = -1; int current_game_id = -1;
     char response[BUFFER_SIZE]; bool move_made = false; bool game_over = false;
     char game_over_status_self[20] = {0}; char game_over_status_opponent[20] = {0};
     int winner_fd_if_game_over = -1;
     if (sscanf(move_args, "%d %d", &r, &c) != 2) {
         pthread_mutex_lock(&client_list_mutex); player_fd = clients[client_idx].active ? clients[client_idx].fd : -1; pthread_mutex_unlock(&client_list_mutex);
         if (player_fd >= 0) { snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_INVALID_MOVE_FORMAT); send_to_client(player_fd, response); }
         return;
     }
     pthread_mutex_lock(&client_list_mutex); pthread_mutex_lock(&game_list_mutex);
     if (!clients[client_idx].active || clients[client_idx].state != CLIENT_STATE_PLAYING) {
         player_fd = clients[client_idx].active ? clients[client_idx].fd : -1;
         ClientState state = clients[client_idx].active ? clients[client_idx].state : CLIENT_STATE_CONNECTED;
         snprintf(response, sizeof(response), "%s%s (current state: %d)\n", RESP_ERROR_PREFIX, ERR_NOT_PLAYING, state);
         goto move_cleanup;
     }
     player_fd = clients[client_idx].fd; current_game_id = clients[client_idx].game_id;
     game_idx = find_game_index_unsafe(current_game_id);
     if (game_idx == -1) {
         snprintf(response, sizeof(response), "%s%s %d\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_FOUND, current_game_id);
         goto move_cleanup;
     }
     GameInfo* game = &games[game_idx];
     if (game->state != GAME_STATE_IN_PROGRESS) {
         snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_IN_PROGRESS);
         goto move_cleanup;
     }
     if (game->current_turn_fd != player_fd) {
         snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_NOT_YOUR_TURN);
         goto move_cleanup;
     }
     if (r < 0 || r > 2 || c < 0 || c > 2) {
         snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_INVALID_MOVE_BOUNDS);
         goto move_cleanup;
     }
     if (game->board[r][c] != CELL_EMPTY) {
         snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_INVALID_MOVE_OCCUPIED);
         goto move_cleanup;
     }
     Cell player_symbol = (player_fd == game->player1_fd) ? CELL_X : CELL_O;
     game->board[r][c] = player_symbol;
     LOG("Player %s (fd %d, %c) made move %d,%d in game %d\n", clients[client_idx].name, player_fd, (player_symbol == CELL_X ? 'X' : 'O'), r, c, current_game_id);
     move_made = true;
     opponent_fd = find_opponent_fd(game, player_fd); opponent_idx = find_client_index_unsafe(opponent_fd);
     if (check_winner(game->board, player_symbol)) {
         game_over = true; game->state = GAME_STATE_FINISHED; game->current_turn_fd = -1;
         game->winner_fd = player_fd; winner_fd_if_game_over = player_fd;
         strcpy(game_over_status_self, "WIN"); strcpy(game_over_status_opponent, "LOSE");
         LOG("Game %d finished. Winner: %s (fd %d).\n", current_game_id, clients[client_idx].name, player_fd);
     } else if (board_full(game->board)) {
         game_over = true; game->state = GAME_STATE_FINISHED; game->current_turn_fd = -1;
         game->winner_fd = -1; winner_fd_if_game_over = -1;
         strcpy(game_over_status_self, "DRAW"); strcpy(game_over_status_opponent, "DRAW");
         LOG("Game %d finished. Draw.\n", current_game_id);
     } else {
         game->current_turn_fd = opponent_fd;
     }
move_cleanup:
     pthread_mutex_unlock(&game_list_mutex); pthread_mutex_unlock(&client_list_mutex);
     if (move_made) {
         pthread_mutex_lock(&game_list_mutex); broadcast_game_state(game_idx); pthread_mutex_unlock(&game_list_mutex);
         if (game_over) {
             char notify_self[BUFFER_SIZE], notify_opponent[BUFFER_SIZE];
             snprintf(notify_self, sizeof(notify_self), "NOTIFY:GAMEOVER %s\n", game_over_status_self);
             snprintf(notify_opponent, sizeof(notify_opponent), "NOTIFY:GAMEOVER %s\n", game_over_status_opponent);
             send_to_client(player_fd, notify_self);
             if (opponent_fd >= 0) send_to_client(opponent_fd, notify_opponent);
             if (winner_fd_if_game_over != -1) {
                 LOG("Sending rematch offer to winner (fd %d)\n", winner_fd_if_game_over);
                 send_to_client(winner_fd_if_game_over, CMD_REMATCH_OFFER);
             } else {
                 LOG("Game %d ended in a draw, no rematch offer.\n", current_game_id);
             }
         }
     } else if (player_fd >= 0) {
         send_to_client(player_fd, response);
     }
}


// **** DEFINIZIONE DELLA FUNZIONE process_rematch_command ****
void process_rematch_command(int client_idx, const char* choice) {
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !choice) return;

    int winner_fd = -1;
    int current_game_id = 0;
    int game_idx = -1;
    int loser_fd = -1;
    int loser_idx = -1;
    char response_winner[BUFFER_SIZE]; // Risposta per il vincitore
    char notify_loser[BUFFER_SIZE];    // Notifica per il perdente
    char error_response[BUFFER_SIZE];  // Per eventuali errori al vincitore
    bool send_responses = false;       // Flag per inviare messaggi fuori dal lock

    response_winner[0] = '\0';
    notify_loser[0] = '\0';
    error_response[0] = '\0';

    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);

    // 1. Verifica stato e recupera informazioni del client (vincitore)
    if (!clients[client_idx].active) {
        LOG("REMATCH error: Client index %d is inactive.\n", client_idx);
        goto rematch_cleanup;
    }
    winner_fd = clients[client_idx].fd;
    current_game_id = clients[client_idx].game_id;
    char winner_name[MAX_NAME_LEN];
    strncpy(winner_name, clients[client_idx].name, MAX_NAME_LEN);
    winner_name[MAX_NAME_LEN - 1] = '\0';

    if (current_game_id <= 0) {
        LOG("REMATCH error: Client %d (%s) not associated with a game.\n", winner_fd, winner_name);
        // Utilizzo di RESP_ERROR_PREFIX qui non ideale perché definito in game_logic.c
        // Usiamo una stringa letterale o dichiariamo extern RESP_ERROR_PREFIX
        snprintf(error_response, sizeof(error_response), "ERROR:%s\n", "You are not in a game state to rematch.");
        goto rematch_cleanup;
    }

    // 2. Trova la partita
    game_idx = find_game_index_unsafe(current_game_id);
    if (game_idx == -1) {
        LOG("REMATCH error: Game ID %d (from client %d) not found.\n", current_game_id, winner_fd);
        snprintf(error_response, sizeof(error_response), "%s%d\n", ERR_GAME_NOT_FOUND, current_game_id); //ERR_GAME_NOT_FOUND include già "ERROR:"
        goto rematch_cleanup;
    }

    GameInfo* game = &games[game_idx];

    // 3. Verifica stato partita e se il client è il vincitore
    if (game->state != GAME_STATE_FINISHED) {
        LOG("REMATCH error: Game %d is not finished (state %d).\n", game->id, game->state);
        snprintf(error_response, sizeof(error_response), "%s\n", ERR_NOT_FINISHED_GAME);
        goto rematch_cleanup;
    }
     if (game->winner_fd == -1) { // Controllo per pareggio
         LOG("REMATCH error: Game %d was a draw, cannot rematch.\n", game->id);
         snprintf(error_response, sizeof(error_response), "ERROR:%s\n", "Game was a draw, cannot rematch.");
         // Metti entrambi in lobby se c'è un errore su pareggio
         clients[client_idx].state = CLIENT_STATE_LOBBY; clients[client_idx].game_id = 0;
         loser_fd = find_opponent_fd(game, winner_fd);
         loser_idx = find_client_index_unsafe(loser_fd);
         if(loser_idx != -1 && clients[loser_idx].active){
            clients[loser_idx].state = CLIENT_STATE_LOBBY; clients[loser_idx].game_id = 0;
         }
         goto rematch_cleanup;
     }
    if (game->winner_fd != winner_fd) {
        LOG("REMATCH error: Client %d (%s) is not the winner (winner fd %d).\n", winner_fd, winner_name, game->winner_fd);
        snprintf(error_response, sizeof(error_response), "%s\n", ERR_NOT_THE_WINNER); //ERR_NOT_THE_WINNER include "ERROR:"
        clients[client_idx].state = CLIENT_STATE_LOBBY; // Mandalo in lobby
        clients[client_idx].game_id = 0;
        goto rematch_cleanup;
    }

    // 4. Trova il perdente
    loser_fd = find_opponent_fd(game, winner_fd);
    loser_idx = find_client_index_unsafe(loser_fd);
    char loser_name[MAX_NAME_LEN] = "Opponent";
    if(loser_idx != -1 && clients[loser_idx].active) {
        strncpy(loser_name, clients[loser_idx].name, MAX_NAME_LEN-1); loser_name[MAX_NAME_LEN-1] = '\0';
    } else {
        loser_fd = -1; // Se non attivo o non trovato, marca fd come invalido per l'invio
        LOG("Rematch: Loser (original fd %d) for game %d not found or inactive.\n", find_opponent_fd(game, winner_fd), game->id);
    }

    // 5. Gestisci scelta YES/NO
    if (strcmp(choice, CMD_REMATCH_YES) == 0) {
        LOG("Rematch ACCEPTED by winner %s for game %d.\n", winner_name, game->id);
        // Resetta gioco
        game->state = GAME_STATE_WAITING;
        game->player1_fd = winner_fd; // Vincitore diventa P1
        strncpy(game->player1_name, winner_name, MAX_NAME_LEN-1); game->player1_name[MAX_NAME_LEN - 1] = '\0';
        game->player2_fd = -1; game->player2_name[0] = '\0';
        init_board(game->board); game->current_turn_fd = -1;
        game->pending_joiner_fd = -1; game->pending_joiner_name[0] = '\0';
        game->winner_fd = -1;
        // Aggiorna stato vincitore
        clients[client_idx].state = CLIENT_STATE_WAITING;
        // Prepara risposta vincitore
        snprintf(response_winner, sizeof(response_winner), RESP_REMATCH_ACCEPTED_FMT, game->id);
        // Aggiorna stato perdente e prepara notifica
        if (loser_idx != -1 && clients[loser_idx].active) {
            LOG("Moving loser %s (fd %d) to LOBBY.\n", loser_name, loser_fd);
            clients[loser_idx].state = CLIENT_STATE_LOBBY; clients[loser_idx].game_id = 0;
            snprintf(notify_loser, sizeof(notify_loser), "%s", NOTIFY_OPPONENT_ACCEPTED_REMATCH);
        }
        send_responses = true;

    } else if (strcmp(choice, CMD_REMATCH_NO) == 0) {
        LOG("Rematch DECLINED by winner %s for game %d.\n", winner_name, game->id);
        // Aggiorna stato vincitore
        clients[client_idx].state = CLIENT_STATE_LOBBY; clients[client_idx].game_id = 0;
        // Prepara risposta vincitore
        snprintf(response_winner, sizeof(response_winner), "%s", RESP_REMATCH_DECLINED);
        // Aggiorna stato perdente e prepara notifica
        if (loser_idx != -1 && clients[loser_idx].active) {
            LOG("Moving loser %s (fd %d) to LOBBY.\n", loser_name, loser_fd);
            clients[loser_idx].state = CLIENT_STATE_LOBBY; clients[loser_idx].game_id = 0;
            snprintf(notify_loser, sizeof(notify_loser), "%s", NOTIFY_OPPONENT_DECLINED);
        }
        // Lasciamo lo slot della partita FINISHED per ora. Verrà pulito se/quando entrambi si disconnettono.
        send_responses = true;
    } else {
        LOG("REMATCH error: Unknown choice '%s'.\n", choice);
        snprintf(error_response, sizeof(error_response), "ERROR:%s\n", "Invalid rematch choice.");
    }

rematch_cleanup:
    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    if (send_responses) {
        if (response_winner[0]) send_to_client(winner_fd, response_winner);
        if (loser_fd >= 0 && notify_loser[0]) send_to_client(loser_fd, notify_loser); // Solo se loser_fd è valido E c'è notifica
    } else if (winner_fd >= 0 && error_response[0]) {
        send_to_client(winner_fd, error_response);
    }
}


bool process_quit_command(int client_idx) {
    // ... (Implementazione come prima, MA potrebbe necessitare modifiche per gestire QUIT nello stato post-partita) ...
    // PER ORA LA MANTENIAMO INVARIATA: gestirà l'uscita chiamando handle_player_leaving_game
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS) return false;
     int client_fd = -1; char client_name[MAX_NAME_LEN]; ClientState current_state; int current_game_id;
     bool request_disconnect = false;
     pthread_mutex_lock(&client_list_mutex);
     if (!clients[client_idx].active) { pthread_mutex_unlock(&client_list_mutex); return true; } // Già inattivo
     client_fd = clients[client_idx].fd; strncpy(client_name, clients[client_idx].name, MAX_NAME_LEN); client_name[MAX_NAME_LEN - 1] = '\0';
     current_state = clients[client_idx].state; current_game_id = clients[client_idx].game_id;
     pthread_mutex_unlock(&client_list_mutex);

     LOG("Client %s sent QUIT. State: %d, Game ID: %d\n", client_name, current_state, current_game_id);

     // Gestione QUIT: Se in partita o in attesa/post-partita, gestisci l'uscita dalla partita
     if (current_state == CLIENT_STATE_PLAYING || current_state == CLIENT_STATE_WAITING) {
         LOG("Client %s is leaving game %d context.\n", client_name, current_game_id);
         pthread_mutex_lock(&client_list_mutex); pthread_mutex_lock(&game_list_mutex);
         int game_idx = find_game_index_unsafe(current_game_id);
         if (game_idx != -1) {
             // handle_player_leaving_game si occuperà di notificare l'avversario
             // e gestire lo stato della partita (anche se FINISHED)
             handle_player_leaving_game(game_idx, client_fd, client_name);
         } else {
             LOG("QUIT: Game %d not found for client %s, state %d.\n", current_game_id, client_name, current_state);
         }
         // Metti SEMPRE il client che manda QUIT in lobby, indipendentemente dallo stato precedente della partita
         if (clients[client_idx].active) { // Ricontrolla prima di scrivere
             clients[client_idx].state = CLIENT_STATE_LOBBY; clients[client_idx].game_id = 0;
             LOG("Client %s moved to LOBBY after QUIT.\n", client_name);
         }
         pthread_mutex_unlock(&game_list_mutex); pthread_mutex_unlock(&client_list_mutex);
         send_to_client(client_fd, RESP_QUIT_OK); // Conferma che è tornato in lobby
         request_disconnect = false; // QUIT gestito come uscita da partita/attesa/post-partita -> non disconnette
     } else {
         // Se in LOBBY o CONNECTED, QUIT = Disconnessione
         LOG("Client %s sent QUIT from state %d. Interpreting as disconnect.\n", client_name, current_state);
         request_disconnect = true;
     }
     return request_disconnect;
}

void send_unknown_command_error(int client_idx, const char* received_command, ClientState current_state) {
     // ... (Implementazione come prima) ...
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS) return;
     int client_fd = -1;
     pthread_mutex_lock(&client_list_mutex);
     if (clients[client_idx].active) client_fd = clients[client_idx].fd;
     pthread_mutex_unlock(&client_list_mutex);
     if (client_fd >= 0) {
         char error_resp[BUFFER_SIZE];
         snprintf(error_resp, sizeof(error_resp), ERR_UNKNOWN_COMMAND_FMT,
                  current_state, received_command ? received_command : "<empty>");
         // Nota: Il Fmt aggiunge già il prefisso ERROR:
        strncat(error_resp, "\n", sizeof(error_resp) - strlen(error_resp) -1);
         send_to_client(client_fd, error_resp);
     }
}