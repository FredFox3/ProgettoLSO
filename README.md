# ProgettoLSO

**(Nota:** La struttura del client potrebbe variare leggermente a seconda di come è organizzato il progetto JavaFX, ad esempio se si usa Maven o Gradle, ma i componenti chiave .java, .fxml, .css saranno presenti.)

## Build ed Esecuzione

### Server (Docker)

Il server C viene compilato ed eseguito all'interno di un container Docker.

1.  **Apri un terminale o prompt dei comandi.**
2.  **Naviga nella directory `server/`** del progetto:
    ```bash
    cd percorso/del/progetto/server
    ```
3.  **Costruisci ed esegui il container Docker** usando Docker Compose:
    ```bash
    docker-compose up --build
    ```
    *   Il comando `--build` forza la ricostruzione dell'immagine se il codice C o il Dockerfile sono cambiati. Puoi ometterlo per avvii successivi se non ci sono state modifiche.
    *   Il server si metterà in ascolto sulla porta `12345`. Vedrai i log del server nel terminale.
    *   Per eseguire in background, usa `docker-compose up -d --build`. Per vedere i log, usa `docker-compose logs -f`.

### Client (JavaFX)

Il modo più semplice per eseguire il client JavaFX è tramite un IDE.

**1. Tramite IDE (IntelliJ IDEA, Eclipse, etc.):**

1.  Apri il progetto client (la directory `client/` o il progetto JavaFX specifico) nel tuo IDE.
2.  Assicurati che il progetto sia configurato correttamente con il JDK e JavaFX SDK (se necessario, configura le VM options per specificare il module-path e i moduli richiesti, vedi sotto).
3.  Trova la classe `Main.java` (in `org.trisclient.trisclient`).
4.  Esegui la classe `Main`.

**2. Tramite Riga di Comando (Se l'IDE non è disponibile):**

1.  **Compila il codice Java:** Naviga nella directory `client/src/main/java` (o appropriata) e compila (l'output andrà in una directory `classes` o simile):
    ```bash
    # Esempio (potrebbe richiedere aggiustamenti per classpath e JavaFX)
    javac --module-path /percorso/javafx-sdk-XX/lib --add-modules javafx.controls,javafx.fxml org/trisclient/trisclient/*.java -d ../../../classes
    ```
    *(Nota: La compilazione da riga di comando per JavaFX può essere complessa. L'uso di un IDE o di uno strumento di build come Maven/Gradle è fortemente consigliato.)*
2.  **Esegui l'applicazione:** Naviga nella directory genitore che contiene `classes` e `resources`.
    ```bash
    # Naviga alla root del progetto client (es. 'client/')
    java --module-path /percorso/javafx-sdk-XX/lib --add-modules javafx.controls,javafx.fxml -cp classes org.trisclient.trisclient.Main
    ```
    *   Sostituisci `/percorso/javafx-sdk-XX/lib` con il percorso effettivo alla directory `lib` del tuo JavaFX SDK.
    *   Assicurati che `-cp classes` (o il percorso corretto per i file compilati) sia specificato.

**(Importante sulle VM Options per JavaFX se non incluso nel JDK):**
Se stai usando un JDK > 11 e JavaFX SDK separato, potresti dover configurare le "VM Options" nell'IDE o nella riga di comando: --module-path "/percorso/assoluto/a/javafx-sdk-XX/lib" --add-modules javafx.controls,javafx.fxml


## Come Giocare

1.  **Avvia il Server** come descritto sopra.
2.  **Avvia il Client** come descritto sopra.
3.  **Inserisci un Nome:** Al primo avvio, il client ti chiederà di inserire un nome utente. Questo nome deve essere unico sul server.
4.  **Lobby:** Una volta connesso e accettato il nome, vedrai la lobby:
    *   Puoi **Creare una nuova partita**. Diventerai l'host (giocatore 'X') e attenderai un altro giocatore.
    *   Puoi **Aggiornare** la lista delle partite in attesa create da altri.
    *   Puoi **Unirti** a una partita esistente in stato "In attesa". Invierai una richiesta all'host.
5.  **Richieste di Partecipazione:**
    *   Se sei l'host, riceverai una notifica se qualcuno vuole unirsi. Puoi **Accettare** o **Rifiutare**.
    *   Se hai richiesto di unirti, attenderai la risposta dell'host.
6.  **In Partita:**
    *   La partita inizia quando l'host accetta un partecipante.
    *   La scacchiera viene mostrata. Il client indicherà di chi è il turno.
    *   Clicca su una cella vuota quando è il tuo turno.
    *   Il gioco termina con una vittoria, una sconfitta o un pareggio.
7.  **Fine Partita e Rivincita:**
    *   Al termine della partita, se hai vinto o pareggiato, ti verrà chiesta se vuoi una rivincita.
    *   Se vinci, puoi scegliere "Sì" per ospitare una nuova partita (l'avversario torna alla lobby) o "No" per tornare entrambi alla lobby.
    *   Se pareggi, entrambi i giocatori possono scegliere "Sì" o "No". Se entrambi dicono "Sì", la partita viene resettata e ricomincia. Se uno dice "No", entrambi tornano alla lobby.
    *   Se perdi, torni automaticamente alla lobby.
8.  **Abbandono:** Puoi cliccare su "Abbandona partita" durante il gioco per terminare la partita corrente e tornare alla lobby (l'avversario verrà notificato e vincerà/tornerà alla lobby).
9.  **Quit:** Chiudere la finestra del client invierà un comando `QUIT` al server (se in lobby/waiting/playing) o semplicemente chiuderà la connessione.

## Stop

1.  **Server:**
    *   Se hai avviato con `docker-compose up` nel terminale, premi `Ctrl+C` nel terminale dove è in esecuzione.
    *   Se hai avviato con `docker-compose up -d`, usa il comando `docker-compose down` nella directory `server/`. Questo fermerà e rimuoverà il container.
2.  **Client:**
    *   Chiudi semplicemente la finestra dell'applicazione JavaFX. Questo invierà un segnale di disconnessione al server (se connesso).