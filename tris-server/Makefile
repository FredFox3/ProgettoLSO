CC = gcc
CFLAGS = -g -Wall -Wextra -std=c11 -pthread
LDFLAGS = -pthread

SRCS = server.c globals.c utils.c game_logic.c protocol.c client_handler.c
OBJS = $(SRCS:.c=.o)

TARGET = server

all: $(TARGET)

$(TARGET): $(OBJS)
	$(CC) $(OBJS) -o $(TARGET) $(LDFLAGS)
	@echo "Eseguibile $(TARGET) creato."

%.o: %.c *.h
	$(CC) $(CFLAGS) -c $< -o $@

clean:
	rm -f $(OBJS) $(TARGET) core.*
	@echo "File compilati rimossi."

.PHONY: all clean