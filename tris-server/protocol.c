#include "protocol.h"
#include "utils.h"
#include "game_logic.h" // Necessario per manipolare giochi e usare helpers
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Definizioni costanti stringhe (comandi/risposte/errori specifici del protocollo)
const char* CMD_GET_NAME = "CMD:GET_NAME\n";
const char* CMD_NAME_PREFIX = "NAME ";
const char* CMD_LIST = "LIST";
const char* CMD_CREATE = "CREATE";
const char* CMD_JOIN_REQUEST_PREFIX = "JOIN_REQUEST ";
const char* CMD_ACCEPT_PREFIX = "ACCEPT ";
const char* CMD_REJECT_PREFIX = "REJECT ";
const char* CMD_MOVE_PREFIX = "MOVE ";
const char* CMD_QUIT = "QUIT";

const char* RESP_NAME_OK = "RESP:NAME_OK\n";
const char* RESP_CREATED_FMT = "RESP:CREATED %d\n";
const char* RESP_GAMES_LIST_PREFIX = "RESP:GAMES_LIST;";
const char* RESP_REQUEST_SENT_FMT = "RESP:REQUEST_SENT %d\n";
const char* RESP_JOIN_ACCEPTED_FMT = "RESP:JOIN_ACCEPTED %d %c %s\n"; // game_id, symbol, opponent_name
const char* RESP_REJECT_OK_FMT = "RESP:REJECT_OK %s\n"; // rejected_name
const char* RESP_JOIN_REJECTED_FMT = "RESP:JOIN_REJECTED %d %s\n"; // game_id, creator_name
const char* RESP_QUIT_OK = "RESP:QUIT_OK Back to lobby.\n";

const char* NOTIFY_JOIN_REQUEST_FMT = "NOTIFY:JOIN_REQUEST %s\n"; // joiner_name
const char* NOTIFY_GAME_START_FMT = "NOTIFY:GAME_START %d %c %s\n"; // game_id, symbol, opponent_name
const char* NOTIFY_REQUEST_CANCELLED_FMT = "NOTIFY:REQUEST_CANCELLED %s left\n"; // joiner_name

const char* ERR_SERVER_FULL_GAMES = "ERROR:Server full, cannot create game (no game slots)\n";
const char* ERR_SERVER_FULL_SLOTS = "ERROR:Server is full. Try again later.\n"; // Per connessioni rifiutate
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
const char* ERR_NOT_PLAYING = "ERROR:Command only available in PLAYING state\n";
const char* ERR_UNKNOWN_COMMAND_FMT = "ERROR:Unknown command or invalid state (%d) for command: %s\n";


// --- Implementazioni Funzioni Processamento Comandi ---

void process_name_command(int client_idx, const char* name_arg) {
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !name_arg) return;

    pthread_mutex_lock(&client_list_mutex);
    // Assicurati che il client sia attivo e nello stato corretto
    if (clients[client_idx].active && clients[client_idx].state == CLIENT_STATE_CONNECTED) {
        strncpy(clients[client_idx].name, name_arg, MAX_NAME_LEN - 1);
        clients[client_idx].name[MAX_NAME_LEN - 1] = '\0'; // Assicura null termination
        clients[client_idx].state = CLIENT_STATE_LOBBY;
        int fd = clients[client_idx].fd; // Leggi fd mentre hai il lock
        LOG("Client fd %d (idx %d) registered name: %s\n", fd, client_idx, clients[client_idx].name);
        pthread_mutex_unlock(&client_list_mutex); // Sblocca prima di inviare

        send_to_client(fd, RESP_NAME_OK);
    } else {
        // Stato errato o client non attivo - logga e sblocca
        int fd = clients[client_idx].active ? clients[client_idx].fd : -1;
        ClientState state = clients[client_idx].active ? clients[client_idx].state : -1;
        pthread_mutex_unlock(&client_list_mutex);
        LOG("Client fd %d (idx %d) sent NAME in wrong state (%d) or inactive.\n", fd, client_idx, state);
        // Non inviare errori qui, il client non se lo aspetta se ha già un nome o è disconnesso
    }
}

