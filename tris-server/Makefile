# Semplice Makefile per compilare il server TicTacToe

# Compilatore C
CC = gcc
# Flags di compilazione: -g per debug, -Wall per warning, -pthread per pthreads
CFLAGS = -g -Wall -Wextra -std=c11 -pthread
# Flags del linker: -pthread per linkare la libreria pthreads
LDFLAGS = -pthread

# File sorgente (.c)
SRCS = server.c globals.c utils.c game_logic.c protocol.c client_handler.c
# File oggetto (.o) corrispondenti
OBJS = $(SRCS:.c=.o)

# Nome dell'eseguibile finale
TARGET = server

# Regola di default: crea l'eseguibile
all: $(TARGET)

# Regola per creare l'eseguibile: collega i file oggetto
$(TARGET): $(OBJS)
	$(CC) $(OBJS) -o $(TARGET) $(LDFLAGS)
	@echo "Eseguibile $(TARGET) creato."

# Regola generica per creare i file oggetto (.o) dai file sorgente (.c)
# Compila ogni .c includendo le dipendenze dagli header (semplificato qui)
# Per dipendenze header corrette, servirebbe 'gcc -MM'
%.o: %.c *.h
	$(CC) $(CFLAGS) -c $< -o $@

# Regola per pulire i file generati
clean:
	rm -f $(OBJS) $(TARGET) core.*
	@echo "File compilati rimossi."

# Phony targets (non rappresentano file)
.PHONY: all clean