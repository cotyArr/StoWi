package com.storagewizard.ui;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;

/**
 * OptionsPanel — Java 17+, offline settings (custom scan directories & theme).
 */
public final class OptionsPanel extends JPanel {
    public enum Theme { CATPPUCCIN, GRUVBOX }

    public record Settings(List<Path> scanDirectories, Theme theme, String language) {
        public Settings {
            scanDirectories = List.copyOf(scanDirectories);
            Objects.requireNonNull(theme);
            language = (language == null || language.isBlank()) ? "en" : language.trim();
        }
    }

    private static final Font MONO = chooseFont();
    private enum Tone { TEXT, DIM, ACCENT, OK, ERROR }

    private record Palette(Color bg, Color surface, Color border, Color text,
                           Color dim, Color accent, Color ok, Color error) {
        static Palette of(Theme theme) {
            return theme == Theme.GRUVBOX
                ? new Palette(new Color(0x282828), new Color(0x3C3836), new Color(0x504945),
                    new Color(0xEBDBB2), new Color(0xA89984), new Color(0xFABD2F),
                    new Color(0xB8BB26), new Color(0xFB4934))
                : new Palette(new Color(0x1E1E2E), new Color(0x313244), new Color(0x45475A),
                    new Color(0xCDD6F4), new Color(0x9399B2), new Color(0x89B4FA),
                    new Color(0xA6E3A1), new Color(0xF38BA8));
        }
        Color color(Tone tone) {
            return switch (tone) {
                case TEXT -> text; case DIM -> dim; case ACCENT -> accent;
                case OK -> ok; case ERROR -> error;
            };
        }
    }

    private Theme theme;
    private String language = "en";
    private Palette palette;
    private final DefaultListModel<Path> folders = new DefaultListModel<>();
    private final JList<Path> folderList = new JList<>(folders);
    private final JToggleButton catppuccin = new JToggleButton("Catppuccin");
    private final JToggleButton gruvbox = new JToggleButton("Gruvbox");
    private final JButton remove = button(I18n.t("options.btn.remove_dir"), Tone.DIM);
    private final JLabel folderStatus = label(I18n.t("options.dirs.count", 0), Tone.DIM, 12);
    private Consumer<Settings> onSettingsChanged = ignored -> {};
    private Consumer<Theme> onThemeChanged = ignored -> {};

    public OptionsPanel(Theme theme) {
        this.theme = Objects.requireNonNull(theme);
        this.palette = Palette.of(theme);

        setLayout(new BorderLayout(0, 20));
        setBorder(new EmptyBorder(30, 28, 24, 28));

        JLabel title = label(I18n.t("options.title"), Tone.TEXT, 22);
        title.setFont(MONO.deriveFont(Font.BOLD, 22f));
        add(title, BorderLayout.NORTH);

        JPanel content = transparent();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        // 01 / Custom scan directories
        JPanel directories = section(I18n.t("options.sec.dirs"));
        folderList.setVisibleRowCount(4);
        folderList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        folderList.setFixedCellHeight(30);
        folderList.setCellRenderer((list, value, index, selected, focused) -> {
            JLabel row = new JLabel("[dir] " + value);
            row.setFont(MONO.deriveFont(13f));
            row.setOpaque(true);
            row.setBackground(selected ? palette.border() : palette.surface());
            row.setForeground(selected ? palette.accent() : palette.text());
            row.setBorder(new EmptyBorder(2, 10, 2, 10));
            row.setToolTipText(value.toString());
            return row;
        });

        JScrollPane folderScroll = new JScrollPane(folderList);
        folderScroll.putClientProperty("framed", true);
        folderScroll.setPreferredSize(new Dimension(300, 126));
        folderScroll.setMinimumSize(new Dimension(100, 90));
        directories.add(folderScroll, BorderLayout.CENTER);

        JPanel directoryFooter = transparent();
        directoryFooter.setLayout(new BoxLayout(directoryFooter, BoxLayout.Y_AXIS));
        JPanel actions = flow();
        JButton add = button(I18n.t("options.btn.add_dir"), Tone.ACCENT);
        add.addActionListener(e -> chooseFolders());
        remove.setEnabled(false);
        remove.addActionListener(e -> removeSelectedDirectories());
        folderList.addListSelectionListener(e -> remove.setEnabled(!folderList.isSelectionEmpty()));
        actions.add(add);
        actions.add(remove);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        folderStatus.setAlignmentX(LEFT_ALIGNMENT);
        directoryFooter.add(actions);
        directoryFooter.add(Box.createVerticalStrut(5));
        directoryFooter.add(folderStatus);
        directories.add(directoryFooter, BorderLayout.SOUTH);
        content.add(directories);

        // 02 / Appearance
        JPanel appearance = section(I18n.t("options.sec.appearance"));
        JPanel themeButtons = flow();
        ButtonGroup group = new ButtonGroup();
        group.add(catppuccin);
        group.add(gruvbox);
        decorateButton(catppuccin, Tone.ACCENT);
        decorateButton(gruvbox, Tone.ACCENT);
        for (JToggleButton toggle : new JToggleButton[]{catppuccin, gruvbox}) {
            toggle.setUI(new javax.swing.plaf.basic.BasicToggleButtonUI() {
                @Override protected void paintButtonPressed(Graphics g, AbstractButton b) {
                    g.setColor(palette.border());
                    g.fillRect(0, 0, b.getWidth(), b.getHeight());
                }
            });
        }
        catppuccin.addActionListener(e -> setTheme(Theme.CATPPUCCIN));
        gruvbox.addActionListener(e -> setTheme(Theme.GRUVBOX));
        themeButtons.add(catppuccin);
        themeButtons.add(gruvbox);
        appearance.add(themeButtons, BorderLayout.CENTER);
        content.add(appearance);

        content.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        add(scroll, BorderLayout.CENTER);
        applyTheme();
    }

