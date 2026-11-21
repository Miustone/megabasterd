package com.tonikelope.megabasterd;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.Border;

/**
 * Windows 11 / WinUI 3 look-and-feel integration powered by FlatLaf.
 */
public final class WinUI3Theme {

    private static final Logger LOG = Logger.getLogger(WinUI3Theme.class.getName());

    private static volatile boolean DARK_ACTIVE = false;

    public static final Color WIN11_ACCENT_LIGHT = new Color(0x00, 0x67, 0xC0);
    public static final Color WIN11_ACCENT_DARK = new Color(0x60, 0xCD, 0xFF);

    public static final Color SURFACE_LIGHT = new Color(0xF3, 0xF3, 0xF3);
    public static final Color CARD_LIGHT = new Color(0xFF, 0xFF, 0xFF);
    public static final Color CARD_BORDER_LIGHT = new Color(0xE2, 0xE2, 0xE2);

    public static final Color SURFACE_DARK = new Color(0x20, 0x20, 0x20);
    public static final Color CARD_DARK = new Color(0x2B, 0x2B, 0x2B);
    public static final Color CARD_BORDER_DARK = new Color(0x3D, 0x3D, 0x3D);

    public static final Color STATUS_OK_LIGHT = new Color(0x0F, 0x7B, 0x0F);
    public static final Color STATUS_OK_DARK = new Color(0x6C, 0xCB, 0x5F);
    public static final Color STATUS_WARN_LIGHT = new Color(0x9D, 0x5D, 0x00);
    public static final Color STATUS_WARN_DARK = new Color(0xFC, 0xE1, 0x00);
    public static final Color STATUS_ERR_LIGHT = new Color(0xC4, 0x2B, 0x1C);
    public static final Color STATUS_ERR_DARK = new Color(0xFF, 0x99, 0xA4);

    private WinUI3Theme() {
    }

    public static boolean isDark() {
        return DARK_ACTIVE;
    }

    public static Color accentColor() {
        return DARK_ACTIVE ? WIN11_ACCENT_DARK : WIN11_ACCENT_LIGHT;
    }

    public static Color statusOkColor() {
        return DARK_ACTIVE ? STATUS_OK_DARK : STATUS_OK_LIGHT;
    }

    public static Color statusWarnColor() {
        return DARK_ACTIVE ? STATUS_WARN_DARK : STATUS_WARN_LIGHT;
    }

    public static Color statusErrorColor() {
        return DARK_ACTIVE ? STATUS_ERR_DARK : STATUS_ERR_LIGHT;
    }

    public static Color cardBackground() {
        return DARK_ACTIVE ? CARD_DARK : CARD_LIGHT;
    }

    public static Color surfaceBackground() {
        return DARK_ACTIVE ? SURFACE_DARK : SURFACE_LIGHT;
    }

    public static Color cardBorderColor() {
        return DARK_ACTIVE ? CARD_BORDER_DARK : CARD_BORDER_LIGHT;
    }

