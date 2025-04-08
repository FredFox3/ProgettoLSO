#include "game_logic.h"
#include "utils.h" // Per LOG, send_to_client
#include <string.h>
#include <stdio.h> // per snprintf

// Definizioni costanti stringhe
const char* NOTIFY_OPPONENT_LEFT = "NOTIFY:OPPONENT_LEFT\n";
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

    // Logga prima di resettare se l'ID era valido
    if (games[game_idx].id > 0) {
        LOG("Resetting game slot index %d (Previous Game ID: %d) to EMPTY\n", game_idx, games[game_idx].id);
    } else {
         LOG("Resetting unused game slot index %d to EMPTY\n", game_idx);
    }

    // Usa memset per azzerare gran parte della struttura in modo efficiente
    memset(&games[game_idx], 0, sizeof(GameInfo));

    // Reimposta valori specifici per chiarezza e sicurezza
    games[game_idx].id = 0; // ID 0 indica slot vuoto
    games[game_idx].state = GAME_STATE_EMPTY;
    games[game_idx].player1_fd = -1;
    games[game_idx].player2_fd = -1;
    games[game_idx].current_turn_fd = -1;
    games[game_idx].pending_joiner_fd = -1;
    // init_board è implicitamente fatto da memset, ma chiamarlo è sicuro
    init_board(games[game_idx].board);
}


bool handle_player_leaving_game(int game_idx, int leaving_client_fd, const char* leaving_client_name) {
    // Assumes client_list_mutex and game_list_mutex are LOCKED by caller

    // Controlli iniziali su indici e fd
    if (game_idx < 0 || game_idx >= MAX_GAMES || leaving_client_fd < 0) {
        LOG("Error: Invalid arguments to handle_player_leaving_game (game_idx=%d, fd=%d)\n",
            game_idx, leaving_client_fd);
        return false; // Indice o fd non valido
    }

    GameInfo* game = &games[game_idx];
    // *** CORREZIONE: Leggi lo stato della partita all'inizio ***
    GameState state_at_entry = game->state;

    // Se la partita è già vuota, non c'è nulla da fare
    if (state_at_entry == GAME_STATE_EMPTY) {
         LOG("handle_player_leaving_game called for an already EMPTY slot (idx %d). Ignoring.\n", game_idx);
         return false;
    }

    char response[BUFFER_SIZE]; // Buffer per messaggi di notifica
    bool game_reset_to_empty = false; // Flag per indicare se la partita viene annullata

    int opponent_fd = -1;
    int opponent_idx = -1;
    bool was_player1 = (game->player1_fd == leaving_client_fd);
    bool was_player2 = (game->player2_fd == leaving_client_fd);
    bool was_pending = (game->pending_joiner_fd == leaving_client_fd);

    // Log iniziale (usa lo stato letto all'inizio)
    LOG("Handling departure: Player '%s' (fd %d) from game %d (idx %d, state %d)\n",
        leaving_client_name ? leaving_client_name : "N/A",
        leaving_client_fd,
        game->id, game_idx, state_at_entry); // Stampa lo stato all'entrata

    // --- Logica basata sullo stato all'entrata ---

    // Caso 1: Il giocatore che esce era un richiedente in attesa (pending joiner)
    // Usa state_at_entry per la condizione
    if (was_pending && state_at_entry == GAME_STATE_WAITING) {
        LOG("Pending joiner '%s' left game %d. Clearing request.\n", game->pending_joiner_name, game->id);
        // Azioni: Resetta solo la parte pending
        game->pending_joiner_fd = -1;
        game->pending_joiner_name[0] = '\0';
        // Notifica il creatore che la richiesta è stata annullata
        if (game->player1_fd >= 0) { // Controlla se il creatore è ancora valido
             snprintf(response, sizeof(response), "NOTIFY:REQUEST_CANCELLED %s left\n",
                      leaving_client_name ? leaving_client_name : "Player");
             send_to_client(game->player1_fd, response);
        }
    }
    // Caso 2: Il giocatore che esce era il creatore (P1) mentre la partita era in attesa (WAITING)
    // Usa state_at_entry per la condizione
    else if (was_player1 && state_at_entry == GAME_STATE_WAITING) {
        LOG("Creator '%s' left game %d while WAITING. Cancelling game and resetting slot.\n", game->player1_name, game->id);
        // Azioni: Notifica pending joiner (se c'è) e resetta l'intero slot
        int pending_joiner_notify_fd = game->pending_joiner_fd; // Copia fd prima di resettare
        char pending_joiner_name_copy[MAX_NAME_LEN];
        strncpy(pending_joiner_name_copy, game->pending_joiner_name, MAX_NAME_LEN);
        pending_joiner_name_copy[MAX_NAME_LEN-1] = '\0';

        // Resetta lo slot della partita a EMPTY
        reset_game_slot_to_empty_unsafe(game_idx);
        game_reset_to_empty = true; // Segnala che lo slot è stato resettato

        // Notifica il richiedente pendente (se esisteva) DOPO aver resettato
        if (pending_joiner_notify_fd >= 0) {
             snprintf(response, sizeof(response), "ERROR:Game %d cancelled: creator '%s' left.\n", game->id, // Usa game->id salvato implicitamente da reset? No, l'id viene azzerato. Meglio usare un id salvato se serve. Ma qui l'id della partita annullata non è più rilevante per il joiner.
                       leaving_client_name ? leaving_client_name : "Creator"); // Nome del creatore che è uscito
             send_to_client(pending_joiner_notify_fd, response);
             // Rimetti il richiedente nella lobby (assumendo che il suo thread si occuperà della sua disconnessione se necessario)
             int joiner_idx = find_client_index_unsafe(pending_joiner_notify_fd);
             if(joiner_idx != -1 && clients[joiner_idx].active) { // Check anche active
                 clients[joiner_idx].state = CLIENT_STATE_LOBBY;
                 clients[joiner_idx].game_id = 0;
                 LOG("Pending joiner '%s' (fd %d) returned to LOBBY due to creator leaving.\n", clients[joiner_idx].name, pending_joiner_notify_fd);
             }
        }
    }
    // Caso 3: Il giocatore (P1 o P2) esce mentre la partita è in corso (IN_PROGRESS)
    // Usa state_at_entry per la condizione
    else if ((was_player1 || was_player2) && state_at_entry == GAME_STATE_IN_PROGRESS) {
        LOG("Player '%s' left game %d IN_PROGRESS. Opponent wins. Setting state to FINISHED.\n",
            leaving_client_name ? leaving_client_name : "Player", game->id);

        opponent_fd = find_opponent_fd(game, leaving_client_fd);

        // Azioni: Imposta stato a FINISHED, notifica avversario
        game->state = GAME_STATE_FINISHED; // La partita finisce
        game->current_turn_fd = -1; // Nessuno ha il turno

        // Marca il giocatore che è uscito come invalido (-2)
        if (was_player1) game->player1_fd = -2;
        else game->player2_fd = -2;

        // Notifica l'avversario (se ancora valido) e rimettilo nella lobby
        if (opponent_fd >= 0) { // Controlla se fd è valido (non -1 o -2)
            send_to_client(opponent_fd, NOTIFY_OPPONENT_LEFT);
            send_to_client(opponent_fd, NOTIFY_GAMEOVER_WIN); // L'avversario vince
            opponent_idx = find_client_index_unsafe(opponent_fd);
            if (opponent_idx != -1 && clients[opponent_idx].active) { // Check active
                 clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                 clients[opponent_idx].game_id = 0;
                 LOG("Opponent '%s' (fd %d) moved to LOBBY due to player leaving.\n", clients[opponent_idx].name, opponent_fd);
            }
        } else {
             LOG("Opponent for leaving player %d not found or already disconnected.\n", leaving_client_fd);
        }
         LOG("Game %d (index %d) set to FINISHED due to player leaving.\n", game->id, game_idx);
    }
    // Caso 4: Il giocatore esce dopo che la partita è già finita (FINISHED)
    // Usa state_at_entry per la condizione
    else if ((was_player1 || was_player2) && state_at_entry == GAME_STATE_FINISHED) {
        LOG("Player '%s' left after game %d already FINISHED. Marking fd as disconnected.\n",
             leaving_client_name ? leaving_client_name : "Player", game->id);
        // Azioni: Marca solo il fd come invalido per evitare futuri tentativi di invio
        if (was_player1) game->player1_fd = -2;
        else game->player2_fd = -2;
    }
    // Caso 5: Il giocatore non era attivamente coinvolto o stato inatteso
    else {
         // Stampa lo stato letto all'inizio per il debug
         LOG("Player '%s' (fd %d) left game %d in unexpected state %d or wasn't actively involved (P1:%d, P2:%d, Pend:%d). No game state change.\n",
            leaving_client_name ? leaving_client_name : "Player",
            leaving_client_fd, game->id, state_at_entry,
            game->player1_fd, game->player2_fd, game->pending_joiner_fd);
    }

    return game_reset_to_empty; // Ritorna true solo se lo slot è stato resettato
}

