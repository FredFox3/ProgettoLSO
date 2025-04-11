#include "game_logic.h"
#include "utils.h" // Per LOG, send_to_client
#include "protocol.h"
#include <string.h>
#include <stdio.h> // per snprintf

// Definizioni costanti stringhe
const char* NOTIFY_OPPONENT_LEFT = "NOTIFY:OPPONENT_LEFT Back to lobby.\n"; // Aggiunto "Back to lobby." per chiarezza
const char* NOTIFY_WINNER_LEFT_AFTER_GAME = "NOTIFY:WINNER_LEFT Back to lobby.\n"; // Nuovo: Per notificare il perdente se il vincitore esce dopo la fine
const char* NOTIFY_GAMEOVER_WIN = "NOTIFY:GAMEOVER WIN\n";
const char* NOTIFY_GAMEOVER_LOSE = "NOTIFY:GAMEOVER LOSE\n";
const char* NOTIFY_GAMEOVER_DRAW = "NOTIFY:GAMEOVER DRAW\n";
const char* NOTIFY_YOUR_TURN = "NOTIFY:YOUR_TURN\n";
const char* NOTIFY_BOARD_PREFIX = "NOTIFY:BOARD "; // Spazio alla fine!
const char* RESP_ERROR_PREFIX = "ERROR:";

// --- Implementazione Funzioni Board ---

void init_board(Cell board[3][3]) {
    memset(board, CELL_EMPTY, sizeof(Cell) * 3 * 3);
}

// In game_logic.c

void board_to_string(const Cell board[3][3], char *out_str, size_t max_len) {
    if (!out_str || max_len == 0) return;
    out_str[0] = '\0';
    size_t current_len = 0;
    const size_t cell_width = 2; // Spazio per cella + spazio separatore

    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {
            // Calcola quanto spazio serve per questa cella e lo spazio successivo (o \0)
            size_t needed = (r == 2 && c == 2) ? 1 : cell_width;
            if (current_len + needed >= max_len) {
                // Non c'è abbastanza spazio, termina la stringa qui
                out_str[max_len - 1] = '\0'; // Assicura null termination
                LOG("Warning: board_to_string buffer truncated.\n");
                return;
            }

            char cell_char;
            switch (board[r][c]) {
                case CELL_X: cell_char = 'X'; break;
                case CELL_O: cell_char = 'O'; break;
                default:     cell_char = '-'; break; // Usa '-' per vuoto
            }

            // Aggiungi la cella
            out_str[current_len++] = cell_char;

            // Aggiungi uno spazio se non è l'ultima cella in assoluto
            if (r < 2 || c < 2) {
                out_str[current_len++] = ' ';
            }
        }
        // NON aggiungere newline qui!
    }
    out_str[current_len] = '\0'; // Termina la stringa correttamente
}