    public static Border createCardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(cardBorderColor(), 1, true),
                BorderFactory.createEmptyBorder(8, 10, 8, 10));
    }

    public static Font resolveWin11Font(int style, int size) {
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String candidate : new String[]{"Segoe UI Variable Text", "Segoe UI Variable", "Segoe UI", "Dialog"}) {
            if (available.contains(candidate)) {
                return new Font(candidate, style, size);
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    /**
     * Reads Windows 11 Personalize AppsUseLightTheme registry key when on Windows.
     */
    public static boolean detectSystemDarkMode() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return false;
        }
        try {
            Process p = new ProcessBuilder(
                    "reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("AppsUseLightTheme")) {
                        return line.trim().endsWith("0x0");
                    }
                }
            }
            p.waitFor();
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Could not detect Windows dark mode: {0}", ex.getMessage());
        }
        return false;
    }

    public static void apply(String darkSettingValue) {
        boolean dark;
        if ("yes".equalsIgnoreCase(darkSettingValue)) {
            dark = true;
        } else if ("no".equalsIgnoreCase(darkSettingValue)) {
            dark = false;
        } else {
            dark = detectSystemDarkMode();
        }
        apply(dark);
    }

    public static void apply(boolean dark) {
        DARK_ACTIVE = dark;
        try {
            FlatLaf.setUseNativeWindowDecorations(true);
            UIManager.put("TitlePane.unifiedBackground", true);
            UIManager.put("TitlePane.showIcon", true);
            UIManager.put("Component.arc", 8);
            UIManager.put("Button.arc", 8);
            UIManager.put("TextComponent.arc", 6);
            UIManager.put("CheckBox.arc", 5);
            UIManager.put("ProgressBar.arc", 6);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("ScrollBar.showButtons", false);
            UIManager.put("TabbedPane.showTabSeparators", false);
            UIManager.put("TabbedPane.tabType", "card");
            UIManager.put("TabbedPane.cardTabSelectionHeight", 3);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("Component.innerFocusWidth", 0);

            if (dark) {
                UIManager.put("Panel.background", SURFACE_DARK);
                UIManager.put("OptionPane.background", SURFACE_DARK);
                UIManager.put("Dialog.background", SURFACE_DARK);
                UIManager.put("Component.accentColor", WIN11_ACCENT_DARK);
                UIManager.put("ProgressBar.foreground", WIN11_ACCENT_DARK);
                UIManager.put("TabbedPane.underlineColor", WIN11_ACCENT_DARK);
                FlatDarkLaf.setup();
            } else {
                UIManager.put("Panel.background", SURFACE_LIGHT);
                UIManager.put("OptionPane.background", SURFACE_LIGHT);
                UIManager.put("Dialog.background", SURFACE_LIGHT);
                UIManager.put("Component.accentColor", WIN11_ACCENT_LIGHT);
                UIManager.put("ProgressBar.foreground", WIN11_ACCENT_LIGHT);
                UIManager.put("TabbedPane.underlineColor", WIN11_ACCENT_LIGHT);
                FlatLightLaf.setup();
            }

            Font uiFont = resolveWin11Font(Font.PLAIN, 13);
            MainPanel.GUI_FONT = uiFont;
            UIManager.put("defaultFont", uiFont);
            FlatLaf.updateUI();
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failed to apply WinUI3 FlatLaf theme, falling back: {0}", ex.getMessage());
        }
    }

    public static void styleCardPanel(JPanel panel) {
        if (panel == null) {
            return;
        }
        panel.setBackground(cardBackground());
        panel.setOpaque(true);
        panel.setBorder(createCardBorder());
    }

    public static void stylePrimaryButton(JButton btn) {
        if (btn == null) {
            return;
        }
        btn.putClientProperty("JButton.buttonType", "roundRect");
        btn.setBackground(accentColor());
        btn.setForeground(DARK_ACTIVE ? Color.BLACK : Color.WHITE);
        btn.setOpaque(true);
    }

    public static void styleDangerButton(JButton btn) {
        if (btn == null) {
            return;
        }
        btn.putClientProperty("JButton.buttonType", "roundRect");
        btn.setBackground(statusErrorColor());
        btn.setForeground(DARK_ACTIVE ? Color.BLACK : Color.WHITE);
        btn.setOpaque(true);
    }

    public static void styleSubtleComponent(JComponent comp) {
        if (comp == null) {
            return;
        }
        comp.putClientProperty("FlatLaf.style", "arc: 8");
    }

    public static void applyScrollBarTuning(Container root) {
        if (root == null) {
            return;
        }
        for (Component c : root.getComponents()) {
            if (c instanceof javax.swing.JScrollPane sp) {
                sp.getVerticalScrollBar().setUnitIncrement(20);
                sp.getHorizontalScrollBar().setUnitIncrement(20);
            } else if (c instanceof Container child) {
                applyScrollBarTuning(child);
            }
        }
    }
}
