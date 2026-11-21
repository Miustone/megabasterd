package com.tonikelope.megabasterd;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/**
 * Performs a complete factory reset of MegaBasterd: shuts down active
 * transfers and DB/log handles, deletes all MegaBasterd state, backups,
 * caches, logs, and temp chunk directories, and optionally deletes tracked
 * downloaded files before cleanly restarting or exiting.
 */
public final class ResetManager {

    private static final Logger LOG = Logger.getLogger(ResetManager.class.getName());

    private ResetManager() {
    }

    public static final class ResetResult {
        public final int deletedItems;
        public final int failedItems;
        public final List<String> errors;

        public ResetResult(int deletedItems, int failedItems, List<String> errors) {
            this.deletedItems = deletedItems;
            this.failedItems = failedItems;
            this.errors = errors;
        }
    }

    public static void showResetDialog(MainPanel mainPanel, JFrame owner) {
        if (mainPanel == null) {
            return;
        }

        List<Path> trackedDownloads = collectTrackedDownloadFiles(mainPanel);

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel title = new JLabel(I18n.tr("ui.reset.title_header"));
        title.setFont(WinUI3Theme.resolveWin11Font(Font.BOLD, 15));
        panel.add(title, BorderLayout.NORTH);

        JPanel body = new JPanel(new GridLayout(0, 1, 0, 8));
        JLabel desc = new JLabel("<html><body style='width:420px'>"
                + I18n.tr("ui.reset.description")
                + "</body></html>");
        body.add(desc);

        JCheckBox deleteDownloadsCheck = new JCheckBox(
                I18n.tr("ui.reset.delete_downloads_checkbox", trackedDownloads.size()));
        deleteDownloadsCheck.setSelected(false);
        deleteDownloadsCheck.setForeground(WinUI3Theme.statusErrorColor());
        body.add(deleteDownloadsCheck);

        JCheckBox restartAfterCheck = new JCheckBox(I18n.tr("ui.reset.restart_after_checkbox"));
        restartAfterCheck.setSelected(true);
        body.add(restartAfterCheck);

        panel.add(body, BorderLayout.CENTER);

        int choice = JOptionPane.showConfirmDialog(
                owner,
                panel,
                I18n.tr("ui.reset.dialog_title"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        boolean deleteDownloads = deleteDownloadsCheck.isSelected();
        if (deleteDownloads) {
            int confirmDownloads = JOptionPane.showConfirmDialog(
                    owner,
                    I18n.tr("ui.reset.confirm_delete_downloads", trackedDownloads.size()),
                    I18n.tr("ui.reset.dialog_title"),
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.ERROR_MESSAGE);
            if (confirmDownloads != JOptionPane.YES_OPTION) {
                return;
            }
        }

        performResetAndExit(mainPanel, deleteDownloads, restartAfterCheck.isSelected(), trackedDownloads);
    }

    public static void performResetAndExit(
            MainPanel mainPanel,
            boolean deleteDownloads,
            boolean restartAfter,
            List<Path> trackedDownloads) {

        mainPanel.setExit(true);

        try {
            mainPanel.getDownload_manager().cancelAllTransferences();
            mainPanel.getUpload_manager().cancelAllTransferences();
        } catch (Exception ignore) {
        }

        try {
            if (mainPanel.getMega_proxy_server() != null) {
                mainPanel.getMega_proxy_server().stopServer();
            }
        } catch (Exception ignore) {
        }

        try {
            if (mainPanel.getKiss_stream_server() != null) {
                mainPanel.getKiss_stream_server().stopServer();
            }
        } catch (Exception ignore) {
        }

        try {
            SqliteSingleton.getInstance().shutdown();
        } catch (Exception ignore) {
        }

        closeAllFileLogHandlers();

        Path home = Paths.get(MainPanel.MEGABASTERD_HOME_DIR);
        ResetResult res = wipeToolData(home, deleteDownloads ? trackedDownloads : List.of());
        LOG.log(Level.INFO, "Reset finished: deleted={0}, failed={1}", new Object[]{res.deletedItems, res.failedItems});

        if (restartAfter) {
            try {
                MainPanel.restartApplicationFresh();
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Fresh restart failed: {0}", ex.getMessage());
            }
        }
        System.exit(0);
    }

    public static ResetResult wipeToolData(Path homeDir, List<Path> trackedDownloadPaths) {
        int deleted = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        Set<Path> targets = new LinkedHashSet<>();
        if (homeDir != null && Files.exists(homeDir)) {
            try (Stream<Path> s = Files.list(homeDir)) {
                s.forEach(p -> {
                    String name = p.getFileName().toString();
                    if (name.startsWith(".megabasterd")
                            || name.startsWith(".MEGABASTERD_CHUNKS_")
                            || name.equals("MEGABASTERD_UPLOAD_LOGS")
                            || name.startsWith("MEGABASTERD_DEBUG.log")) {
                        targets.add(p);
                    }
                });
            } catch (IOException ex) {
                failed++;
                errors.add(ex.getMessage());
            }
        }

        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        if (Files.exists(tmpDir)) {
            try (Stream<Path> s = Files.list(tmpDir)) {
                s.forEach(p -> {
                    String name = p.getFileName().toString();
                    if (name.startsWith("megabasterd_folder_cache_")
                            || name.startsWith("megabasterd_thumbnail_")
                            || name.startsWith(".mb_bench_")) {
                        targets.add(p);
                    }
                });
            } catch (IOException ex) {
                failed++;
                errors.add(ex.getMessage());
            }
        }

        for (Path dlPath : trackedDownloadPaths) {
            if (dlPath == null) {
                continue;
            }
            targets.add(dlPath);
            targets.add(Paths.get(dlPath.toString() + ".mbpart"));
            targets.add(Paths.get(dlPath.toString() + ".mbstate"));
            targets.add(Paths.get(dlPath.toString() + ".mctemp"));
            Path parent = dlPath.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                try (Stream<Path> s = Files.list(parent)) {
                    s.filter(p -> p.getFileName().toString().startsWith(".MEGABASTERD_CHUNKS_"))
                            .forEach(targets::add);
                } catch (IOException ignore) {
                }
            }
        }

        for (Path t : targets) {
            try {
                if (Files.exists(t)) {
                    deleteRecursively(t);
                    deleted++;
                }
            } catch (IOException ex) {
                failed++;
                errors.add(t + ": " + ex.getMessage());
            }
        }

        return new ResetResult(deleted, failed, errors);
    }

    private static void deleteRecursively(Path target) throws IOException {
        if (Files.isDirectory(target)) {
            try (Stream<Path> walk = Files.walk(target)) {
                List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
                for (Path p : all) {
                    Files.deleteIfExists(p);
                }
            }
        } else {
            Files.deleteIfExists(target);
        }
    }

    private static List<Path> collectTrackedDownloadFiles(MainPanel mainPanel) {
        Set<Path> paths = new LinkedHashSet<>();
        try {
            HashMap<String, HashMap<String, Object>> dbDownloads = DBTools.selectDownloads();
            if (dbDownloads != null) {
                for (HashMap<String, Object> row : dbDownloads.values()) {
                    String dir = (String) row.get("path");
                    String name = (String) row.get("filename");
                    if (dir != null && name != null) {
                        paths.add(Paths.get(dir, name));
                    }
                }
            }
        } catch (Exception ignore) {
        }
        try {
            String defaultDir = mainPanel.getDefault_download_path();
            if (defaultDir != null) {
                Path p = Paths.get(defaultDir);
                if (Files.isDirectory(p)) {
                    try (Stream<Path> s = Files.list(p)) {
                        s.filter(f -> {
                            String n = f.getFileName().toString();
                            return n.endsWith(".mbpart") || n.endsWith(".mbstate") || n.endsWith(".mctemp") || n.startsWith(".MEGABASTERD_CHUNKS_");
                        }).forEach(paths::add);
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return new ArrayList<>(paths);
    }

    private static void closeAllFileLogHandlers() {
        try {
            Logger root = LogManager.getLogManager().getLogger("");
            if (root != null) {
                for (Handler h : root.getHandlers()) {
                    try {
                        h.flush();
                        h.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        } catch (Exception ignore) {
        }
    }
}