void process_list_command(int client_idx) {
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS) return;

    char response[BUFFER_SIZE * 2]; // Buffer ampio per la lista
    response[0] = '\0';
    strcat(response, RESP_GAMES_LIST_PREFIX);
    bool first_game = true;
    int client_fd = -1; // Per inviare fuori dal lock

    pthread_mutex_lock(&client_list_mutex); // Leggi fd
    if(!clients[client_idx].active) {
        pthread_mutex_unlock(&client_list_mutex);
        return; // Client non attivo
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
                default:                    strcpy(state_str, "Unknown"); break; // Non dovrebbe succedere
            }

            char game_info[150];
            // Formato: ID,NomeP1,Stato[,NomeP2] (NomeP2 solo se In Progress o Finished)
            snprintf(game_info, sizeof(game_info), "%d,%s,%s",
                     games[i].id,
                     games[i].player1_name[0] ? games[i].player1_name : "?", // Nome P1 (o ? se vuoto)
                     state_str);

            // Aggiungi P2 se esiste
            if ((games[i].state == GAME_STATE_IN_PROGRESS || games[i].state == GAME_STATE_FINISHED) && games[i].player2_name[0]) {
                strncat(game_info, ",", sizeof(game_info) - strlen(game_info) - 1);
                strncat(game_info, games[i].player2_name, sizeof(game_info) - strlen(game_info) - 1);
            }

            strncat(response, game_info, sizeof(response) - strlen(response) - 1);
            first_game = false;
        }
    }
    pthread_mutex_unlock(&game_list_mutex);

    strncat(response, "\n", sizeof(response) - strlen(response) - 1); // Aggiungi newline finale
    send_to_client(client_fd, response);
}

void process_create_command(int client_idx) {
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS) return;

     char response[BUFFER_SIZE];
     int game_idx = -1;
     int created_game_id = -1;
     int client_fd = -1;
     bool success = false;

     pthread_mutex_lock(&client_list_mutex);
     pthread_mutex_lock(&game_list_mutex);

     // Verifica stato client e ottieni fd/nome
     if (!clients[client_idx].active || clients[client_idx].state != CLIENT_STATE_LOBBY) {
         client_fd = clients[client_idx].active ? clients[client_idx].fd : -1;
         ClientState state = clients[client_idx].active ? clients[client_idx].state : -1;
         snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_NOT_IN_LOBBY);
         LOG("Client %s (fd %d, idx %d) tried CREATE in wrong state (%d).\n",
             clients[client_idx].name, client_fd, client_idx, state);
         goto create_cleanup; // Salta all'unlock e invio
     }
     client_fd = clients[client_idx].fd; // Ora sappiamo che è attivo

     // Trova uno slot partita vuoto
     for (int i = 0; i < MAX_GAMES; ++i) {
         if (games[i].state == GAME_STATE_EMPTY) {
             game_idx = i;
             break;
         }
     }

     if (game_idx != -1) {
         // Inizializza la nuova partita
         LOG("Using empty game slot %d for new game.\n", game_idx);
         created_game_id = next_game_id++; // Assegna ID univoco
         games[game_idx].id = created_game_id;
         games[game_idx].state = GAME_STATE_WAITING;
         init_board(games[game_idx].board);
         games[game_idx].player1_fd = client_fd;
         games[game_idx].player2_fd = -1;
         games[game_idx].current_turn_fd = -1; // Nessuno gioca ancora
         strncpy(games[game_idx].player1_name, clients[client_idx].name, MAX_NAME_LEN);
         games[game_idx].player1_name[MAX_NAME_LEN - 1] = '\0';
         games[game_idx].player2_name[0] = '\0';
         games[game_idx].pending_joiner_fd = -1;
         games[game_idx].pending_joiner_name[0] = '\0';

         // Aggiorna stato client
         clients[client_idx].state = CLIENT_STATE_WAITING;
         clients[client_idx].game_id = created_game_id;

         snprintf(response, sizeof(response), RESP_CREATED_FMT, created_game_id);
         LOG("Client %s (fd %d) created game %d in slot index %d\n",
             clients[client_idx].name, client_fd, created_game_id, game_idx);
         success = true;

     } else {
         // Nessuno slot disponibile
         snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_SERVER_FULL_GAMES);
         LOG("Cannot create game for %s (fd %d), server full (MAX_GAMES reached or no EMPTY slots)\n",
             clients[client_idx].name, client_fd);
     }

create_cleanup:
     pthread_mutex_unlock(&game_list_mutex);
     pthread_mutex_unlock(&client_list_mutex);

     if (client_fd >= 0) { // Invia la risposta (successo o errore)
        send_to_client(client_fd, response);
     }
}

