package com.fraternity.dues;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Where DuesDesk keeps its files on each operating system, and which cloud-sync folders are installed.
 * Everything is built with {@link Path} so it works with both "/" and "\" separators.
 */
public final class AppPaths {
    private AppPaths() {}

    public enum Os { MAC, WINDOWS, OTHER }

    /** A cloud-sync folder found on this computer, offered as a quick pick for automatic backups. */
    public record CloudFolder(String provider, String path) {}

    public static final String DATA_FILE_NAME = "store_data.json";
    /** Folder used before per-OS locations existed. Kept so existing installs keep their data. */
    public static final String LEGACY_DIR = ".fraternity-dues";

    public static Os currentOs() {
        return osFromName(System.getProperty("os.name", ""));
    }

    static Os osFromName(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.contains("mac") || name.contains("darwin")) return Os.MAC;
        if (name.contains("win")) return Os.WINDOWS;
        return Os.OTHER;
    }

    /** ~/Library/Application Support/DuesDesk on macOS, %LOCALAPPDATA%\DuesDesk on Windows, the XDG data folder elsewhere. */
    public static Path appDataDir() {
        return appDataDir(currentOs(), Path.of(System.getProperty("user.home", ".")), System::getenv);
    }

    static Path appDataDir(Os os, Path home, Function<String, String> env) {
        return switch (os) {
            case MAC -> home.resolve("Library").resolve("Application Support").resolve("DuesDesk");
            case WINDOWS -> {
                String localAppData = env.apply("LOCALAPPDATA");
                Path base = localAppData == null || localAppData.isBlank()
                        ? home.resolve("AppData").resolve("Local") : Path.of(localAppData);
                yield base.resolve("DuesDesk");
            }
            case OTHER -> {
                String xdg = env.apply("XDG_DATA_HOME");
                Path base = xdg == null || xdg.isBlank() ? home.resolve(".local").resolve("share") : Path.of(xdg);
                yield base.resolve("DuesDesk");
            }
        };
    }

    /**
     * The saved-data file to use when none is configured. An install that already has data in the legacy
     * ~/.fraternity-dues folder keeps using it, so upgrading never "loses" a chapter's records.
     */
    public static Path defaultDataFile() {
        return defaultDataFile(Path.of(System.getProperty("user.home", ".")), appDataDir());
    }

    static Path defaultDataFile(Path home, Path appDataDir) {
        Path legacy = home.resolve(LEGACY_DIR).resolve(DATA_FILE_NAME);
        Path legacyBackup = legacy.resolveSibling(DATA_FILE_NAME + ".bak");
        if (Files.exists(legacy) || Files.exists(legacyBackup)) return legacy;
        return appDataDir.resolve(DATA_FILE_NAME);
    }

    /** How the automatic-backup key is protected on this computer, for the Settings page. */
    public static String credentialStoreName() {
        return credentialStoreName(currentOs());
    }

    static String credentialStoreName(Os os) {
        return switch (os) {
            case MAC -> "macOS Keychain";
            case WINDOWS -> "Windows Credential Protection (DPAPI)";
            case OTHER -> "an owner-only key file in your DuesDesk data folder";
        };
    }

    /** Cloud-sync folders that exist on this computer (iCloud Drive, OneDrive, Dropbox, Google Drive). */
    public static List<CloudFolder> detectCloudFolders() {
        return detectCloudFolders(currentOs(), Path.of(System.getProperty("user.home", ".")), System::getenv, driveRoots());
    }

    private static List<Path> driveRoots() {
        List<Path> roots = new ArrayList<>();
        for (Path root : java.nio.file.FileSystems.getDefault().getRootDirectories()) roots.add(root);
        return roots;
    }

    static List<CloudFolder> detectCloudFolders(Os os, Path home, Function<String, String> env, List<Path> driveRoots) {
        Map<String, CloudFolder> found = new LinkedHashMap<>();
        if (os == Os.MAC) {
            add(found, "iCloud Drive", home.resolve("Library").resolve("Mobile Documents").resolve("com~apple~CloudDocs"));
            Path cloudStorage = home.resolve("Library").resolve("CloudStorage");
            for (Path entry : list(cloudStorage)) {
                String name = entry.getFileName().toString();
                if (name.startsWith("GoogleDrive")) {
                    Path myDrive = entry.resolve("My Drive");
                    add(found, "Google Drive", Files.isDirectory(myDrive) ? myDrive : entry);
                } else if (name.startsWith("OneDrive")) {
                    add(found, name.equals("OneDrive") || name.equals("OneDrive-Personal") ? "OneDrive" : "OneDrive (" + name.substring(name.indexOf('-') + 1) + ")", entry);
                } else if (name.startsWith("Dropbox")) {
                    add(found, "Dropbox", entry);
                }
            }
            add(found, "Dropbox", home.resolve("Dropbox"));
            add(found, "OneDrive", home.resolve("OneDrive"));
            add(found, "Google Drive", home.resolve("Google Drive"));
        } else if (os == Os.WINDOWS) {
            add(found, "iCloud Drive", home.resolve("iCloudDrive"));
            String personal = env.apply("OneDrive");
            if (personal != null && !personal.isBlank()) add(found, "OneDrive", Path.of(personal));
            String business = env.apply("OneDriveCommercial");
            if (business != null && !business.isBlank()) add(found, "OneDrive (work or school)", Path.of(business));
            add(found, "OneDrive", home.resolve("OneDrive"));
            add(found, "Dropbox", home.resolve("Dropbox"));
            add(found, "Google Drive", home.resolve("Google Drive"));
            // Google Drive for desktop mounts a virtual drive (usually G:) with a "My Drive" folder.
            for (Path root : driveRoots) add(found, "Google Drive", root.resolve("My Drive"));
        } else {
            add(found, "Dropbox", home.resolve("Dropbox"));
            add(found, "OneDrive", home.resolve("OneDrive"));
            add(found, "Google Drive", home.resolve("Google Drive"));
        }
        return List.copyOf(found.values());
    }

    private static void add(Map<String, CloudFolder> found, String provider, Path folder) {
        if (folder == null || !Files.isDirectory(folder)) return;
        String target = folder.resolve("DuesDesk Backups").toString();
        found.putIfAbsent(target, new CloudFolder(provider, target));
    }

    private static List<Path> list(Path folder) {
        if (!Files.isDirectory(folder)) return List.of();
        try (var entries = Files.list(folder)) {
            return entries.filter(Files::isDirectory).sorted().toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }
}
