#include "client_handler.h"
#include "types.h"
#include "utils.h"
#include "protocol.h"
#include "game_logic.h" // Per cleanup e find_xxx_unsafe
#include <unistd.h> // Per read, close
#include <errno.h>  // Per errno
#include <string.h> // Per memset, strncmp, etc.
#include <stdlib.h> // Per free

void *handle_client(void *arg) {
    // Ottieni indice client e libera l'argomento allocato
    if (!arg) {
         LOG("Error: NULL argument passed to handle_client thread.\n");
         return NULL;
    }
    int client_index = *((int*)arg);
    free(arg);

    // Controlli iniziali sull'indice
    if (client_index < 0 || client_index >= MAX_TOTAL_CLIENTS) {
         LOG("Error: Invalid client index %d received in handler thread.\n", client_index);
         return NULL;
    }

    int client_fd = -1;
    bool client_needs_name = false;

    // Ottieni fd e stato iniziale in modo sicuro
    pthread_mutex_lock(&client_list_mutex);
    if (clients[client_index].active) {
        client_fd = clients[client_index].fd;
        client_needs_name = (clients[client_index].state == CLIENT_STATE_CONNECTED);
    } else {
        // Il client è stato reso inattivo tra la creazione del thread e l'acquisizione del lock? Molto raro.
         LOG("Error: Client index %d is inactive when thread started.\n", client_index);
         pthread_mutex_unlock(&client_list_mutex);
         return NULL;
    }
    pthread_mutex_unlock(&client_list_mutex);

    // Se non siamo riusciti a ottenere un fd valido (dovrebbe essere impossibile se active=true)
    if (client_fd < 0) {
         LOG("Error: Could not get valid fd for active client index %d.\n", client_index);
         pthread_mutex_lock(&client_list_mutex);
         clients[client_index].active = false; // Marca come inattivo per sicurezza
         pthread_mutex_unlock(&client_list_mutex);
         return NULL;
    }

    LOG("Thread started for client fd %d (index %d)\n", client_fd, client_index);

    // Invia richiesta nome se necessario
    if (client_needs_name) {
        if (!send_to_client(client_fd, CMD_GET_NAME)) {
            // Se fallisce l'invio iniziale, probabilmente il client si è già disconnesso
            LOG("Initial GET_NAME send failed for fd %d. Assuming disconnect.\n", client_fd);
            goto cleanup_connection; // Salta direttamente al cleanup
        }
    }

    char buffer[BUFFER_SIZE];
    bool client_connected = true; // Flag per controllare il loop principale

    // Loop principale di lettura comandi
    while (client_connected && keep_running) {
        memset(buffer, 0, BUFFER_SIZE);
        ssize_t bytes_read = read(client_fd, buffer, BUFFER_SIZE - 1);

        // Gestione disconnessione o errore di lettura
        if (bytes_read <= 0) {
            if (bytes_read == 0) {
                // Disconnessione pulita
                LOG("Client fd %d (idx %d) disconnected gracefully.\n", client_fd, client_index);
            } else if (errno == EINTR && !keep_running) {
                // Interrotto da segnale di shutdown
                LOG("Read interrupted by shutdown signal for client fd %d.\n", client_fd);
            } else if (errno == EINTR) {
                 // Interrotto da altro segnale, continua il loop
                 LOG("Read interrupted by signal (errno %d), continuing...\n", errno);
                 continue;
            } else {
                 // Errore di lettura
                 LOG("Read error from client fd %d (idx %d): %s (errno %d)\n", client_fd, client_index, strerror(errno), errno);
            }
            client_connected = false; // Causa l'uscita dal loop
            break; // Esce immediatamente
        }

        // Rimuovi newline e carriage return dal buffer
        buffer[strcspn(buffer, "\r\n")] = 0;

        // Log del messaggio ricevuto (ottieni nome e stato attuali)
        ClientState current_state;
        char current_name[MAX_NAME_LEN];
        pthread_mutex_lock(&client_list_mutex);
        if (clients[client_index].active) {
            current_state = clients[client_index].state;
            strncpy(current_name, clients[client_index].name, MAX_NAME_LEN);
            current_name[MAX_NAME_LEN - 1] = '\0';
        } else {
             // Client diventato inattivo mentre era nel loop? Raro.
             LOG("Client idx %d became inactive during read loop.\n", client_index);
             pthread_mutex_unlock(&client_list_mutex);
             client_connected = false;
             break;
        }
        pthread_mutex_unlock(&client_list_mutex);

        LOG("Received from fd %d (idx %d, name '%s', state %d): [%s]\n",
            client_fd, client_index, current_name[0] ? current_name : "(no name yet)", current_state, buffer);

        // --- Parsing e Dispatch dei Comandi ---
        // Usa le funzioni definite in protocol.c
        if (strncmp(buffer, CMD_NAME_PREFIX, strlen(CMD_NAME_PREFIX)) == 0 && current_state == CLIENT_STATE_CONNECTED) {
            process_name_command(client_index, buffer + strlen(CMD_NAME_PREFIX));
        } else if (strcmp(buffer, CMD_LIST) == 0 && current_state == CLIENT_STATE_LOBBY) {
            process_list_command(client_index);
        } else if (strcmp(buffer, CMD_CREATE) == 0 && current_state == CLIENT_STATE_LOBBY) {
            process_create_command(client_index);
        } else if (strncmp(buffer, CMD_JOIN_REQUEST_PREFIX, strlen(CMD_JOIN_REQUEST_PREFIX)) == 0 && current_state == CLIENT_STATE_LOBBY) {
             process_join_request_command(client_index, buffer + strlen(CMD_JOIN_REQUEST_PREFIX));
        } else if (strncmp(buffer, CMD_ACCEPT_PREFIX, strlen(CMD_ACCEPT_PREFIX)) == 0 && current_state == CLIENT_STATE_WAITING) {
             process_accept_command(client_index, buffer + strlen(CMD_ACCEPT_PREFIX));
        } else if (strncmp(buffer, CMD_REJECT_PREFIX, strlen(CMD_REJECT_PREFIX)) == 0 && current_state == CLIENT_STATE_WAITING) {
             process_reject_command(client_index, buffer + strlen(CMD_REJECT_PREFIX));
        } else if (strncmp(buffer, CMD_MOVE_PREFIX, strlen(CMD_MOVE_PREFIX)) == 0 && current_state == CLIENT_STATE_PLAYING) {
             process_move_command(client_index, buffer + strlen(CMD_MOVE_PREFIX));
        } else if (strcmp(buffer, CMD_QUIT) == 0) {
            if (process_quit_command(client_index)) {
                 // process_quit ha indicato che questo QUIT significa disconnessione
                 LOG("QUIT processed as disconnect request for fd %d.\n", client_fd);
                 client_connected = false; // Esce dal loop
            }
             // Se ritorna false, il client è tornato in lobby, il loop continua
        }
        else {
            // Comando sconosciuto o non valido per lo stato attuale
            if (current_state != CLIENT_STATE_CONNECTED) { // Non inviare errori prima che il nome sia settato
                 LOG("Unknown command or invalid state for: [%s] from fd %d (state %d)\n", buffer, client_fd, current_state);
                 send_unknown_command_error(client_index, buffer, current_state);
            } else {
                 LOG("Received command [%s] before NAME from fd %d. Ignoring.\n", buffer, client_fd);
            }
        }

        // Piccolo delay per evitare busy-waiting aggressivo (opzionale)
        // usleep(10000); // 10 ms

    } // Fine while(client_connected && keep_running)


cleanup_connection:
    // --- Cleanup alla disconnessione (o uscita dal loop) ---
    LOG("Cleaning up client connection (fd %d, index %d)\n", client_fd, client_index);

    // Chiudi il socket PRIMA di modificare strutture dati condivise
    if (client_fd >= 0) {
        close(client_fd);
        // Non resettare client_fd qui, serve per handle_player_leaving_game
    }

    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);

    // Ottieni ID partita e nome PRIMA di resettare lo slot client
    int game_id_leaving = clients[client_index].active ? clients[client_index].game_id : 0;
    char disconnecting_client_name[MAX_NAME_LEN];
    if (clients[client_index].active) {
        strncpy(disconnecting_client_name, clients[client_index].name, MAX_NAME_LEN);
    } else {
        disconnecting_client_name[0] = '\0'; // Già inattivo
    }
    disconnecting_client_name[MAX_NAME_LEN-1] = '\0';

    // Resetta completamente lo slot del client
    if (clients[client_index].active) { // Solo se era ancora attivo
        LOG("Marking client index %d (name '%s') as inactive.\n", client_index, disconnecting_client_name[0] ? disconnecting_client_name : "N/A");
        clients[client_index].active = false;
        clients[client_index].fd = -1; // Segna fd come non valido nello slot
        clients[client_index].game_id = 0;
        clients[client_index].state = CLIENT_STATE_CONNECTED; // Stato iniziale per il prossimo uso
        clients[client_index].name[0] = '\0';
        // clients[client_index].thread_id = 0; // Se usata
    }

    // Gestisci l'impatto sulla partita (se il client era in una partita)
    if (game_id_leaving > 0) {
        int game_idx = find_game_index_unsafe(game_id_leaving);
        if (game_idx != -1) {
             // Usa la funzione helper centralizzata. Passa l'fd originale (ora chiuso).
             // handle_player_leaving_game userà questo fd per trovare il giocatore nella struttura GameInfo.
             LOG("Client was in game %d (idx %d), handling departure...\n", game_id_leaving, game_idx);
             handle_player_leaving_game(game_idx, client_fd, disconnecting_client_name);
        } else {
             LOG("Client '%s' (prev fd %d) disconnected, associated game ID %d not found.\n",
                 disconnecting_client_name, client_fd, game_id_leaving);
        }
    } else {
         LOG("Client '%s' (prev fd %d) disconnected from lobby/connecting state.\n",
             disconnecting_client_name[0] ? disconnecting_client_name : "N/A", client_fd);
    }

    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    LOG("Thread finished for connection (prev fd %d, index %d)\n", client_fd, client_index);
    return NULL;
}