void process_join_request_command(int client_idx, const char* game_id_str) {
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !game_id_str) return;

    int game_id_to_join = atoi(game_id_str);
    int game_idx = -1;
    int creator_fd = -1;
    int requester_fd = -1;
    char requester_name[MAX_NAME_LEN] = {0};
    char response_requester[BUFFER_SIZE]; // Risposta per chi fa la richiesta
    char notify_creator[BUFFER_SIZE];   // Notifica per il creatore
    bool proceed = false;

    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);

    // 1. Controlla lo stato del richiedente e ottieni info
    if (!clients[client_idx].active || clients[client_idx].state != CLIENT_STATE_LOBBY) {
        requester_fd = clients[client_idx].active ? clients[client_idx].fd : -1;
        snprintf(response_requester, sizeof(response_requester), "%s%s\n", RESP_ERROR_PREFIX, ERR_NOT_IN_LOBBY);
        LOG("JOIN_REQUEST failed: Client idx %d (fd %d) not in LOBBY state.\n", client_idx, requester_fd);
        goto join_req_cleanup;
    }
    requester_fd = clients[client_idx].fd;
    strncpy(requester_name, clients[client_idx].name, MAX_NAME_LEN -1);

    // 2. Trova la partita richiesta
    game_idx = find_game_index_unsafe(game_id_to_join);
    if (game_idx == -1) {
        snprintf(response_requester, sizeof(response_requester), "%s%s %d\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_FOUND, game_id_to_join);
        LOG("JOIN_REQUEST failed: Game ID %d not found for request from %s (fd %d).\n", game_id_to_join, requester_name, requester_fd);
        goto join_req_cleanup;
    }

    // 3. Controlla lo stato della partita e altre condizioni
    GameInfo* game = &games[game_idx];
    creator_fd = game->player1_fd; // fd del creatore

    if (game->state != GAME_STATE_WAITING) {
        const char* err_msg = (game->state == GAME_STATE_IN_PROGRESS) ? ERR_GAME_ALREADY_STARTED :
                              (game->state == GAME_STATE_FINISHED) ? ERR_GAME_FINISHED :
                              ERR_GAME_NOT_WAITING;
        snprintf(response_requester, sizeof(response_requester), "%s%s\n", RESP_ERROR_PREFIX, err_msg);
        LOG("JOIN_REQUEST failed: Game %d (idx %d) not in WAITING state (state=%d) for request from %s.\n", game_id_to_join, game_idx, game->state, requester_name);
        goto join_req_cleanup;
    }
    if (creator_fd == requester_fd) {
        snprintf(response_requester, sizeof(response_requester), "%s%s\n", RESP_ERROR_PREFIX, ERR_CANNOT_JOIN_OWN_GAME);
        LOG("JOIN_REQUEST failed: %s (fd %d) tried to join own game %d.\n", requester_name, requester_fd, game_id_to_join);
        goto join_req_cleanup;
    }
    if (game->pending_joiner_fd != -1) {
        snprintf(response_requester, sizeof(response_requester), "%s%s\n", RESP_ERROR_PREFIX, ERR_ALREADY_PENDING);
        LOG("JOIN_REQUEST failed: Game %d already has pending joiner %s (fd %d). Request from %s rejected.\n",
            game_id_to_join, game->pending_joiner_name, game->pending_joiner_fd, requester_name);
        goto join_req_cleanup;
    }
     if (creator_fd < 0) { // Creatore disconnesso?
         snprintf(response_requester, sizeof(response_requester), "%s%s\n", RESP_ERROR_PREFIX, ERR_CREATOR_LEFT);
         LOG("JOIN_REQUEST failed: Creator for game %d (idx %d) has invalid fd %d.\n", game_id_to_join, game_idx, creator_fd);
         goto join_req_cleanup;
     }

    // 4. Se tutto ok, imposta richiesta pendente e prepara notifiche/risposte
    game->pending_joiner_fd = requester_fd;
    strncpy(game->pending_joiner_name, requester_name, MAX_NAME_LEN -1);
    game->pending_joiner_name[MAX_NAME_LEN - 1] = '\0';

    // Prepara notifica per il creatore
    snprintf(notify_creator, sizeof(notify_creator), NOTIFY_JOIN_REQUEST_FMT, requester_name);
    // Prepara risposta per il richiedente
    snprintf(response_requester, sizeof(response_requester), RESP_REQUEST_SENT_FMT, game_id_to_join);

    LOG("Client %s (fd %d) requested to join game %d (idx %d). Notifying creator %s (fd %d)\n",
        requester_name, requester_fd, game_id_to_join, game_idx,
        game->player1_name, creator_fd);

    proceed = true; // Segnala che dobbiamo inviare fuori dal lock

join_req_cleanup:
    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    // Invia messaggi fuori dai lock
    if (proceed) {
        // Tenta di notificare il creatore
        if (!send_to_client(creator_fd, notify_creator)) {
            // Fallimento notifica: annulla la richiesta pendente
            LOG("Failed to send join request notification to creator fd %d. Cancelling request.\n", creator_fd);
            pthread_mutex_lock(&game_list_mutex); // Richiede lock per modificare game
            // Controlla se la situazione è ancora la stessa prima di resettare
            if (games[game_idx].pending_joiner_fd == requester_fd) {
                games[game_idx].pending_joiner_fd = -1;
                games[game_idx].pending_joiner_name[0] = '\0';
            }
            pthread_mutex_unlock(&game_list_mutex);

            // Invia errore al richiedente
            snprintf(response_requester, sizeof(response_requester), "%s%s\n", RESP_ERROR_PREFIX, ERR_CREATOR_LEFT); // Riutilizza l'errore
            send_to_client(requester_fd, response_requester);
        } else {
            // Notifica inviata con successo, invia conferma al richiedente
            send_to_client(requester_fd, response_requester);
        }
    } else if (requester_fd >= 0) {
        // Se non si procede, invia l'errore preparato al richiedente
        send_to_client(requester_fd, response_requester);
    }
}