void broadcast_game_state(int game_idx) {
    // ... (controllo game_idx come prima) ...
    GameInfo *game = &games[game_idx];
    char board_msg[BUFFER_SIZE]; // Buffer per l'intero messaggio NOTIFY:BOARD
    char board_str[18]; // Buffer sufficiente per "X O - X O - X O -" (9 celle + 8 spazi + \0)

    // Prepara stringa board (ora su una riga)
    board_to_string(game->board, board_str, sizeof(board_str));

    // Componi il messaggio completo
    snprintf(board_msg, sizeof(board_msg), "%s%s\n", NOTIFY_BOARD_PREFIX, board_str); // NOTIFY:BOARD <board_su_una_riga>\n

    // Invia stato board a entrambi (se fd validi)
    if (game->player1_fd >= 0) send_to_client(game->player1_fd, board_msg);
    if (game->player2_fd >= 0) send_to_client(game->player2_fd, board_msg);

    // Invia notifica turno (solo se la partita è in corso e c'è un turno)
    if (game->state == GAME_STATE_IN_PROGRESS && game->current_turn_fd >= 0) {
        // send_to_client già aggiunge \n implicitamente se non c'è,
        // ma è buona pratica averlo nella costante o aggiungerlo qui.
        // Assumiamo che NOTIFY_YOUR_TURN definito in game_logic.h finisca con \n
        send_to_client(game->current_turn_fd, NOTIFY_YOUR_TURN);
    }
}