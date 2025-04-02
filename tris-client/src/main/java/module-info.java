module org.trisclient.trisclient {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires javafx.base;

    opens org.trisclient.trisclient to javafx.fxml;
    exports org.trisclient.trisclient;
}