void process_accept_command(int client_idx, const char* accepted_player_name) {
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !accepted_player_name) return;

    int creator_idx = client_idx;
    int creator_fd = -1;
    int joiner_idx = -1;
    int joiner_fd = -1;
    int game_idx = -1;
    int current_game_id = -1;

    char response_creator[BUFFER_SIZE];
    char response_joiner[BUFFER_SIZE];
    char notify_creator_start[BUFFER_SIZE];
    char notify_joiner_start[BUFFER_SIZE];
    bool start_game = false;

    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);

    // 1. Verifica stato creatore e ottieni fd/game_id
    if (!clients[creator_idx].active || clients[creator_idx].state != CLIENT_STATE_WAITING) {
        creator_fd = clients[creator_idx].active ? clients[creator_idx].fd : -1;
        snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, ERR_NOT_WAITING);
        LOG("ACCEPT failed: Client idx %d (fd %d) not in WAITING state.\n", creator_idx, creator_fd);
        goto accept_cleanup;
    }
    creator_fd = clients[creator_idx].fd;
    current_game_id = clients[creator_idx].game_id;

    // 2. Trova la partita
    game_idx = find_game_index_unsafe(current_game_id);
    if (game_idx == -1) {
        snprintf(response_creator, sizeof(response_creator), "%s%s %d\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_FOUND, current_game_id);
        LOG("ACCEPT failed: Game ID %d (from client %d) not found.\n", current_game_id, creator_idx);
        goto accept_cleanup;
    }

    // 3. Verifica stato partita e richiesta pendente
    GameInfo* game = &games[game_idx];
    if (game->state != GAME_STATE_WAITING) { // Doppia verifica
        snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_WAITING);
        LOG("ACCEPT failed: Game %d (idx %d) not in WAITING state (state=%d).\n", current_game_id, game_idx, game->state);
        goto accept_cleanup;
    }
     // Verifica che chi accetta sia il creatore effettivo
     if (game->player1_fd != creator_fd) {
        snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, "You are not the creator of this game.");
        LOG("ACCEPT failed: Client %d (fd %d) tried to accept for game %d but is not creator (P1 fd: %d).\n", creator_idx, creator_fd, current_game_id, game->player1_fd);
        goto accept_cleanup;
     }
     // Verifica che ci sia un pending joiner e che il nome corrisponda
     if (game->pending_joiner_fd < 0 || strcmp(game->pending_joiner_name, accepted_player_name) != 0) {
         snprintf(response_creator, sizeof(response_creator), "%s%s '%s'.\n", RESP_ERROR_PREFIX, ERR_NO_PENDING_REQUEST, accepted_player_name);
         LOG("ACCEPT failed: Game %d has no pending request from '%s' (pending: %s, fd: %d).\n",
             current_game_id, accepted_player_name, game->pending_joiner_name, game->pending_joiner_fd);
         goto accept_cleanup;
     }
     joiner_fd = game->pending_joiner_fd;

    // 4. Trova l'indice del joiner e verifica il suo stato (deve essere ancora in LOBBY)
    joiner_idx = find_client_index_unsafe(joiner_fd);
    if (joiner_idx == -1 || !clients[joiner_idx].active /*|| clients[joiner_idx].state != CLIENT_STATE_LOBBY*/) {
        // Tolto controllo LOBBY: potrebbe essersi disconnesso e riconnesso, o essere in attesa altrove.
        // L'importante è che sia `active`. Se il suo stato è cambiato, pazienza.
         snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, ERR_JOINER_LEFT);
         LOG("ACCEPT failed: Joiner '%s' (fd %d, idx %d) not found or inactive.\n", accepted_player_name, joiner_fd, joiner_idx);
         // Resetta la richiesta pendente
         game->pending_joiner_fd = -1;
         game->pending_joiner_name[0] = '\0';
         goto accept_cleanup;
    }

    // 5. Se tutto ok, aggiorna stato partita e stati client
    LOG("Creator %s (fd %d) ACCEPTED join request from %s (fd %d) for game %d\n",
       clients[creator_idx].name, creator_fd, accepted_player_name, joiner_fd, current_game_id);

    game->player2_fd = joiner_fd;
    strncpy(game->player2_name, accepted_player_name, MAX_NAME_LEN -1);
    game->player2_name[MAX_NAME_LEN - 1] = '\0';
    game->state = GAME_STATE_IN_PROGRESS;
    game->current_turn_fd = creator_fd; // P1 (X) inizia
    game->pending_joiner_fd = -1; // Rimuovi richiesta pendente
    game->pending_joiner_name[0] = '\0';

    // Aggiorna stato di entrambi i client
    clients[creator_idx].state = CLIENT_STATE_PLAYING;
    // clients[creator_idx].game_id è già corretto
    clients[joiner_idx].state = CLIENT_STATE_PLAYING;
    clients[joiner_idx].game_id = current_game_id; // Associa il joiner alla partita

    // 6. Prepara notifiche e risposte
    // Risposta al joiner: accettato, simbolo O, nome avversario (creatore)
    snprintf(response_joiner, sizeof(response_joiner), RESP_JOIN_ACCEPTED_FMT,
             current_game_id, 'O', clients[creator_idx].name);
    // Notifica inizio partita al joiner: ID, simbolo O, nome avversario (creatore)
    snprintf(notify_joiner_start, sizeof(notify_joiner_start), NOTIFY_GAME_START_FMT,
             current_game_id, 'O', clients[creator_idx].name);

    // Notifica inizio partita al creatore: ID, simbolo X, nome avversario (joiner)
    snprintf(notify_creator_start, sizeof(notify_creator_start), NOTIFY_GAME_START_FMT,
             current_game_id, 'X', accepted_player_name);
    // Risposta al creatore (implicita, l'inizio partita è la conferma)

    start_game = true; // Segnala di inviare e broadcastare lo stato

