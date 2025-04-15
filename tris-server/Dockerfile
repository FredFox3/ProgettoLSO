FROM gcc:latest

WORKDIR /app

COPY . .

RUN make clean && make

EXPOSE 12345

CMD ["./server"]