bool check_winner(const Cell board[3][3], Cell player) {
     if (player == CELL_EMPTY) return false;
     // Controlla righe e colonne
     for (int i = 0; i < 3; i++) {
        if (board[i][0] == player && board[i][1] == player && board[i][2] == player) return true;
        if (board[0][i] == player && board[1][i] == player && board[2][i] == player) return true;
    }
    // Controlla diagonali
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

// --- Implementazione Funzioni Gestione Partite ---

int find_game_index_unsafe(int game_id) {
    if (game_id <= 0) return -1;
    for (int i = 0; i < MAX_GAMES; ++i) {
        // Cerca solo partite non vuote con l'ID corrispondente
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
    if (game->player1_fd == player_fd) return game->player2_fd; // Ritorna fd P2 (può essere -1 o -2)
    if (game->player2_fd == player_fd) return game->player1_fd; // Ritorna fd P1 (può essere -1 o -2)
    return -1; // player_fd non è in questa partita
}


void reset_game_slot_to_empty_unsafe(int game_idx) {
    if (game_idx < 0 || game_idx >= MAX_GAMES) return;

    if (games[game_idx].id > 0) {
        LOG("Resetting game slot index %d (Previous Game ID: %d) to EMPTY\n", game_idx, games[game_idx].id);
    } else {
         // Non loggare se era già vuoto (succede all'avvio)
         // LOG("Resetting unused game slot index %d to EMPTY\n", game_idx);
    }

    memset(&games[game_idx], 0, sizeof(GameInfo)); // Azzeramento iniziale

    // Reimposta valori specifici per chiarezza e sicurezza
    games[game_idx].id = 0;
    games[game_idx].state = GAME_STATE_EMPTY;
    games[game_idx].player1_fd = -1;
    games[game_idx].player2_fd = -1;
    games[game_idx].current_turn_fd = -1;
    games[game_idx].pending_joiner_fd = -1;
    games[game_idx].winner_fd = -1; // *** INIZIALIZZA winner_fd ***
    // init_board è implicito con memset a 0 dato che CELL_EMPTY è 0
    // init_board(games[game_idx].board);
}


bool handle_player_leaving_game(int game_idx, int leaving_client_fd, const char* leaving_client_name) {
    // Assumes client_list_mutex and game_list_mutex are LOCKED by caller

    if (game_idx < 0 || game_idx >= MAX_GAMES || leaving_client_fd < 0) {
        LOG("Error: Invalid arguments to handle_player_leaving_game (game_idx=%d, fd=%d)\n", game_idx, leaving_client_fd);
        return false;
    }

    GameInfo* game = &games[game_idx];
    GameState state_at_entry = game->state; // Leggi stato partita all'inizio

    if (state_at_entry == GAME_STATE_EMPTY) {
         LOG("handle_player_leaving_game called for an already EMPTY slot (idx %d). Ignoring.\n", game_idx);
         return false;
    }

    char response[BUFFER_SIZE];
    bool game_reset_to_empty = false;

    int opponent_fd = -1;
    int opponent_idx = -1;
    bool was_player1 = (game->player1_fd == leaving_client_fd);
    bool was_player2 = (game->player2_fd == leaving_client_fd);
    bool was_pending = (game->pending_joiner_fd == leaving_client_fd);

    LOG("Handling departure: Player '%s' (fd %d) from game %d (idx %d, state %d)\n",
        leaving_client_name ? leaving_client_name : "N/A",
        leaving_client_fd,
        game->id, game_idx, state_at_entry);

    // --- Logica basata sullo stato all'entrata ---

    // Caso 1: Pending joiner
    if (was_pending && state_at_entry == GAME_STATE_WAITING) {
        LOG("Pending joiner '%s' left game %d. Clearing request.\n", game->pending_joiner_name, game->id);
        game->pending_joiner_fd = -1;
        game->pending_joiner_name[0] = '\0';
        if (game->player1_fd >= 0) {
             // Usa la costante definita in protocol.h/c
             snprintf(response, sizeof(response), NOTIFY_REQUEST_CANCELLED_FMT,
                      leaving_client_name ? leaving_client_name : "Player");
             send_to_client(game->player1_fd, response);
        }

    // Caso 2: Creatore (P1) lascia mentre WAITING
    } else if (was_player1 && state_at_entry == GAME_STATE_WAITING) {
         LOG("Creator '%s' left game %d while WAITING. Cancelling game and resetting slot.\n", game->player1_name, game->id);
         int pending_joiner_notify_fd = game->pending_joiner_fd;
         int saved_game_id = game->id; // Salva l'ID prima di resettare

         // Resetta slot a EMPTY
         reset_game_slot_to_empty_unsafe(game_idx);
         game_reset_to_empty = true;

         if (pending_joiner_notify_fd >= 0) {
             // Notifica il joiner che la partita è cancellata
             snprintf(response, sizeof(response), "%sGame %d cancelled: creator '%s' left.\n", RESP_ERROR_PREFIX,
                      saved_game_id, leaving_client_name ? leaving_client_name : "Creator");
             send_to_client(pending_joiner_notify_fd, response);

             // Rimetti il joiner in lobby
             int joiner_idx = find_client_index_unsafe(pending_joiner_notify_fd);
             if(joiner_idx != -1 && clients[joiner_idx].active) {
                 clients[joiner_idx].state = CLIENT_STATE_LOBBY;
                 clients[joiner_idx].game_id = 0;
                 LOG("Pending joiner '%s' (fd %d) returned to LOBBY due to creator leaving.\n", clients[joiner_idx].name, pending_joiner_notify_fd);
             }
         }

    // Caso 3: Giocatore (P1 o P2) lascia mentre IN_PROGRESS
    } else if ((was_player1 || was_player2) && state_at_entry == GAME_STATE_IN_PROGRESS) {
        LOG("Player '%s' left game %d IN_PROGRESS. Opponent wins. Setting state to FINISHED. Moving winner to lobby.\n",
            leaving_client_name ? leaving_client_name : "Player", game->id);

        opponent_fd = find_opponent_fd(game, leaving_client_fd);

        game->state = GAME_STATE_FINISHED; // La partita finisce
        game->current_turn_fd = -1;

        // L'avversario è il vincitore forzato
        game->winner_fd = opponent_fd;

        // Marca chi è uscito
        if (was_player1) game->player1_fd = -2;
        else game->player2_fd = -2;

        // Notifica l'avversario e mettilo subito in lobby (non c'è rematch in questo caso)
        if (opponent_fd >= 0) {
            LOG("Notifying opponent fd %d they won game %d and moving to LOBBY.\n", opponent_fd, game->id);
            send_to_client(opponent_fd, NOTIFY_OPPONENT_LEFT); // Notifica uscita
            send_to_client(opponent_fd, NOTIFY_GAMEOVER_WIN);  // Notifica vittoria

            // Metti subito l'avversario (vincitore) in lobby
            opponent_idx = find_client_index_unsafe(opponent_fd);
            if (opponent_idx != -1 && clients[opponent_idx].active) {
                clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                clients[opponent_idx].game_id = 0;
                LOG("Opponent '%s' (fd %d) moved to LOBBY as game %d ended due to opponent leaving IN_PROGRESS.\n", clients[opponent_idx].name, opponent_fd, game->id);
            }
        } else {
             LOG("Opponent for leaving player %d not found or already disconnected.\n", leaving_client_fd);
        }
         LOG("Game %d (index %d) set to FINISHED due to player leaving IN_PROGRESS.\n", game->id, game_idx);

         // Controlla se anche l'altro è già disconnesso per pulire lo slot
         if(opponent_fd < 0){ // Se l'avversario non è valido
            LOG("Both players seem disconnected for game %d after one left IN_PROGRESS. Resetting slot.\n", game->id);
            reset_game_slot_to_empty_unsafe(game_idx);
            game_reset_to_empty = true;
         }


    // Caso 4: Giocatore (P1 o P2) lascia DOPO la fine (FINISHED)
    } else if ((was_player1 || was_player2) && state_at_entry == GAME_STATE_FINISHED) {
        LOG("Player '%s' left after game %d already FINISHED.\n",
             leaving_client_name ? leaving_client_name : "Player", game->id);

        // Trova l'avversario (potrebbe essere già -2)
        opponent_fd = find_opponent_fd(game, leaving_client_fd);
        opponent_idx = find_client_index_unsafe(opponent_fd); // Cerca l'indice solo se fd non è -2

        // Determina se chi esce è il vincitore, il perdente o se era un pareggio
        bool is_winner = (game->winner_fd == leaving_client_fd);
        bool is_draw = (game->winner_fd == -1);

        // Marca chi è uscito come invalido nella partita
        if (was_player1) game->player1_fd = -2;
        else game->player2_fd = -2;

        if (is_winner) {
            // --- Il VINCITORE esce prima di rispondere a REMATCH ---
            LOG("WINNER '%s' (fd %d) left game %d after FINISH. Forfeits rematch.\n",
                 leaving_client_name, leaving_client_fd, game->id);
            game->winner_fd = -2; // Marca vincitore come uscito/invalido

            // Notifica il perdente (se ancora connesso) e mettilo in lobby
            if (opponent_fd >= 0 && opponent_idx != -1 && clients[opponent_idx].active) {
                LOG("Notifying loser '%s' (fd %d) that winner left. Moving to LOBBY.\n", clients[opponent_idx].name, opponent_fd);
                send_to_client(opponent_fd, NOTIFY_WINNER_LEFT_AFTER_GAME); // Messaggio specifico
                clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                clients[opponent_idx].game_id = 0;
            } else {
                 LOG("Loser (original fd %d) already disconnected or inactive.\n", opponent_fd);
                 // Se anche il perdente era già -2, resetta lo slot
                 if(opponent_fd == -2){
                    LOG("Both winner and loser are -2 for game %d. Resetting slot.\n", game->id);
                    reset_game_slot_to_empty_unsafe(game_idx);
                    game_reset_to_empty = true;
                 }
            }
        } else if (is_draw) {
             // --- Un giocatore esce dopo un PAREGGIO ---
             LOG("Player '%s' (fd %d) left game %d after DRAW.\n", leaving_client_name, leaving_client_fd, game->id);

             // Notifica l'altro giocatore (se ancora connesso) e mettilo in lobby
             if (opponent_fd >= 0 && opponent_idx != -1 && clients[opponent_idx].active) {
                 LOG("Notifying opponent '%s' (fd %d) that other player left after draw. Moving to LOBBY.\n", clients[opponent_idx].name, opponent_fd);
                 send_to_client(opponent_fd, NOTIFY_OPPONENT_LEFT); // Messaggio generico
                 clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                 clients[opponent_idx].game_id = 0;
             } else {
                 LOG("Other player (original fd %d) in draw already disconnected or inactive.\n", opponent_fd);
                 // Se anche l'altro era già -2, resetta lo slot
                 if(opponent_fd == -2){
                    LOG("Both players are -2 for game %d after draw. Resetting slot.\n", game->id);
                    reset_game_slot_to_empty_unsafe(game_idx);
                    game_reset_to_empty = true;
                 }
             }
        } else {
            // --- Il PERDENTE esce ---
            LOG("LOSER '%s' (fd %d) left game %d after FINISH.\n",
                 leaving_client_name, leaving_client_fd, game->id);
            // Nessuna azione necessaria sul vincitore qui.
            // Se il vincitore era l'opponent_fd, controlla se è ancora valido
            if (opponent_fd == -2) { // Se il vincitore si era già disconnesso
                LOG("Loser left after winner already left for game %d. Resetting slot.\n", game->id);
                reset_game_slot_to_empty_unsafe(game_idx);
                game_reset_to_empty = true;
            } else {
                LOG("Winner (original fd %d) remains, awaiting rematch choice or disconnect.\n", opponent_fd);
            }
        }

    // Caso 5: Uscita inattesa
    } else {
         LOG("Player '%s' (fd %d) left game %d in unexpected state %d or wasn't actively involved (P1:%d, P2:%d, Pend:%d, Win:%d). No game state change.\n",
            leaving_client_name ? leaving_client_name : "Player",
            leaving_client_fd, game->id, state_at_entry,
            game->player1_fd, game->player2_fd, game->pending_joiner_fd, game->winner_fd);
    }

    // --- Pulizia Finale dello Slot ---
    // Questa logica è ridondante ora che resettiamo lo slot quando P1/P2 diventano entrambi -2 nello stato FINISHED
    // // Controlla se entrambi i giocatori sono ora disconnessi (-2) E lo stato è FINISHED o EMPTY
    // if ((game->state == GAME_STATE_FINISHED || game->state == GAME_STATE_EMPTY) // Controlla anche EMPTY nel caso raro sia stato resettato ma siamo ancora qui
    //     && game->player1_fd == -2 && game->player2_fd == -2
    //     && !game_reset_to_empty ) { // E non l'abbiamo già resettato
    //     LOG("Both players marked as left (-2) for game %d. Resetting game slot %d.\n", game->id, game_idx);
    //     reset_game_slot_to_empty_unsafe(game_idx);
    //     game_reset_to_empty = true;
    // }

    return game_reset_to_empty;
}

void broadcast_game_state(int game_idx) {
    // Assume game_list_mutex is LOCKED by caller

    if (game_idx < 0 || game_idx >= MAX_GAMES) {
        LOG("Error: Invalid game_idx %d for broadcast_game_state\n", game_idx);
        return;
    }

    GameInfo *game = &games[game_idx];
    // Aggiunto controllo se slot è empty per evitare accessi strani
    if(game->state == GAME_STATE_EMPTY) {
        LOG("Warning: Attempted broadcast_game_state for EMPTY slot index %d\n", game_idx);
        return;
    }

    char board_msg[BUFFER_SIZE];
    char board_str[20]; // Buffer leggermente più grande per sicurezza

    // 1. Prepara e invia la stringa della board
    board_to_string(game->board, board_str, sizeof(board_str));
    snprintf(board_msg, sizeof(board_msg), "%s%s\n", NOTIFY_BOARD_PREFIX, board_str);

    // Invia stato board a entrambi i giocatori validi (fd >= 0)
    if (game->player1_fd >= 0) {
        send_to_client(game->player1_fd, board_msg);
    }
    if (game->player2_fd >= 0) {
        send_to_client(game->player2_fd, board_msg);
    }

    // 2. Invia la notifica del turno SE la partita è in corso E c'è un giocatore valido di turno
    if (game->state == GAME_STATE_IN_PROGRESS && game->current_turn_fd >= 0) {
        // === Log Esplicito PRIMA dell'invio ===
        LOG("--- BROADCAST: Sending YOUR_TURN to fd %d for game %d (State: %d) ---\n",
            game->current_turn_fd, game->id, game->state);
        // ------------------------------------
        send_to_client(game->current_turn_fd, NOTIFY_YOUR_TURN);
    } else {
        // Log esplicito se NON si invia il turno (utile per debug)
         char p1n[10], p2n[10]; // Buffer per convertire fd in stringa
         snprintf(p1n, sizeof(p1n), "%d", game->player1_fd);
         snprintf(p2n, sizeof(p2n), "%d", game->player2_fd);
         LOG("--- BROADCAST: NOT sending YOUR_TURN for game %d (State: %d, Turn FD: %d, P1_FD: %s, P2_FD: %s) ---\n",
            game->id, game->state, game->current_turn_fd,
            p1n, p2n);
    }
}