accept_cleanup:
    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    // Invia messaggi fuori dai lock
    if (start_game) {
        send_to_client(joiner_fd, response_joiner);      // Conferma accettazione al joiner
        send_to_client(joiner_fd, notify_joiner_start);  // Notifica inizio al joiner
        send_to_client(creator_fd, notify_creator_start);// Notifica inizio al creatore

        // Invia stato iniziale board e turno
        pthread_mutex_lock(&game_list_mutex); // Lock necessario per broadcast
        broadcast_game_state(game_idx);
        pthread_mutex_unlock(&game_list_mutex);

    } else if (creator_fd >= 0) {
        // Se non si è avviata la partita, invia l'errore preparato al creatore
        send_to_client(creator_fd, response_creator);
    }
}


void process_reject_command(int client_idx, const char* rejected_player_name) {
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !rejected_player_name) return;

     int creator_idx = client_idx;
     int creator_fd = -1;
     int joiner_fd = -1;
     int game_idx = -1;
     int current_game_id = -1;

     char response_creator[BUFFER_SIZE];
     char response_joiner[BUFFER_SIZE];
     bool rejected = false;

     pthread_mutex_lock(&client_list_mutex);
     pthread_mutex_lock(&game_list_mutex);

     // 1. Verifica stato creatore e ottieni fd/game_id
     if (!clients[creator_idx].active || clients[creator_idx].state != CLIENT_STATE_WAITING) {
         creator_fd = clients[creator_idx].active ? clients[creator_idx].fd : -1;
         snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, ERR_NOT_WAITING);
         LOG("REJECT failed: Client idx %d (fd %d) not in WAITING state.\n", creator_idx, creator_fd);
         goto reject_cleanup;
     }
     creator_fd = clients[creator_idx].fd;
     current_game_id = clients[creator_idx].game_id;

     // 2. Trova la partita
     game_idx = find_game_index_unsafe(current_game_id);
     if (game_idx == -1) {
         snprintf(response_creator, sizeof(response_creator), "%s%s %d\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_FOUND, current_game_id);
         LOG("REJECT failed: Game ID %d (from client %d) not found.\n", current_game_id, creator_idx);
         goto reject_cleanup;
     }

     // 3. Verifica stato partita e richiesta pendente
     GameInfo* game = &games[game_idx];
     if (game->state != GAME_STATE_WAITING) {
         snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_WAITING);
         LOG("REJECT failed: Game %d (idx %d) not in WAITING state (state=%d).\n", current_game_id, game_idx, game->state);
         goto reject_cleanup;
     }
      if (game->player1_fd != creator_fd) {
         snprintf(response_creator, sizeof(response_creator), "%s%s\n", RESP_ERROR_PREFIX, "You are not the creator of this game.");
         LOG("REJECT failed: Client %d (fd %d) tried to reject for game %d but is not creator (P1 fd: %d).\n", creator_idx, creator_fd, current_game_id, game->player1_fd);
         goto reject_cleanup;
      }
      if (game->pending_joiner_fd < 0 || strcmp(game->pending_joiner_name, rejected_player_name) != 0) {
          snprintf(response_creator, sizeof(response_creator), "%s%s '%s'.\n", RESP_ERROR_PREFIX, ERR_NO_PENDING_REQUEST, rejected_player_name);
          LOG("REJECT failed: Game %d has no pending request from '%s' (pending: %s, fd: %d).\n",
              current_game_id, rejected_player_name, game->pending_joiner_name, game->pending_joiner_fd);
          goto reject_cleanup;
      }
      joiner_fd = game->pending_joiner_fd;

     // 4. Se tutto ok, rimuovi richiesta pendente e prepara risposte
     LOG("Creator %s (fd %d) REJECTED join request from %s (fd %d) for game %d\n",
         clients[creator_idx].name, creator_fd, rejected_player_name, joiner_fd, current_game_id);

     game->pending_joiner_fd = -1;
     game->pending_joiner_name[0] = '\0';

     // Prepara risposta per il joiner rifiutato
     snprintf(response_joiner, sizeof(response_joiner), RESP_JOIN_REJECTED_FMT,
              current_game_id, clients[creator_idx].name);

     // Prepara risposta per il creatore
     snprintf(response_creator, sizeof(response_creator), RESP_REJECT_OK_FMT, rejected_player_name);

     rejected = true;

 reject_cleanup:
     pthread_mutex_unlock(&game_list_mutex);
     pthread_mutex_unlock(&client_list_mutex);

     // Invia messaggi fuori dai lock
     if (rejected) {
         send_to_client(joiner_fd, response_joiner);
         send_to_client(creator_fd, response_creator);
     } else if (creator_fd >= 0) {
         send_to_client(creator_fd, response_creator); // Invia errore al creatore se reject non è avvenuto
     }
}


