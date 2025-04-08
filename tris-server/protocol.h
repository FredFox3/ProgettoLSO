#ifndef PROTOCOL_H
#define PROTOCOL_H

#include "types.h" // Per ClientInfo, GameInfo, stati, ecc.

// --- Costanti Stringhe Comandi/Risposte ---
// (Alcune sono definite in game_logic.h/c se più legate alla logica interna)
extern const char* CMD_GET_NAME;
extern const char* CMD_NAME_PREFIX;
extern const char* CMD_LIST;
extern const char* CMD_CREATE;
extern const char* CMD_JOIN_REQUEST_PREFIX;
extern const char* CMD_ACCEPT_PREFIX;
extern const char* CMD_REJECT_PREFIX;
extern const char* CMD_MOVE_PREFIX;
extern const char* CMD_QUIT;

extern const char* RESP_NAME_OK;
extern const char* RESP_CREATED_FMT;
extern const char* RESP_GAMES_LIST_PREFIX;
extern const char* RESP_REQUEST_SENT_FMT;
extern const char* RESP_JOIN_ACCEPTED_FMT;
// ... altre risposte (REJECT_OK, JOIN_REJECTED, etc.)

extern const char* ERR_SERVER_FULL_GAMES;
extern const char* ERR_SERVER_FULL_SLOTS;
extern const char* ERR_INVALID_MOVE_FORMAT;
extern const char* ERR_INVALID_MOVE_BOUNDS;
extern const char* ERR_INVALID_MOVE_OCCUPIED;
extern const char* ERR_NOT_YOUR_TURN;
extern const char* ERR_GAME_NOT_FOUND;
extern const char* ERR_NOT_IN_LOBBY;
extern const char* ERR_NOT_WAITING;
// ... altri errori

// --- Funzioni Processamento Comandi ---
// Ogni funzione prende l'indice del client che ha inviato il comando
// e l'argomento del comando (se presente).

void process_name_command(int client_idx, const char* name_arg);
void process_list_command(int client_idx);
void process_create_command(int client_idx);
void process_join_request_command(int client_idx, const char* game_id_str);
void process_accept_command(int client_idx, const char* accepted_player_name);
void process_reject_command(int client_idx, const char* rejected_player_name);
void process_move_command(int client_idx, const char* move_args);

/**
 * @brief Gestisce il comando QUIT inviato da un client.
 * Determina se il client sta lasciando una partita o semplicemente la lobby.
 * Chiama handle_player_leaving_game se necessario.
 * Aggiorna lo stato del client.
 * @param client_idx Indice del client che ha inviato QUIT.
 * @return true se il comando QUIT implica una richiesta di disconnessione
 *         (es. QUIT dalla lobby), false altrimenti (es. QUIT da partita/attesa).
 */
bool process_quit_command(int client_idx);

/**
 * @brief Invia un messaggio di errore generico per comandi sconosciuti o fuori stato.
 * @param client_idx Indice del client.
 * @param received_command Il comando ricevuto che ha causato l'errore.
 * @param current_state Lo stato del client al momento della ricezione.
 */
void send_unknown_command_error(int client_idx, const char* received_command, ClientState current_state);


#endif // PROTOCOL_H