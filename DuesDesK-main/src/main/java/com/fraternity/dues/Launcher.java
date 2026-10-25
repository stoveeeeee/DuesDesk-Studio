package com.fraternity.dues;

import javafx.application.Application;

// Must NOT extend Application. If the main class does, JavaFX fails with
// "JavaFX runtime components are missing" when started from a fat jar.
public class Launcher {

    public static void main(String[] args) {
        // Lets the window drop the OS title bar (StageStyle.EXTENDED is a JavaFX 25 preview feature).
        System.setProperty("javafx.enablePreview", "true");
        System.setProperty("javafx.suppressPreviewWarning", "true");
        Application.launch(DesktopApp.class, args);
    }
}