void process_move_command(int client_idx, const char* move_args) {
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !move_args) return;

    int r, c;
    int player_fd = -1;
    int game_idx = -1;
    int current_game_id = -1;
    char response[BUFFER_SIZE]; // Per errori
    bool move_made = false;
    bool game_over = false;
    char game_over_status_self[20] = {0}; // "WIN", "LOSE", "DRAW" per chi ha mosso
    char game_over_status_opponent[20] = {0};

    // 1. Parsa le coordinate
    if (sscanf(move_args, "%d %d", &r, &c) != 2) {
        pthread_mutex_lock(&client_list_mutex);
        player_fd = clients[client_idx].active ? clients[client_idx].fd : -1;
        pthread_mutex_unlock(&client_list_mutex);
        if (player_fd >= 0) {
             snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_INVALID_MOVE_FORMAT);
             send_to_client(player_fd, response);
        }
        return;
    }

    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);

    // 2. Verifica stato client e ottieni fd/game_id
    if (!clients[client_idx].active || clients[client_idx].state != CLIENT_STATE_PLAYING) {
        player_fd = clients[client_idx].active ? clients[client_idx].fd : -1;
        snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_NOT_PLAYING);
        LOG("MOVE failed: Client idx %d (fd %d) not in PLAYING state.\n", client_idx, player_fd);
        goto move_cleanup;
    }
    player_fd = clients[client_idx].fd;
    current_game_id = clients[client_idx].game_id;

    // 3. Trova la partita
    game_idx = find_game_index_unsafe(current_game_id);
    if (game_idx == -1) {
        snprintf(response, sizeof(response), "%s%s %d\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_FOUND, current_game_id);
        LOG("MOVE failed: Game ID %d (from client %d) not found.\n", current_game_id, client_idx);
        goto move_cleanup;
    }

    // 4. Verifica stato partita e turno
    GameInfo* game = &games[game_idx];
    if (game->state != GAME_STATE_IN_PROGRESS) {
        snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_GAME_NOT_IN_PROGRESS);
        LOG("MOVE failed: Game %d (idx %d) not IN_PROGRESS (state=%d).\n", current_game_id, game_idx, game->state);
        goto move_cleanup;
    }
    if (game->current_turn_fd != player_fd) {
        snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_NOT_YOUR_TURN);
        LOG("MOVE failed: Not turn for player %s (fd %d) in game %d.\n", clients[client_idx].name, player_fd, current_game_id);
        goto move_cleanup;
    }

    // 5. Valida la mossa (coordinate e cella vuota)
    if (r < 0 || r > 2 || c < 0 || c > 2) {
        snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_INVALID_MOVE_BOUNDS);
        LOG("MOVE failed: Invalid coords (%d,%d) from %s in game %d.\n", r, c, clients[client_idx].name, current_game_id);
        goto move_cleanup;
    }
    if (game->board[r][c] != CELL_EMPTY) {
        snprintf(response, sizeof(response), "%s%s\n", RESP_ERROR_PREFIX, ERR_INVALID_MOVE_OCCUPIED);
        LOG("MOVE failed: Cell (%d,%d) occupied in game %d (attempt by %s).\n", r, c, current_game_id, clients[client_idx].name);
        goto move_cleanup;
    }

    // 6. Esegui la mossa
    Cell player_symbol = (player_fd == game->player1_fd) ? CELL_X : CELL_O;
    game->board[r][c] = player_symbol;
    LOG("Player %s (fd %d, %c) made move %d,%d in game %d\n",
        clients[client_idx].name, player_fd, (player_symbol == CELL_X ? 'X' : 'O'), r, c, current_game_id);
    move_made = true;

    // 7. Controlla fine partita (vittoria o pareggio)
    int opponent_fd = find_opponent_fd(game, player_fd);
    int opponent_idx = find_client_index_unsafe(opponent_fd);

    if (check_winner(game->board, player_symbol)) {
        game_over = true;
        game->state = GAME_STATE_FINISHED;
        game->current_turn_fd = -1;
        strcpy(game_over_status_self, "WIN");
        strcpy(game_over_status_opponent, "LOSE");
        LOG("Game %d finished. Winner: %s. State set to FINISHED.\n", current_game_id, clients[client_idx].name);
    } else if (board_full(game->board)) {
        game_over = true;
        game->state = GAME_STATE_FINISHED;
        game->current_turn_fd = -1;
        strcpy(game_over_status_self, "DRAW");
        strcpy(game_over_status_opponent, "DRAW");
        LOG("Game %d finished. Draw. State set to FINISHED.\n", current_game_id);
    } else {
        // Partita non finita, passa il turno
        game->current_turn_fd = opponent_fd; // Passa turno all'avversario
    }

    // 8. Se la partita è finita, aggiorna lo stato dei client a LOBBY
    if (game_over) {
        clients[client_idx].state = CLIENT_STATE_LOBBY;
        clients[client_idx].game_id = 0;
        if (opponent_idx != -1 && clients[opponent_idx].active) {
            clients[opponent_idx].state = CLIENT_STATE_LOBBY;
            clients[opponent_idx].game_id = 0;
             LOG("Opponent %s (fd %d) returned to LOBBY after game over.\n", clients[opponent_idx].name, opponent_fd);
        }
         LOG("Player %s (fd %d) returned to LOBBY after game over.\n", clients[client_idx].name, player_fd);
    }


