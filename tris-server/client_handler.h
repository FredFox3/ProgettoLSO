#ifndef CLIENT_HANDLER_H
#define CLIENT_HANDLER_H

#include <pthread.h> // Per PTHREAD_FUNC

// Tipo puntatore a funzione per pthread_create
typedef void *(*PTHREAD_FUNC)(void *);

/**
 * @brief Funzione eseguita da un thread per gestire la connessione di un singolo client.
 * Legge i comandi, li passa alle funzioni di processamento del protocollo,
 * e gestisce la disconnessione del client.
 * @param arg Puntatore a un intero contenente l'indice del client nell'array `clients`.
 *            La memoria puntata da arg viene liberata da questa funzione.
 * @return NULL
 */
void *handle_client(void *arg);

#endif // CLIENT_HANDLER_H