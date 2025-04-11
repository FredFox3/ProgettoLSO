#include "game_logic.h"
#include "utils.h"
#include "protocol.h"
#include <string.h>
#include <stdio.h>

const char* NOTIFY_OPPONENT_LEFT = "NOTIFY:OPPONENT_LEFT Back to lobby.\n";
const char* NOTIFY_WINNER_LEFT_AFTER_GAME = "NOTIFY:WINNER_LEFT Back to lobby.\n";
const char* NOTIFY_GAMEOVER_WIN = "NOTIFY:GAMEOVER WIN\n";
const char* NOTIFY_GAMEOVER_LOSE = "NOTIFY:GAMEOVER LOSE\n";
const char* NOTIFY_GAMEOVER_DRAW = "NOTIFY:GAMEOVER DRAW\n";
const char* NOTIFY_YOUR_TURN = "NOTIFY:YOUR_TURN\n";
const char* NOTIFY_BOARD_PREFIX = "NOTIFY:BOARD ";
const char* RESP_ERROR_PREFIX = "ERROR:";

void init_board(Cell board[3][3]) {
    memset(board, CELL_EMPTY, sizeof(Cell) * 3 * 3);
}

void board_to_string(const Cell board[3][3], char *out_str, size_t max_len) {
    if (!out_str || max_len == 0) return;
    out_str[0] = '\0';
    size_t current_len = 0;
    const size_t cell_width = 2;

    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {

            size_t needed = (r == 2 && c == 2) ? 1 : cell_width;
            if (current_len + needed >= max_len) {

                out_str[max_len - 1] = '\0';
                LOG("Attenzione: buffer board_to_string troncato.\n");
                return;
            }

            char cell_char;
            switch (board[r][c]) {
                case CELL_X: cell_char = 'X'; break;
                case CELL_O: cell_char = 'O'; break;
                default:     cell_char = '-'; break;
            }

            out_str[current_len++] = cell_char;

            if (r < 2 || c < 2) {
                out_str[current_len++] = ' ';
            }
        }

    }
    out_str[current_len] = '\0';
}


bool check_winner(const Cell board[3][3], Cell player) {
     if (player == CELL_EMPTY) return false;

     for (int i = 0; i < 3; i++) {
        if (board[i][0] == player && board[i][1] == player && board[i][2] == player) return true;
        if (board[0][i] == player && board[1][i] == player && board[2][i] == player) return true;
    }

    if (board[0][0] == player && board[1][1] == player && board[2][2] == player) return true;
    if (board[0][2] == player && board[1][1] == player && board[2][0] == player) return true;
    return false;
}

bool board_full(const Cell board[3][3]) {
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++)
            if (board[i][j] == CELL_EMPTY) return false;
    return true;
}

int find_game_index_unsafe(int game_id) {
    if (game_id <= 0) return -1;
    for (int i = 0; i < MAX_GAMES; ++i) {

        if (games[i].state != GAME_STATE_EMPTY && games[i].id == game_id) {
            return i;
        }
    }
    return -1;
}

int find_client_index_unsafe(int fd) {
    if (fd < 0) return -1;
    for (int i = 0; i < MAX_TOTAL_CLIENTS; ++i) {
        if (clients[i].active && clients[i].fd == fd) {
            return i;
        }
    }
    return -1;
}


int find_opponent_fd(const GameInfo* game, int player_fd) {
    if (!game || player_fd < 0) return -1;
    if (game->player1_fd == player_fd) return game->player2_fd;
    if (game->player2_fd == player_fd) return game->player1_fd;
    return -1;
}


void reset_game_slot_to_empty_unsafe(int game_idx) {
    if (game_idx < 0 || game_idx >= MAX_GAMES) return;

    if (games[game_idx].id > 0) {
        LOG("Reimposto lo slot partita indice %d (ID Partita Precedente: %d) a EMPTY\n", game_idx, games[game_idx].id);
    } else {

    }

    memset(&games[game_idx], 0, sizeof(GameInfo));

    games[game_idx].id = 0;
    games[game_idx].state = GAME_STATE_EMPTY;
    games[game_idx].player1_fd = -1;
    games[game_idx].player2_fd = -1;
    games[game_idx].current_turn_fd = -1;
    games[game_idx].pending_joiner_fd = -1;
    games[game_idx].winner_fd = -1;

}