move_cleanup:
    // Sblocca mutex PRIMA di inviare notifiche
    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    // Invia notifiche fuori dai lock
    if (move_made) {
        // Invia sempre lo stato aggiornato della board
        pthread_mutex_lock(&game_list_mutex); // Richiede lock per leggere stato board aggiornato
        broadcast_game_state(game_idx); // Invia board e (se non finita) notifica turno
        pthread_mutex_unlock(&game_list_mutex);

        // Se la partita è finita, invia notifiche GAMEOVER
        if (game_over) {
            char notify_self[BUFFER_SIZE], notify_opponent[BUFFER_SIZE];
            snprintf(notify_self, sizeof(notify_self), "NOTIFY:GAMEOVER %s\n", game_over_status_self);
            snprintf(notify_opponent, sizeof(notify_opponent), "NOTIFY:GAMEOVER %s\n", game_over_status_opponent);

            send_to_client(player_fd, notify_self);
            if (opponent_fd >= 0) { // Invia solo se l'avversario è valido
                send_to_client(opponent_fd, notify_opponent);
            }
        }
    } else if (player_fd >= 0) {
        // Se la mossa non è stata fatta, invia l'errore preparato
        send_to_client(player_fd, response);
    }
}


bool process_quit_command(int client_idx) {
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS) return false;

    int client_fd = -1;
    char client_name[MAX_NAME_LEN];
    ClientState current_state;
    int current_game_id;
    bool request_disconnect = false; // True se QUIT implica disconnessione

    // 1. Leggi stato attuale del client
    pthread_mutex_lock(&client_list_mutex);
    if (!clients[client_idx].active) {
        pthread_mutex_unlock(&client_list_mutex);
        LOG("QUIT command from inactive client index %d\n", client_idx);
        return true; // Inattivo, consideralo come disconnesso
    }
    client_fd = clients[client_idx].fd;
    strncpy(client_name, clients[client_idx].name, MAX_NAME_LEN);
    client_name[MAX_NAME_LEN - 1] = '\0';
    current_state = clients[client_idx].state;
    current_game_id = clients[client_idx].game_id;
    pthread_mutex_unlock(&client_list_mutex); // Rilascia subito

    LOG("Client %s (fd %d, idx %d) sent QUIT. State: %d, Game ID: %d\n",
        client_name, client_fd, client_idx, current_state, current_game_id);

    // 2. Gestisci QUIT in base allo stato
    if (current_state == CLIENT_STATE_PLAYING || current_state == CLIENT_STATE_WAITING) {
        // Client sta lasciando una partita o l'attesa
        LOG("Client %s is leaving game %d.\n", client_name, current_game_id);

        pthread_mutex_lock(&client_list_mutex); // Richiede entrambi i lock per
        pthread_mutex_lock(&game_list_mutex);   // handle_player_leaving_game

        int game_idx = find_game_index_unsafe(current_game_id);
        if (game_idx != -1) {
            // Chiama la logica centralizzata per gestire l'uscita
            handle_player_leaving_game(game_idx, client_fd, client_name);
        } else {
            LOG("QUIT: Game %d not found for client %s, but client state was %d.\n",
                current_game_id, client_name, current_state);
        }

        // Indipendentemente da tutto, riporta il client alla LOBBY
        if (clients[client_idx].active) { // Ricontrolla attività prima di modificare
            clients[client_idx].state = CLIENT_STATE_LOBBY;
            clients[client_idx].game_id = 0;
             LOG("Client %s (fd %d) moved to LOBBY state after QUIT.\n", client_name, client_fd);
        }

        pthread_mutex_unlock(&game_list_mutex);
        pthread_mutex_unlock(&client_list_mutex);

        // Invia una conferma al client (opzionale)
        send_to_client(client_fd, RESP_QUIT_OK);
        request_disconnect = false; // Non disconnettere, è tornato in lobby

    } else {
        // Client è in LOBBY o CONNECTED. QUIT qui significa disconnettersi.
        LOG("Client %s (fd %d) sent QUIT from state %d. Interpreting as disconnect request.\n",
            client_name, client_fd, current_state);
        request_disconnect = true;
    }

    return request_disconnect;
}

void send_unknown_command_error(int client_idx, const char* received_command, ClientState current_state) {
     if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS) return;

     int client_fd = -1;
     pthread_mutex_lock(&client_list_mutex);
     if (clients[client_idx].active) {
         client_fd = clients[client_idx].fd;
     }
     pthread_mutex_unlock(&client_list_mutex);

     if (client_fd >= 0) {
         char error_resp[BUFFER_SIZE];
         snprintf(error_resp, sizeof(error_resp), ERR_UNKNOWN_COMMAND_FMT,
                  current_state, received_command ? received_command : "<empty>");
        strncat(error_resp, "\n", sizeof(error_resp) - strlen(error_resp) -1); // Aggiunge \n se non presente
         send_to_client(client_fd, error_resp);
     }
}