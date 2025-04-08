#include "types.h"
#include "utils.h"
#include "client_handler.h" // Per handle_client
#include "protocol.h" // Per ERR_SERVER_FULL_SLOTS
#include "game_logic.h" // Per reset_game_slot_to_empty (se necessario in cleanup)

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <signal.h>
#include <pthread.h>

// --- Gestore Segnali ---
void handle_signal(int signal) {
    // Evita chiamate non async-signal-safe qui (come printf/LOG)
    // Scrivi direttamente a stderr/stdout se necessario o imposta solo il flag
    char msg[] = "\nSignal received, initiating shutdown...\n";
    write(STDOUT_FILENO, msg, strlen(msg)); // write è async-signal-safe
    keep_running = 0;

    // Tentativo leggero di sbloccare accept (potrebbe non essere necessario con SO_REUSEADDR)
    // shutdown() non è garantito async-signal-safe, ma spesso funziona.
    // close() è async-signal-safe.
    if (server_fd != -1) {
        // Non chiamare shutdown qui, potrebbe causare problemi.
        // Lascia che il loop principale esca a causa di keep_running=0
        // e gestisca la chiusura in cleanup().
        // close(server_fd); // Anche close qui può essere rischioso se main sta usando fd
        // La cosa più sicura è impostare solo keep_running e lasciare fare al main loop.
    }
}

// --- Funzione di Cleanup ---
void cleanup_server() {
    LOG("Cleaning up server resources...\n");

    // 1. Chiudi il socket del server per non accettare nuove connessioni
    if (server_fd != -1) {
        LOG("Closing server listening socket fd %d\n", server_fd);
        close(server_fd);
        server_fd = -1; // Segna come chiuso
    }

    // 2. Notifica e chiudi le connessioni client esistenti
    pthread_mutex_lock(&client_list_mutex);
    LOG("Closing active client connections...\n");
    for (int i = 0; i < MAX_TOTAL_CLIENTS; i++) {
        if (clients[i].active && clients[i].fd != -1) {
            LOG("Closing connection for client index %d (fd %d, name: '%s')\n",
                i, clients[i].fd, clients[i].name[0] ? clients[i].name : "N/A");
            // Invia notifica (best effort)
            send_to_client(clients[i].fd, "NOTIFY:SERVER_SHUTDOWN\n");
            // Chiudi il socket
            close(clients[i].fd);
            clients[i].fd = -1; // Marca come chiuso nello slot
            // Non modificare active qui, i thread potrebbero essere ancora in cleanup.
            // Potremmo voler usare pthread_cancel o pthread_join se avessimo tenuto gli ID
            // ma pthread_detach rende questo difficile. Lasciamo che i thread terminino.
        }
         // Marcare comunque come inattivo per coerenza finale? No, lascia che il thread lo faccia.
         //clients[i].active = false;
    }
    pthread_mutex_unlock(&client_list_mutex);

    // Ritardo opzionale per permettere ai thread di finire il cleanup?
    // sleep(1);

    // 3. Distruggi i mutex
    LOG("Destroying mutexes...\n");
    pthread_mutex_destroy(&client_list_mutex);
    pthread_mutex_destroy(&game_list_mutex);

    LOG("Server cleanup complete.\n");
}