bool handle_player_leaving_game(int game_idx, int leaving_client_fd, const char* leaving_client_name) {

    if (game_idx < 0 || game_idx >= MAX_GAMES || leaving_client_fd < 0) {
        LOG("Errore: Argomenti non validi per handle_player_leaving_game (game_idx=%d, fd=%d)\n", game_idx, leaving_client_fd);
        return false;
    }

    GameInfo* game = &games[game_idx];
    GameState state_at_entry = game->state;

    if (state_at_entry == GAME_STATE_EMPTY) {
         LOG("handle_player_leaving_game chiamato per uno slot già EMPTY (idx %d). Ignoro.\n", game_idx);
         return false;
    }

    char response[BUFFER_SIZE];
    bool game_reset_to_empty = false;

    int opponent_fd = -1;
    int opponent_idx = -1;
    bool was_player1 = (game->player1_fd == leaving_client_fd);
    bool was_player2 = (game->player2_fd == leaving_client_fd);
    bool was_pending = (game->pending_joiner_fd == leaving_client_fd);

    LOG("Gestione uscita: Giocatore '%s' (fd %d) dalla partita %d (idx %d, stato %d)\n",
        leaving_client_name ? leaving_client_name : "N/A",
        leaving_client_fd,
        game->id, game_idx, state_at_entry);

    if (was_pending && state_at_entry == GAME_STATE_WAITING) {
        LOG("Richiedente in attesa '%s' ha lasciato la partita %d. Pulisco richiesta.\n", game->pending_joiner_name, game->id);
        game->pending_joiner_fd = -1;
        game->pending_joiner_name[0] = '\0';
        if (game->player1_fd >= 0) {

             snprintf(response, sizeof(response), NOTIFY_REQUEST_CANCELLED_FMT,
                      leaving_client_name ? leaving_client_name : "Player");
             send_to_client(game->player1_fd, response);
        }

    } else if (was_player1 && state_at_entry == GAME_STATE_WAITING) {
         LOG("Creatore '%s' ha lasciato la partita %d mentre era in WAITING. Annullamento partita e reset slot.\n", game->player1_name, game->id);
         int pending_joiner_notify_fd = game->pending_joiner_fd;
         int saved_game_id = game->id;

         reset_game_slot_to_empty_unsafe(game_idx);
         game_reset_to_empty = true;

         if (pending_joiner_notify_fd >= 0) {

             snprintf(response, sizeof(response), "%sGame %d cancelled: creator '%s' left.\n", RESP_ERROR_PREFIX,
                      saved_game_id, leaving_client_name ? leaving_client_name : "Creator");
             send_to_client(pending_joiner_notify_fd, response);

             int joiner_idx = find_client_index_unsafe(pending_joiner_notify_fd);
             if(joiner_idx != -1 && clients[joiner_idx].active) {
                 clients[joiner_idx].state = CLIENT_STATE_LOBBY;
                 clients[joiner_idx].game_id = 0;
                 LOG("Richiedente in attesa '%s' (fd %d) riportato alla LOBBY a causa dell'uscita del creatore.\n", clients[joiner_idx].name, pending_joiner_notify_fd);
             }
         }

    } else if ((was_player1 || was_player2) && state_at_entry == GAME_STATE_IN_PROGRESS) {
        LOG("Giocatore '%s' ha lasciato la partita %d IN_PROGRESS. L'avversario vince. Imposto stato a FINISHED. Sposto vincitore in lobby.\n",
            leaving_client_name ? leaving_client_name : "Player", game->id);

        opponent_fd = find_opponent_fd(game, leaving_client_fd);

        game->state = GAME_STATE_FINISHED;
        game->current_turn_fd = -1;

        game->winner_fd = opponent_fd;

        if (was_player1) game->player1_fd = -2;
        else game->player2_fd = -2;

        if (opponent_fd >= 0) {
            LOG("Notifico all'avversario fd %d che ha vinto la partita %d e lo sposto in LOBBY.\n", opponent_fd, game->id);
            send_to_client(opponent_fd, NOTIFY_OPPONENT_LEFT);
            send_to_client(opponent_fd, NOTIFY_GAMEOVER_WIN);

            opponent_idx = find_client_index_unsafe(opponent_fd);
            if (opponent_idx != -1 && clients[opponent_idx].active) {
                clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                clients[opponent_idx].game_id = 0;
                LOG("Avversario '%s' (fd %d) spostato in LOBBY poiché la partita %d è terminata a causa dell'uscita dell'avversario IN_PROGRESS.\n", clients[opponent_idx].name, opponent_fd, game->id);
            }
        } else {
             LOG("Avversario per il giocatore %d che è uscito non trovato o già disconnesso.\n", leaving_client_fd);
        }
         LOG("Partita %d (indice %d) impostata a FINISHED a causa dell'uscita del giocatore IN_PROGRESS.\n", game->id, game_idx);

         if(opponent_fd < 0){
            LOG("Entrambi i giocatori sembrano disconnessi per la partita %d dopo che uno è uscito IN_PROGRESS. Reset dello slot.\n", game->id);
            reset_game_slot_to_empty_unsafe(game_idx);
            game_reset_to_empty = true;
         }


    } else if ((was_player1 || was_player2) && state_at_entry == GAME_STATE_FINISHED) {
        LOG("Giocatore '%s' è uscito dopo che la partita %d era già FINISHED.\n",
             leaving_client_name ? leaving_client_name : "Player", game->id);

        opponent_fd = find_opponent_fd(game, leaving_client_fd);
        opponent_idx = find_client_index_unsafe(opponent_fd);

        bool is_winner = (game->winner_fd == leaving_client_fd);
        bool is_draw = (game->winner_fd == -1);

        if (was_player1) game->player1_fd = -2;
        else game->player2_fd = -2;

        if (is_winner) {

            LOG("VINCITORE '%s' (fd %d) ha lasciato la partita %d dopo FINISH. Rinuncia al rematch.\n",
                 leaving_client_name, leaving_client_fd, game->id);
            game->winner_fd = -2;

            if (opponent_fd >= 0 && opponent_idx != -1 && clients[opponent_idx].active) {
                LOG("Notifico al perdente '%s' (fd %d) che il vincitore è uscito. Sposto in LOBBY.\n", clients[opponent_idx].name, opponent_fd);
                send_to_client(opponent_fd, NOTIFY_WINNER_LEFT_AFTER_GAME);
                clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                clients[opponent_idx].game_id = 0;
            } else {
                 LOG("Perdente (fd originale %d) già disconnesso o inattivo.\n", opponent_fd);

                 if(opponent_fd == -2){
                    LOG("Sia il vincitore che il perdente sono -2 per la partita %d. Reset dello slot.\n", game->id);
                    reset_game_slot_to_empty_unsafe(game_idx);
                    game_reset_to_empty = true;
                 }
            }
        } else if (is_draw) {

             LOG("Giocatore '%s' (fd %d) ha lasciato la partita %d dopo un PAREGGIO.\n", leaving_client_name, leaving_client_fd, game->id);

             if (opponent_fd >= 0 && opponent_idx != -1 && clients[opponent_idx].active) {
                 LOG("Notifico all'avversario '%s' (fd %d) che l'altro giocatore è uscito dopo un pareggio. Sposto in LOBBY.\n", clients[opponent_idx].name, opponent_fd);
                 send_to_client(opponent_fd, NOTIFY_OPPONENT_LEFT);
                 clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                 clients[opponent_idx].game_id = 0;
             } else {
                 LOG("L'altro giocatore (fd originale %d) nel pareggio è già disconnesso o inattivo.\n", opponent_fd);

                 if(opponent_fd == -2){
                    LOG("Entrambi i giocatori sono -2 per la partita %d dopo un pareggio. Reset dello slot.\n", game->id);
                    reset_game_slot_to_empty_unsafe(game_idx);
                    game_reset_to_empty = true;
                 }
             }
        } else {

            LOG("PERDENTE '%s' (fd %d) ha lasciato la partita %d dopo FINISH.\n",
                 leaving_client_name, leaving_client_fd, game->id);


            if (opponent_fd == -2) {
                LOG("Il perdente è uscito dopo che il vincitore era già uscito per la partita %d. Reset dello slot.\n", game->id);
                reset_game_slot_to_empty_unsafe(game_idx);
                game_reset_to_empty = true;
            } else {
                LOG("Il vincitore (fd originale %d) rimane, in attesa della scelta del rematch o della disconnessione.\n", opponent_fd);
            }
        }

    } else {
         LOG("Giocatore '%s' (fd %d) ha lasciato la partita %d in uno stato inatteso %d o non era attivamente coinvolto (P1:%d, P2:%d, Pend:%d, Win:%d). Nessun cambio stato partita.\n",
            leaving_client_name ? leaving_client_name : "Player",
            leaving_client_fd, game->id, state_at_entry,
            game->player1_fd, game->player2_fd, game->pending_joiner_fd, game->winner_fd);
    }


    return game_reset_to_empty;
}

