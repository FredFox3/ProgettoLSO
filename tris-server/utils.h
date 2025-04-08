#ifndef UTILS_H
#define UTILS_H

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/time.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h> // Per send, MSG_NOSIGNAL
#include <stdbool.h>    // Per bool nel prototipo send_to_client

// --- Macro per Logging ---
void get_timestamp(char *buffer, size_t len);

#define LOG(...) do { \
    char timestamp[30]; \
    get_timestamp(timestamp, sizeof(timestamp)); \
    printf("%s - ", timestamp); \
    printf(__VA_ARGS__); \
    fflush(stdout); \
} while(0)

#define LOG_PERROR(msg) do { \
    char timestamp[30]; \
    get_timestamp(timestamp, sizeof(timestamp)); \
    fprintf(stderr, "%s - ERROR: ", timestamp); \
    fflush(stderr); \
    perror(msg); \
} while(0)

// --- Funzioni di Utilità ---

/**
 * @brief Invia un messaggio a un client in modo sicuro.
 * Gestisce EPIPE e altri errori comuni, loggando eventuali problemi.
 * @param client_fd File descriptor del client.
 * @param message Messaggio da inviare (stringa C null-terminated).
 * @return true se l'invio è andato a buon fine (o il client era già disconnesso),
 *         false se si è verificato un errore critico di invio.
 */
bool send_to_client(int client_fd, const char* message);

#endif // UTILS_H