// --- Funzione Main ---
int main() {
    struct sockaddr_in address;
    int opt = 1;
    int addrlen = sizeof(address);

    // 1. Imposta gestori segnali
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = handle_signal;
    // SA_RESTART non è necessario qui, vogliamo che accept venga interrotto
    if (sigaction(SIGINT, &action, NULL) < 0 || sigaction(SIGTERM, &action, NULL) < 0) {
         perror("sigaction failed");
         exit(EXIT_FAILURE);
    }
    // Ignora SIGPIPE per evitare crash se si scrive su socket chiuso
    signal(SIGPIPE, SIG_IGN);

    // 2. Inizializza strutture dati globali
    LOG("Initializing server data structures...\n");
    pthread_mutex_lock(&client_list_mutex);
    for (int i = 0; i < MAX_TOTAL_CLIENTS; ++i) {
        clients[i].active = false;
        clients[i].fd = -1;
        clients[i].state = CLIENT_STATE_CONNECTED; // Stato iniziale
        clients[i].game_id = 0;
        clients[i].name[0] = '\0';
        // clients[i].thread_id = 0; // Se usata
    }
    pthread_mutex_unlock(&client_list_mutex);

    pthread_mutex_lock(&game_list_mutex);
    for (int i = 0; i < MAX_GAMES; ++i) {
        reset_game_slot_to_empty_unsafe(i); // Usa la funzione helper per inizializzare
    }
    pthread_mutex_unlock(&game_list_mutex);


    // 3. Crea e configura il socket del server
    LOG("Setting up server socket...\n");
    if ((server_fd = socket(AF_INET, SOCK_STREAM, 0)) == 0) {
        LOG_PERROR("socket failed");
        exit(EXIT_FAILURE);
    }

    // Permetti riutilizzo indirizzo (utile per riavvii rapidi)
    if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt))) {
        // Non fatale, ma logga
        LOG_PERROR("setsockopt(SO_REUSEADDR) failed");
    }
    // Opzione SO_REUSEPORT (meno comune, ma può essere utile in certi scenari)
    // if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEPORT, &opt, sizeof(opt))) {
    //     LOG_PERROR("setsockopt(SO_REUSEPORT) failed");
    // }


    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY; // Accetta connessioni su qualsiasi IP locale
    address.sin_port = htons(PORT);

    // 4. Associa il socket all'indirizzo e porta
    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        LOG_PERROR("bind failed");
        close(server_fd);
        exit(EXIT_FAILURE);
    }

    // 5. Metti il server in ascolto
    if (listen(server_fd, MAX_TOTAL_CLIENTS) < 0) { // La backlog queue può essere > MAX_TOTAL_CLIENTS
        LOG_PERROR("listen failed");
        close(server_fd);
        exit(EXIT_FAILURE);
    }

    LOG("Server listening on port %d... (Max Concurrent Clients: %d, Max Games: %d)\n", PORT, MAX_TOTAL_CLIENTS, MAX_GAMES);

    // --- Loop Principale Accettazione Connessioni ---
    while (keep_running) {
        int new_socket = accept(server_fd, (struct sockaddr *)&address, (socklen_t*)&addrlen);

        if (new_socket < 0) {
            if (!keep_running && (errno == EINTR || errno == EBADF)) {
                // Accettazione interrotta dal segnale di shutdown o socket chiuso
                LOG("Accept loop interrupted by shutdown signal or closed socket.\n");
            } else if (errno == EINTR) {
                 LOG("accept() interrupted by signal, retrying...\n");
                 continue; // Riprova ad accettare
            } else if (keep_running) {
                 // Errore di accept non dovuto a shutdown
                 LOG_PERROR("accept failed");
                 // Potrebbe essere un errore temporaneo o grave. Continuiamo?
                 // sleep(1); // Pausa prima di riprovare?
            }
             if (!keep_running) break; // Esce se il flag è stato impostato
            continue; // Torna all'inizio del while
        }

        LOG("New connection accepted, assigning fd %d\n", new_socket);

        // Trova uno slot client libero
        pthread_mutex_lock(&client_list_mutex);
        int client_index = -1;
        for (int i = 0; i < MAX_TOTAL_CLIENTS; ++i) {
            if (!clients[i].active) {
                client_index = i;
                break;
            }
        }

        if (client_index != -1) {
            // Slot trovato: inizializza e crea thread handler
            clients[client_index].active = true;
            clients[client_index].fd = new_socket;
            clients[client_index].state = CLIENT_STATE_CONNECTED; // Stato iniziale
            clients[client_index].game_id = 0;
            clients[client_index].name[0] = '\0';

            // Alloca memoria per passare l'indice al thread
            int *p_client_index = malloc(sizeof(int));
             if (p_client_index == NULL) {
                 LOG_PERROR("Failed to allocate memory for thread arg");
                 close(new_socket);
                 clients[client_index].active = false; // Libera lo slot
                 clients[client_index].fd = -1;
                 pthread_mutex_unlock(&client_list_mutex);
                 continue; // Prova ad accettare la prossima connessione
             }
            *p_client_index = client_index;

            pthread_t thread_id;
            if (pthread_create(&thread_id, NULL, handle_client, p_client_index) != 0) {
                LOG_PERROR("pthread_create failed");
                close(new_socket);
                clients[client_index].active = false; // Libera lo slot
                clients[client_index].fd = -1;
                 free(p_client_index); // Libera la memoria allocata
            } else {
                // clients[client_index].thread_id = thread_id; // Salva ID se necessario
                pthread_detach(thread_id); // Rende il thread indipendente
                 LOG("Client assigned to index %d, handler thread %lu created.\n", client_index, (unsigned long)thread_id);
            }
        } else {
            // Server pieno: rifiuta connessione
            LOG("Server full (MAX_TOTAL_CLIENTS reached), rejecting connection fd %d\n", new_socket);
            // Usa costante definita in protocol.h/c
            send(new_socket, ERR_SERVER_FULL_SLOTS, strlen(ERR_SERVER_FULL_SLOTS), MSG_NOSIGNAL);
            close(new_socket);
        }
        pthread_mutex_unlock(&client_list_mutex);

    } // Fine while(keep_running)

    // --- Shutdown ---
    LOG("Server shutting down...\n");
    cleanup_server(); // Chiama la funzione di pulizia

    LOG("Server exited gracefully.\n");
    return 0;
}