void broadcast_game_state(int game_idx) {

    if (game_idx < 0 || game_idx >= MAX_GAMES) {
        LOG("Errore: game_idx %d non valido per broadcast_game_state\n", game_idx);
        return;
    }

    GameInfo *game = &games[game_idx];

    if(game->state == GAME_STATE_EMPTY) {
        LOG("Attenzione: Tentativo di broadcast_game_state per lo slot EMPTY indice %d\n", game_idx);
        return;
    }

    char board_msg[BUFFER_SIZE];
    char board_str[20];


    board_to_string(game->board, board_str, sizeof(board_str));
    snprintf(board_msg, sizeof(board_msg), "%s%s\n", NOTIFY_BOARD_PREFIX, board_str);

    if (game->player1_fd >= 0) {
        send_to_client(game->player1_fd, board_msg);
    }
    if (game->player2_fd >= 0) {
        send_to_client(game->player2_fd, board_msg);
    }

    if (game->state == GAME_STATE_IN_PROGRESS && game->current_turn_fd >= 0) {

        LOG("--- BROADCAST: Invio YOUR_TURN a fd %d per partita %d (Stato: %d) ---\n",
            game->current_turn_fd, game->id, game->state);

        send_to_client(game->current_turn_fd, NOTIFY_YOUR_TURN);
    } else {

         char p1n[10], p2n[10];
         snprintf(p1n, sizeof(p1n), "%d", game->player1_fd);
         snprintf(p2n, sizeof(p2n), "%d", game->player2_fd);
         LOG("--- BROADCAST: NON invio YOUR_TURN per partita %d (Stato: %d, Turno FD: %d, P1_FD: %s, P2_FD: %s) ---\n",
            game->id, game->state, game->current_turn_fd,
            p1n, p2n);
    }
}