    public Settings getSettings() {
        return new Settings(Collections.list(folders.elements()), theme, language);
    }

    public void setOnSettingsChanged(Consumer<Settings> callback) {
        onSettingsChanged = Objects.requireNonNull(callback);
    }

    public void setOnThemeChanged(Consumer<Theme> callback) {
        onThemeChanged = Objects.requireNonNull(callback);
    }

    /** Load persisted settings without triggering change callbacks. */
    public void loadSettings(Settings settings) {
        Objects.requireNonNull(settings);
        Consumer<Settings> callback = onSettingsChanged;
        onSettingsChanged = ignored -> {};
        try {
            folders.clear();
            for (Path path : settings.scanDirectories()) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!folders.contains(normalized)) folders.addElement(normalized);
            }
            theme = settings.theme();
            language = settings.language();
            palette = Palette.of(theme);
            applyTheme();
            updateFolderCount();
        } finally {
            onSettingsChanged = callback;
        }
    }

    public void setTheme(Theme next) {
        Objects.requireNonNull(next);
        if (theme == next) return;
        theme = next;
        palette = Palette.of(next);
        applyTheme();
        onThemeChanged.accept(next);
        notifySettings();
    }

    /** Returns false if the path does not exist, is not a directory, or is already listed. */
    public boolean addDirectory(Path path) {
        Path normalized = Objects.requireNonNull(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            status(folderStatus, I18n.t("options.dirs.missing"), Tone.ERROR);
            return false;
        }
        if (folders.contains(normalized)) {
            status(folderStatus, I18n.t("options.dirs.exists"), Tone.DIM);
            return false;
        }
        folders.addElement(normalized);
        folderList.setSelectedIndex(folders.size() - 1);
        updateFolderCount();
        notifySettings();
        return true;
    }

    public void removeSelectedDirectories() {
        int[] selected = folderList.getSelectedIndices();
        if (selected.length == 0) return;
        for (int i = selected.length - 1; i >= 0; i--) folders.remove(selected[i]);
        updateFolderCount();
        notifySettings();
    }

    private void chooseFolders() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(I18n.t("options.dirs.chooser"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (java.io.File file : chooser.getSelectedFiles()) addDirectory(file.toPath());
        }
    }

    private void notifySettings() { onSettingsChanged.accept(getSettings()); }

    private void updateFolderCount() {
        status(folderStatus, I18n.t("options.dirs.count", folders.size()), Tone.DIM);
    }

    private JPanel section(String title) {
        JPanel panel = transparent();
        panel.setLayout(new BorderLayout(0, 12));
        panel.putClientProperty("section", true);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        JLabel heading = label(title, Tone.ACCENT, 14);
        heading.setFont(MONO.deriveFont(Font.BOLD, 14f));
        panel.add(heading, BorderLayout.NORTH);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 260));
        return panel;
    }

    private static JPanel transparent() { JPanel p = new JPanel(); p.setOpaque(false); return p; }
    private static JPanel flow() { return new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6)) {{ setOpaque(false); }}; }

    private static JLabel label(String text, Tone tone, int size) {
        JLabel label = new JLabel(text);
        label.putClientProperty("tone", tone);
        label.setFont(MONO.deriveFont((float) size));
        return label;
    }

    private static JButton button(String text, Tone tone) {
        JButton button = new JButton(text);
        decorateButton(button, tone);
        return button;
    }

    private static void decorateButton(AbstractButton button, Tone tone) {
        button.putClientProperty("tone", tone);
        button.setFont(MONO.deriveFont(13f));
        button.setBorder(new EmptyBorder(9, 12, 9, 12));
        button.setFocusPainted(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setOpaque(true);
        button.setContentAreaFilled(false);
        button.addFocusListener(new FocusAdapter() {
            public void focusGained(FocusEvent e) { button.repaint(); }
            public void focusLost(FocusEvent e) { button.repaint(); }
        });
    }

    private void status(JLabel label, String text, Tone tone) {
        label.setText(text);
        label.putClientProperty("tone", tone);
        label.setForeground(palette.color(tone));
        label.setToolTipText(text);
    }

    private void applyTheme() {
        catppuccin.setSelected(theme == Theme.CATPPUCCIN);
        gruvbox.setSelected(theme == Theme.GRUVBOX);
        skin(this);
        revalidate();
        repaint();
    }

    private void skin(Component component) {
        component.setBackground(palette.bg());
        component.setForeground(palette.text());
        if (component instanceof JComponent jc) {
            Object tone = jc.getClientProperty("tone");
            if (tone instanceof Tone t) jc.setForeground(palette.color(t));
            if (Boolean.TRUE.equals(jc.getClientProperty("section")))
                jc.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, palette.border()),
                            new EmptyBorder(16, 0, 18, 0)));
            if (Boolean.TRUE.equals(jc.getClientProperty("framed")))
                jc.setBorder(new CompoundBorder(new LineBorder(palette.border()),
                            new EmptyBorder(4, 8, 4, 8)));
        }
        if (component instanceof JList<?> list) list.setBackground(palette.surface());

        if (component instanceof JScrollPane sp && !Boolean.TRUE.equals(sp.getClientProperty("framed"))) {
            sp.setBorder(null);
            sp.getViewport().setBackground(palette.bg());
        }
        if (component instanceof AbstractButton b) {
            b.setFont(MONO.deriveFont(13f));
            b.setBackground(b.isSelected() ? palette.border() : palette.surface());
            if (b instanceof JToggleButton && !(b instanceof JCheckBox)) {
                b.setUI(new javax.swing.plaf.basic.BasicToggleButtonUI() {
                    @Override protected void paintButtonPressed(Graphics g, AbstractButton btn) {
                        g.setColor(palette.border());
                        g.fillRect(0, 0, btn.getWidth(), btn.getHeight());
                    }
                });
                b.setOpaque(true);
                b.setContentAreaFilled(true);
                b.setForeground(b.isSelected() ? palette.accent() : palette.dim());
                b.setBorder(new CompoundBorder(
                    new LineBorder(b.isSelected() ? palette.accent() : palette.border()),
                    new EmptyBorder(8, 11, 8, 11)));
            }
        }
        if (component instanceof Container container)
            for (Component child : container.getComponents()) skin(child);
    }

    private static Font chooseFont() {
        Set<String> names = new HashSet<>(Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String name : new String[]{"JetBrains Mono", "Fira Code", "Consolas", "DejaVu Sans Mono", "Menlo"})
            if (names.contains(name)) return new Font(name, Font.PLAIN, 13);
        return new Font(Font.MONOSPACED, Font.PLAIN, 13);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            OptionsPanel panel = new OptionsPanel(args.length > 0 && args[0].equalsIgnoreCase("gruvbox")
                                                   ? Theme.GRUVBOX : Theme.CATPPUCCIN);
            JFrame frame = new JFrame("StorageWizard · Options");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(panel);
            frame.setSize(800, 620);
            frame.setMinimumSize(new Dimension(540, 420));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
