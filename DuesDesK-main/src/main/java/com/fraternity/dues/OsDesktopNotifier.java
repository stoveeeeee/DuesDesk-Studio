package com.fraternity.dues;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shows notifications the way each operating system expects: the Windows notification area (toasts), Notification
 * Center on macOS (through osascript), and notify-send on Linux desktops, falling back to the system tray. Clicking a
 * Windows notification opens the matching page in DuesDesk.
 */
final class OsDesktopNotifier implements NotificationService.DesktopNotifier {
    private static final Logger log = LoggerFactory.getLogger(OsDesktopNotifier.class);

    private final AppPaths.Os os = AppPaths.currentOs();
    private final Consumer<String> open;
    private TrayIcon trayIcon;
    private volatile String lastHref = "/";

    OsDesktopNotifier(Consumer<String> open) {
        this.open = open;
    }

    @Override
    public boolean show(String title, String body, String href) {
        lastHref = href == null || href.isBlank() ? "/" : href;
        String safeTitle = clean(title, 64);
        String safeBody = clean(body, 240);
        try {
            if (os == AppPaths.Os.MAC) return run(List.of("osascript", "-e", "display notification " + appleString(safeBody)
                    + " with title " + appleString("DuesDesk") + " subtitle " + appleString(safeTitle)));
            if (os == AppPaths.Os.OTHER && Files.isExecutable(Path.of("/usr/bin/notify-send"))) {
                return run(List.of("/usr/bin/notify-send", "--app-name=DuesDesk", safeTitle, safeBody));
            }
            return tray(safeTitle, safeBody);
        } catch (RuntimeException e) {
            log.warn("Unable to show a desktop notification", e);
            return false;
        }
    }

    private boolean tray(String title, String body) {
        if (!SystemTray.isSupported()) return false;
        EventQueue.invokeLater(() -> {
            try {
                if (trayIcon == null) {
                    trayIcon = new TrayIcon(icon(), "DuesDesk");
                    trayIcon.setImageAutoSize(true);
                    trayIcon.addActionListener(event -> open.accept(lastHref));
                    SystemTray.getSystemTray().add(trayIcon);
                }
                trayIcon.displayMessage(title, body, TrayIcon.MessageType.INFO);
            } catch (AWTException | RuntimeException e) {
                log.warn("Unable to show a notification in the system tray", e);
            }
        });
        return true;
    }

    /** Removes the tray icon so the app can exit cleanly. */
    void close() {
        TrayIcon icon = trayIcon;
        if (icon == null) return;
        try {
            EventQueue.invokeAndWait(() -> SystemTray.getSystemTray().remove(icon));
        } catch (Exception e) {
            log.debug("Tray icon was already gone", e);
        }
        trayIcon = null;
    }

    private static boolean run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            return true;
        } catch (java.io.IOException e) {
            log.warn("Unable to show a desktop notification with {}", command.getFirst(), e);
            return false;
        }
    }

    /** The DuesDesk monogram: a white D on a royal indigo rounded square. */
    private static BufferedImage icon() {
        int size = 64;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x5B, 0x4F, 0xE9));
        g.fill(new RoundRectangle2D.Float(2, 2, size - 4, size - 4, 18, 18));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 38));
        var metrics = g.getFontMetrics();
        g.drawString("D", (size - metrics.stringWidth("D")) / 2, (size - metrics.getHeight()) / 2 + metrics.getAscent());
        g.dispose();
        return image;
    }

    /** Text safe for a notification: one line, printable characters, limited length. */
    static String clean(String value, int max) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    /** An AppleScript string literal; the text is passed as an argument, never through a shell. */
    static String appleString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
