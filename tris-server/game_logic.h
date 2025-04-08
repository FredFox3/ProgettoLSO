#ifndef GAME_LOGIC_H
#define GAME_LOGIC_H

#include "types.h" // Include definizioni tipi (Cell, GameInfo, ClientInfo)
#include <stddef.h> // Per size_t

// --- Costanti Stringhe Protocollo (relative alla logica di gioco) ---
// Si potrebbero mettere in protocol.h, ma sono usate qui per notificare
extern const char* NOTIFY_OPPONENT_LEFT;
extern const char* NOTIFY_GAMEOVER_WIN;
extern const char* NOTIFY_GAMEOVER_LOSE;
extern const char* NOTIFY_GAMEOVER_DRAW;
extern const char* NOTIFY_YOUR_TURN;
extern const char* NOTIFY_BOARD_PREFIX; // "NOTIFY:BOARD "
extern const char* RESP_ERROR_PREFIX; // "ERROR:"

// --- Funzioni Board ---
void init_board(Cell board[3][3]);
void board_to_string(const Cell board[3][3], char *out_str, size_t max_len);
bool check_winner(const Cell board[3][3], Cell player);
bool board_full(const Cell board[3][3]);

// --- Funzioni Gestione Partite ---

/**
 * @brief Trova l'indice nell'array `games` corrispondente a un game_id.
 * NON blocca i mutex. Deve essere chiamata all'interno di una sezione critica.
 * @param game_id L'ID della partita da cercare.
 * @return L'indice nell'array `games` o -1 se non trovata o ID non valido.
 */
int find_game_index_unsafe(int game_id);

/**
 * @brief Trova l'indice nell'array `clients` corrispondente a un file descriptor.
 * NON blocca i mutex. Deve essere chiamata all'interno di una sezione critica.
 * @param fd Il file descriptor del client da cercare.
 * @return L'indice nell'array `clients` o -1 se non trovato.
 */
int find_client_index_unsafe(int fd);

/**
 * @brief Trova il file descriptor dell'avversario in una partita.
 * @param game Puntatore alla struttura GameInfo della partita.
 * @param player_fd Il file descriptor del giocatore di cui si cerca l'avversario.
 * @return Il file descriptor dell'avversario, o -1 se non trovato/partita non valida.
 */
int find_opponent_fd(const GameInfo* game, int player_fd);

/**
 * @brief Resetta completamente uno slot nell'array `games` allo stato EMPTY.
 * NON blocca i mutex. Deve essere chiamata all'interno di una sezione critica.
 * @param game_idx L'indice dello slot da resettare.
 */
void reset_game_slot_to_empty_unsafe(int game_idx);


/**
 * @brief Gestisce le azioni necessarie quando un giocatore lascia una partita
 *        (per disconnessione o comando QUIT).
 * Aggiorna lo stato della partita, notifica l'avversario (se presente),
 * e potenzialmente resetta lo slot della partita.
 * Assume che i mutex `client_list_mutex` e `game_list_mutex` siano GIA' BLOCCATI
 * dal chiamante.
 * @param game_idx L'indice della partita nell'array `games`.
 * @param leaving_client_fd Il file descriptor del client che sta uscendo.
 * @param leaving_client_name Il nome del client che sta uscendo (per i log).
 * @return true se lo slot della partita è stato resettato (diventato EMPTY), false altrimenti.
 */
bool handle_player_leaving_game(int game_idx, int leaving_client_fd, const char* leaving_client_name);


/**
 * @brief Invia lo stato attuale della board e la notifica del turno
 *        ai giocatori coinvolti in una partita.
 * Assume che game_list_mutex sia bloccato.
 * @param game_idx Indice della partita.
 */
void broadcast_game_state(int game_idx);

#endif // GAME_LOGIC_H