#include "client_handler.h"
#include "types.h"
#include "utils.h"
#include "protocol.h"      // Include dichiarazioni dei comandi, risposte, errori
#include "game_logic.h"    // Include funzioni di logica di gioco (es. per quit)
#include <unistd.h>        // Per read, close
#include <errno.h>         // Per errno
#include <string.h>        // Per memset, strncmp, strncpy, strlen, strcspn
#include <stdlib.h>        // Per free

// La funzione handle_client, che gira in un thread separato per ogni client.
void *handle_client(void *arg) {
    if (!arg) {
        LOG("Error: handle_client received NULL argument.\n");
        return NULL;
    }
    // Ottieni l'indice del client e libera l'argomento allocato dinamicamente
    int client_index = *((int*)arg);
    free(arg); // Libera la memoria allocata in main

    if (client_index < 0 || client_index >= MAX_TOTAL_CLIENTS) {
        LOG("Error: handle_client received invalid client index %d.\n", client_index);
        return NULL;
    }

    // Ottieni il file descriptor e verifica se serve il nome, sotto lock
    int client_fd = -1;
    bool client_needs_name = false;
    pthread_mutex_lock(&client_list_mutex);
    if (clients[client_index].active) {
        client_fd = clients[client_index].fd;
        // Serve il nome solo se è appena connesso
        client_needs_name = (clients[client_index].state == CLIENT_STATE_CONNECTED);
    } else {
        LOG("Warning: handle_client started for already inactive client index %d.\n", client_index);
        pthread_mutex_unlock(&client_list_mutex);
        return NULL;
    }
    pthread_mutex_unlock(&client_list_mutex);

    if (client_fd < 0) {
         LOG("Error: handle_client fd is < 0 for active client index %d.\n", client_index);
        return NULL;
    }

    LOG("Thread started for client fd %d (index %d)\n", client_fd, client_index);

    // Se serve il nome, richiedilo
    if (client_needs_name) {
        if (!send_to_client(client_fd, CMD_GET_NAME)) {
             LOG("Failed to send CMD_GET_NAME to client fd %d. Closing connection.\n", client_fd);
             goto cleanup_connection;
        }
    }

    char buffer[BUFFER_SIZE];
    bool client_connected = true;

    // Loop di lettura principale
    while (client_connected && keep_running) {
        memset(buffer, 0, BUFFER_SIZE);
        ssize_t bytes_read = read(client_fd, buffer, BUFFER_SIZE - 1);

        if (bytes_read == 0) {
            LOG("Client fd %d (index %d) disconnected gracefully (read 0 bytes).\n", client_fd, client_index);
            client_connected = false;
            break;
        } else if (bytes_read < 0) {
            if (errno == EINTR) { continue; }
            if (errno == ECONNRESET) {
                 LOG("Client fd %d (index %d) disconnected abruptly (ECONNRESET).\n", client_fd, client_index);
            } else {
                 LOG_PERROR("Read error");
                 fprintf(stderr, "    Client FD: %d, Index: %d\n", client_fd, client_index);
            }
            client_connected = false;
            break;
        }

        buffer[strcspn(buffer, "\r\n")] = 0;

        // Leggi stato e nome attuali del client sotto lock
        ClientState current_state;
        char current_name[MAX_NAME_LEN];
        pthread_mutex_lock(&client_list_mutex);
        if (clients[client_index].active) { // Ricontrolla se attivo
            current_state = clients[client_index].state;
            strncpy(current_name, clients[client_index].name, MAX_NAME_LEN-1);
            current_name[MAX_NAME_LEN-1]='\0';
        } else {
             LOG("Warning: Client index %d became inactive during state read (fd %d).\n", client_index, client_fd);
            pthread_mutex_unlock(&client_list_mutex);
            client_connected=false;
            break;
        }
        pthread_mutex_unlock(&client_list_mutex);

        // Log messaggio ricevuto
        LOG("Received from fd %d (idx %d, name '%s', state %d): [%s]\n",
            client_fd, client_index, current_name[0]?current_name:"(no name yet)", current_state, buffer);

        // --- Parsing comandi ---
        if (strncmp(buffer, CMD_NAME_PREFIX, strlen(CMD_NAME_PREFIX)) == 0 && current_state == CLIENT_STATE_CONNECTED) {
            process_name_command(client_index, buffer + strlen(CMD_NAME_PREFIX));
        }
        // *** QUESTA È LA RIGA MODIFICATA ***
        else if (strcmp(buffer, CMD_LIST) == 0 && (current_state == CLIENT_STATE_LOBBY || current_state == CLIENT_STATE_WAITING)) {
            LOG("Processing LIST command for client %d in state %d.\n", client_index, current_state); // Log aggiunto
            process_list_command(client_index);
        }
        else if (strcmp(buffer, CMD_CREATE) == 0 && current_state == CLIENT_STATE_LOBBY) {
            process_create_command(client_index);
        } else if (strncmp(buffer, CMD_JOIN_REQUEST_PREFIX, strlen(CMD_JOIN_REQUEST_PREFIX)) == 0 && current_state == CLIENT_STATE_LOBBY) {
             process_join_request_command(client_index, buffer + strlen(CMD_JOIN_REQUEST_PREFIX));
        } else if (strncmp(buffer, CMD_ACCEPT_PREFIX, strlen(CMD_ACCEPT_PREFIX)) == 0 && current_state == CLIENT_STATE_WAITING) {
             process_accept_command(client_index, buffer + strlen(CMD_ACCEPT_PREFIX));
        } else if (strncmp(buffer, CMD_REJECT_PREFIX, strlen(CMD_REJECT_PREFIX)) == 0 && current_state == CLIENT_STATE_WAITING) {
             process_reject_command(client_index, buffer + strlen(CMD_REJECT_PREFIX));
        } else if (strncmp(buffer, CMD_MOVE_PREFIX, strlen(CMD_MOVE_PREFIX)) == 0 && current_state == CLIENT_STATE_PLAYING) {
             process_move_command(client_index, buffer + strlen(CMD_MOVE_PREFIX));
        } else if ((strcmp(buffer, CMD_REMATCH_YES) == 0 || strcmp(buffer, CMD_REMATCH_NO) == 0)) { // REMATCH
            if (current_state == CLIENT_STATE_PLAYING) {
                process_rematch_command(client_index, buffer);
            } else {
                send_unknown_command_error(client_index, buffer, current_state);
            }
        } else if (strcmp(buffer, CMD_QUIT) == 0) { // QUIT
            if (process_quit_command(client_index)) {
                 client_connected = false;
            }
        } else { // Comando sconosciuto o non valido nello stato attuale
            if (current_state != CLIENT_STATE_CONNECTED) {
                send_unknown_command_error(client_index, buffer, current_state);
            }
        }
    } // fine while (client_connected && keep_running)

cleanup_connection:
    // Salva l'fd che questo thread stava gestendo *prima* di bloccare i mutex
    int fd_handled_by_this_thread = client_fd;
    LOG("Cleaning up client connection initiated for fd %d (index %d)\n", fd_handled_by_this_thread, client_index);

    // Chiudi il socket PRIMA di modificare le strutture dati condivise
    if (fd_handled_by_this_thread >= 0) {
         close(fd_handled_by_this_thread);
    }

    // Blocca mutex per aggiornare strutture dati condivise
    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);

    int game_id_leaving = 0;
    char disconnecting_client_name[MAX_NAME_LEN] = {0}; // Inizializza a stringa vuota

    // Controlla se lo slot client è ancora assegnato a QUESTO fd
    if (clients[client_index].active && clients[client_index].fd == fd_handled_by_this_thread) {
         game_id_leaving = clients[client_index].game_id;
         strncpy(disconnecting_client_name, clients[client_index].name, MAX_NAME_LEN-1);
         disconnecting_client_name[MAX_NAME_LEN - 1] = '\0';

        // Resetta lo slot client
        clients[client_index].active = false;
        clients[client_index].fd = -1; // Fondamentale!
        clients[client_index].game_id = 0;
        clients[client_index].state = CLIENT_STATE_CONNECTED;
        clients[client_index].name[0] = '\0';
        // ... resetta eventuali altri campi specifici del client qui ...

         LOG("Marking client index %d (name '%s', prev_fd %d) as inactive.\n",
             client_index,
             disconnecting_client_name[0] ? disconnecting_client_name : "N/A",
             fd_handled_by_this_thread);
    } else {
         // Logga se lo slot era già stato pulito o assegnato ad un altro fd
         LOG("Skipping reset for client index %d: Slot inactive or fd mismatch (slot fd: %d, this thread fd: %d).\n",
             client_index, clients[client_index].fd, fd_handled_by_this_thread);
    }

    // Gestisci uscita da partita (se applicabile)
    if (game_id_leaving > 0) {
        int game_idx = find_game_index_unsafe(game_id_leaving);
        if (game_idx != -1) {
             LOG("Client '%s' (prev_fd %d) was in game %d (idx %d). Handling departure.\n",
                 disconnecting_client_name[0] ? disconnecting_client_name : "N/A",
                 fd_handled_by_this_thread,
                 game_id_leaving, game_idx);
             // Chiama la funzione che gestisce l'uscita (notifica avversario, etc.)
             // Passa l'fd originale che si è disconnesso
             handle_player_leaving_game(game_idx, fd_handled_by_this_thread, disconnecting_client_name);
        } else {
             LOG("Client '%s' (prev_fd %d) disconnected, but associated game ID %d not found.\n",
                 disconnecting_client_name[0] ? disconnecting_client_name : "N/A",
                 fd_handled_by_this_thread, game_id_leaving);
        }
    } else if (disconnecting_client_name[0] != '\0'){ // Log solo se il client era stato identificato
         LOG("Client '%s' (prev_fd %d) disconnected from non-game state.\n", disconnecting_client_name, fd_handled_by_this_thread);
    }

    // Rilascia i lock
    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    LOG("Thread finished for connection related to client index %d (prev_fd %d).\n", client_index, fd_handled_by_this_thread);
    return NULL; // Fine thread
}