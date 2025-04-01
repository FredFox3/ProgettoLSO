module org.trisclient.trisclient {
    requires javafx.controls;
    requires javafx.fxml;


    opens org.trisclient.trisclient to javafx.fxml;
    exports org.trisclient.trisclient;
}