package com.fraternity.dues;

import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Background;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.StageStyle;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

public class DesktopApp extends Application {

    static {
        // StageStyle.EXTENDED and HeaderBar (no OS title bar) are preview features in JavaFX 25.
        System.setProperty("javafx.enablePreview", "true");
        System.setProperty("javafx.suppressPreviewWarning", "true");
    }

    /** Height of the page header (logo bar) used before a page has loaded; matches .banner in fragments.html. */
    private static final double DEFAULT_HEADER_HEIGHT = 66;

    private ConfigurableApplicationContext ctx;
    private DesktopBridge bridge; // strong reference: JavaScript holds it only weakly

    @Override
    public void init() {
        // Runs off the UI thread. Blocks until Tomcat is up, so the server is
        // ready by the time start() loads the page.
        ctx = new SpringApplicationBuilder(App.class)
                .headless(false)
                .run(getParameters().getRaw().toArray(new String[0]));
    }

    @Override
    public void start(Stage stage) {
        String port = ctx.getEnvironment().getProperty("local.server.port", "8080");

        WebView web = new WebView();

        // WebView silently ignores confirm() and alert() unless handlers are set.
        // The dashboard uses confirm() for "send to all" and "delete member".
        web.getEngine().setConfirmHandler(msg -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.OK, ButtonType.CANCEL);
            alert.setHeaderText(null);
            alert.initOwner(stage);
            return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
        });
        web.getEngine().setOnAlert(event -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, event.getData(), ButtonType.OK);
            alert.setHeaderText(null);
            alert.initOwner(stage);
            alert.showAndWait();
        });

        bridge = new DesktopBridge(stage, ctx.getBean(Store.class), getHostServices());
        web.getEngine().getLoadWorker().stateProperty().addListener((observable, previous, state) -> {
            if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                ((netscape.javascript.JSObject) web.getEngine().executeScript("window")).setMember("desktopBridge", bridge);
            }
        });

        web.getEngine().load("http://localhost:" + port);

        // Hide the operating system's title bar so the page's own white header sits at the very top. The minimize,
        // maximize and close buttons stay (top right on Windows, top left on macOS), and an invisible HeaderBar laid
        // over the page header makes it the area that drags, snaps and double-click-maximizes the window.
        Parent root = web;
        try {
            HeaderBar dragArea = new HeaderBar();
            dragArea.setBackground(Background.EMPTY);
            dragArea.setMaxHeight(Region.USE_PREF_SIZE);
            dragArea.setPrefHeight(DEFAULT_HEADER_HEIGHT);
            stage.initStyle(StageStyle.EXTENDED);
            StackPane layers = new StackPane(web, dragArea);
            StackPane.setAlignment(dragArea, Pos.TOP_CENTER);
            root = layers;
            web.getEngine().getLoadWorker().stateProperty().addListener((observable, previous, state) -> {
                if (state == javafx.concurrent.Worker.State.SUCCEEDED) matchPageHeader(web, dragArea);
            });
        } catch (RuntimeException | LinkageError e) {
            // Extended windows are a JavaFX preview feature; without it the standard title bar stays.
            root = web;
        }

        stage.setTitle("DuesDesk");
        stage.setScene(new Scene(root, 1280, 800));
        stage.setMaximized(true);   // or stage.setFullScreen(true)
        stage.show();
    }

    /**
     * Sizes the drag area to the page's header (the white bar with the DuesDesk name) and matches the window buttons to
     * the page's light or dark theme. Pages without that header keep the default height.
     */
    private static void matchPageHeader(WebView web, HeaderBar dragArea) {
        try {
            Object height = web.getEngine().executeScript(
                    "(function(){var b=document.querySelector('.app-header-stack .banner')||document.querySelector('.banner');"
                            + "return b?b.getBoundingClientRect().height:0;})()");
            double pixels = height instanceof Number number ? number.doubleValue() : 0;
            double dragHeight = pixels > 0 ? pixels : DEFAULT_HEADER_HEIGHT;
            dragArea.setPrefHeight(dragHeight);
            // Full-screen tables, dialogs and expanded panels start below the drag area so their buttons stay clickable.
            web.getEngine().executeScript("document.documentElement.style.setProperty('--desktop-header-h','"
                    + Math.ceil(dragHeight) + "px')");
            Object theme = web.getEngine().executeScript("document.documentElement.getAttribute('data-theme')");
            if (web.getScene() != null) {
                web.getScene().getPreferences().setColorScheme("dark".equals(theme) ? ColorScheme.DARK : ColorScheme.LIGHT);
            }
        } catch (RuntimeException e) {
            dragArea.setPrefHeight(DEFAULT_HEADER_HEIGHT);
        }
    }

    @Override
    public void stop() {
        // Closing the window or pressing Cmd+Q lands here and shuts the server down.
        if (ctx != null) {
            ctx.close();
        }
    }
}

