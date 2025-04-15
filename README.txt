PROGETTO TRIS CLIENT-SERVER (C Server + JavaFX Client)

Questo progetto implementa un gioco del Tris multiplayer con un server sviluppato in C e un client grafico sviluppato in JavaFX. Il server è containerizzato utilizzando Docker per facilitarne l'esecuzione.

====================
PREREQUISITI
====================

Prima di iniziare, assicurati di avere installato il seguente software:

1.  Docker e Docker Compose: Necessari per costruire ed eseguire l'immagine del server C.
2.  Java Development Kit (JDK): Versione 21.
3.  JavaFX SDK: Versione 21.
4.  Maven

====================
BUILD ED ESECUZIONE
====================

--------------------
Server (Docker)
--------------------

Il server C viene compilato ed eseguito all'interno di un container Docker.

1.  Apri un terminale o prompt dei comandi.
2.  Naviga nella directory "tris-server/" del progetto:
    cd Tris/tris-server
3.  Costruisci ed esegui il container Docker usando Docker Compose:
    docker-compose up --build

--------------------
Client (JavaFX)
--------------------

Per eseguire il client JavaFX:

1.  Compila il codice Java: Naviga nella directory tris-client ed esegui mvn clean install.
2.  Esegui l'applicazione: Naviga nella directory /tris-client ed esegui questo comando: java --module-path "C:\Users\user\.openjfx\javafx-sdk-21.0.6\lib" --add-modules
javafx.controls,javafx.fxml,javafx.graphics,javafx.base -jar target/tris-client-1.0-SNAPSHOT.jar. (dove al posto di user, andrà l'username dell'utente in uso).

====================
STOP
====================

1.  Server:
    - Se hai avviato con "docker-compose up" nel terminale, premi Ctrl+C nel terminale dove è in esecuzione.
    - Se hai avviato con "docker-compose up -d", usa il comando "docker-compose down" nella directory "tris-server/". Questo fermerà e rimuoverà il container.
2.  Client:
    - Chiudi semplicemente la finestra dell'applicazione JavaFX oppure Ctrl+C nel terminale del client. Questo invierà un segnale di disconnessione al server.