#include "types.h"
#include "utils.h"
#include "client_handler.h"
#include "protocol.h"
#include "game_logic.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <signal.h>
#include <pthread.h>

void handle_signal(int signal) {

    char msg[] = "\nSegnale ricevuto, avvio spegnimento...\n";
    write(STDOUT_FILENO, msg, strlen(msg));
    keep_running = 0;

    if (server_fd != -1) {

    }
}

void cleanup_server() {
    LOG("Pulizia risorse server...\n");

    if (server_fd != -1) {
        LOG("Chiusura socket di ascolto del server fd %d\n", server_fd);
        close(server_fd);
        server_fd = -1;
    }

    pthread_mutex_lock(&client_list_mutex);
    LOG("Chiusura connessioni client attive...\n");
    for (int i = 0; i < MAX_TOTAL_CLIENTS; i++) {
        if (clients[i].active && clients[i].fd != -1) {
            LOG("Chiusura connessione per indice client %d (fd %d, nome: '%s')\n",
                i, clients[i].fd, clients[i].name[0] ? clients[i].name : "N/A");

            send_to_client(clients[i].fd, "NOTIFY:SERVER_SHUTDOWN\n");

            close(clients[i].fd);
            clients[i].fd = -1;


        }

    }
    pthread_mutex_unlock(&client_list_mutex);



    LOG("Distruzione mutex...\n");
    pthread_mutex_destroy(&client_list_mutex);
    pthread_mutex_destroy(&game_list_mutex);

    LOG("Pulizia server completata.\n");
}

int main() {
    struct sockaddr_in address;
    int opt = 1;
    int addrlen = sizeof(address);

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = handle_signal;

    if (sigaction(SIGINT, &action, NULL) < 0 || sigaction(SIGTERM, &action, NULL) < 0) {
         perror("sigaction fallito");
         exit(EXIT_FAILURE);
    }

    signal(SIGPIPE, SIG_IGN);

    LOG("Inizializzazione strutture dati server...\n");
    pthread_mutex_lock(&client_list_mutex);
    for (int i = 0; i < MAX_TOTAL_CLIENTS; ++i) {
        clients[i].active = false;
        clients[i].fd = -1;
        clients[i].state = CLIENT_STATE_CONNECTED;
        clients[i].game_id = 0;
        clients[i].name[0] = '\0';

    }
    pthread_mutex_unlock(&client_list_mutex);

    pthread_mutex_lock(&game_list_mutex);
    for (int i = 0; i < MAX_GAMES; ++i) {
        reset_game_slot_to_empty_unsafe(i);
    }
    pthread_mutex_unlock(&game_list_mutex);


    LOG("Impostazione socket server...\n");
    if ((server_fd = socket(AF_INET, SOCK_STREAM, 0)) == 0) {
        LOG_PERROR("creazione socket fallita");
        exit(EXIT_FAILURE);
    }

    if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt))) {

        LOG_PERROR("setsockopt(SO_REUSEADDR) fallito");
    }



    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(PORT);

    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        LOG_PERROR("bind fallito");
        close(server_fd);
        exit(EXIT_FAILURE);
    }

    if (listen(server_fd, MAX_TOTAL_CLIENTS) < 0) {
        LOG_PERROR("listen fallito");
        close(server_fd);
        exit(EXIT_FAILURE);
    }

    LOG("Server in ascolto sulla porta %d... (Max Client Connessi: %d, Max Partite: %d)\n", PORT, MAX_TOTAL_CLIENTS, MAX_GAMES);

    while (keep_running) {
        int new_socket = accept(server_fd, (struct sockaddr *)&address, (socklen_t*)&addrlen);

        if (new_socket < 0) {
            if (!keep_running && (errno == EINTR || errno == EBADF)) {

                LOG("Loop di accept interrotto da segnale di spegnimento o socket chiuso.\n");
            } else if (errno == EINTR) {
                 LOG("accept() interrotto da segnale, riprovo...\n");
                 continue;
            } else if (keep_running) {

                 LOG_PERROR("accept fallito");

            }
             if (!keep_running) break;
            continue;
        }

        LOG("Nuova connessione accettata, assegno fd %d\n", new_socket);

        pthread_mutex_lock(&client_list_mutex);
        int client_index = -1;
        for (int i = 0; i < MAX_TOTAL_CLIENTS; ++i) {
            if (!clients[i].active) {
                client_index = i;
                break;
            }
        }

        if (client_index != -1) {

            clients[client_index].active = true;
            clients[client_index].fd = new_socket;
            clients[client_index].state = CLIENT_STATE_CONNECTED;
            clients[client_index].game_id = 0;
            clients[client_index].name[0] = '\0';

            int *p_client_index = malloc(sizeof(int));
             if (p_client_index == NULL) {
                 LOG_PERROR("Allocazione memoria per argomento thread fallita");
                 close(new_socket);
                 clients[client_index].active = false;
                 clients[client_index].fd = -1;
                 pthread_mutex_unlock(&client_list_mutex);
                 continue;
             }
            *p_client_index = client_index;

            pthread_t thread_id;
            if (pthread_create(&thread_id, NULL, handle_client, p_client_index) != 0) {
                LOG_PERROR("pthread_create fallita");
                close(new_socket);
                clients[client_index].active = false;
                clients[client_index].fd = -1;
                 free(p_client_index);
            } else {

                pthread_detach(thread_id);
                 LOG("Client assegnato all'indice %d, thread gestore %lu creato.\n", client_index, (unsigned long)thread_id);
            }
        } else {

            LOG("Server pieno (raggiunto MAX_TOTAL_CLIENTS), rifiuto connessione fd %d\n", new_socket);

            send(new_socket, ERR_SERVER_FULL_SLOTS, strlen(ERR_SERVER_FULL_SLOTS), MSG_NOSIGNAL);
            close(new_socket);
        }
        pthread_mutex_unlock(&client_list_mutex);

    }

    LOG("Spegnimento server in corso...\n");
    cleanup_server();

    LOG("Server terminato correttamente.\n");
    return 0;
}