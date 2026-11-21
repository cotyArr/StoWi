package com.storagewizard.ui;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class ProjectsPanel extends JPanel {

    public enum Theme { CATPPUCCIN, GRUVBOX }

    record Palette(Color bg, Color surface, Color border, Color fg, Color muted,
                   Color accent, Color green, Color yellow, Color red, Color select) {
        static Palette of(Theme t) {
            return t == Theme.GRUVBOX
                ? new Palette(hex(0x282828), hex(0x3C3836), hex(0x504945), hex(0xEBDBB2), hex(0xA89984),
                              hex(0xFABD2F), hex(0xB8BB26), hex(0xFE8019), hex(0xFB4934), hex(0x504945))
                : new Palette(hex(0x1E1E2E), hex(0x313244), hex(0x45475A), hex(0xCDD6F4), hex(0x9399B2),
                              hex(0x89B4FA), hex(0xA6E3A1), hex(0xF9E2AF), hex(0xF38BA8), hex(0x45475A));
        }
        private static Color hex(int v) { return new Color(v); }
    }

    private static Font mono(int style, float size) {
        Set<String> av = new HashSet<>(Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : List.of("JetBrains Mono", "Fira Code", "Consolas", "DejaVu Sans Mono", "Menlo"))
            if (av.contains(f)) return new Font(f, style, (int) size);
        return new Font(Font.MONOSPACED, style, (int) size);
    }

    public void setTheme(Theme theme) {
        Palette oldP = this.p;
        this.p = Palette.of(theme);
        if (oldP.equals(this.p)) return;

        Map<Color, Color> colorMap = new HashMap<>();
        try {
            for (var rc : Palette.class.getRecordComponents()) {
                Object oldVal = rc.getAccessor().invoke(oldP);
                Object newVal = rc.getAccessor().invoke(this.p);
                if (oldVal instanceof Color oc && newVal instanceof Color nc) {
                    colorMap.put(oc, nc);
                }
            }
        } catch (Exception ignored) {}

        recolorTree(this, colorMap, p.border(), p.accent());

        table.setBackground(p.surface());
        table.setForeground(p.fg());
        table.setGridColor(p.border());
        table.setSelectionBackground(p.select());
        table.setSelectionForeground(p.accent());
        if (table.getTableHeader() != null) {
            table.getTableHeader().setBackground(p.bg());
            table.getTableHeader().setForeground(p.muted());
        }
        revalidate();
        repaint();
    }

    private static void recolorTree(Component c, Map<Color, Color> map, Color newBorder, Color newAccent) {
        if (c.getBackground() != null && map.containsKey(c.getBackground())) {
            c.setBackground(map.get(c.getBackground()));
        }
        if (c.getForeground() != null && map.containsKey(c.getForeground())) {
            c.setForeground(map.get(c.getForeground()));
        }
        if (c instanceof JTextField tf) {
            tf.setCaretColor(newAccent);
        }
        if (c instanceof JScrollPane sp && sp.getViewport() != null) {
            Color vpBg = sp.getViewport().getBackground();
            if (vpBg != null && map.containsKey(vpBg)) {
                sp.getViewport().setBackground(map.get(vpBg));
            }
        }
        if (c instanceof JComponent jc && jc.getBorder() != null) {
            jc.setBorder(recolorBorder(jc.getBorder(), map));
        }
        if (c instanceof Container cont) {
            for (Component child : cont.getComponents()) {
                recolorTree(child, map, newBorder, newAccent);
            }
        }
    }

    private static Border recolorBorder(Border b, Map<Color, Color> map) {
        if (b instanceof LineBorder lb) {
            Color mapped = map.getOrDefault(lb.getLineColor(), lb.getLineColor());
            return new LineBorder(mapped, lb.getThickness(), lb.getRoundedCorners());
        } else if (b instanceof MatteBorder mb) {
            Color mapped = map.getOrDefault(mb.getMatteColor(), mb.getMatteColor());
            Insets in = mb.getBorderInsets();
            return new MatteBorder(in.top, in.left, in.bottom, in.right, mapped);
        } else if (b instanceof CompoundBorder cb) {
            return new CompoundBorder(
                recolorBorder(cb.getOutsideBorder(), map),
                recolorBorder(cb.getInsideBorder(), map)
            );
        }
        return b;
    }

    public static final List<String> DEFAULT_JUNK = List.of(
        "node_modules", "target", "build", "dist", ".gradle", "__pycache__", ".venv", "venv", ".next", "out");
    private static final List<String> PROJECT_MARKERS = List.of(
        "package.json", "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
        "Cargo.toml", "pyproject.toml", "requirements.txt", "go.mod", "composer.json", "CMakeLists.txt", "Makefile", ".git");

    public enum StackPreset {
        ALL("[All]", DEFAULT_JUNK, PROJECT_MARKERS),
        NODE("[Node/JS]",
            List.of("node_modules", ".next", "dist", "build", ".turbo", ".parcel-cache", "coverage", "out"),
            List.of("package.json", "pnpm-lock.yaml", "yarn.lock", "tsconfig.json")),
        JAVA("[Java/JVM]",
            List.of("target", "build", ".gradle", "out", "bin"),
            List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle")),
        PYTHON("[Python]",
            List.of("__pycache__", ".venv", "venv", "env", ".pytest_cache", ".mypy_cache", ".ruff_cache", "build", "dist"),
            List.of("pyproject.toml", "requirements.txt", "setup.py", "Pipfile")),
        RUST_CPP("[Rust/C++]",
            List.of("target", "build", "cmake-build-debug", "cmake-build-release", "out", "dist"),
            List.of("Cargo.toml", "CMakeLists.txt", "Makefile", "meson.build"));

        public final String label;
        public final List<String> junk;
        public final List<String> markers;

        StackPreset(String label, List<String> junk, List<String> markers) {
            this.label = label;
            this.junk = junk;
            this.markers = markers;
        }
    }

    public record ProjectInfo(Path root, long totalBytes, Map<Path, Long> junk) {
        public long junkBytes() { return junk.values().stream().mapToLong(Long::longValue).sum(); }
        public String junkNames() {
            if (junk.isEmpty()) return "—";
            StringJoiner j = new StringJoiner(", ");
            junk.keySet().forEach(p -> j.add(p.getFileName().toString()));
            return j.toString();
        }
    }

    private Palette p;
    private StackPreset activePreset = StackPreset.ALL;
    private final Map<StackPreset, JButton> presetButtons = new EnumMap<>(StackPreset.class);
    private final JTextField pathField = new JTextField();
    private final JTextField customField = new JTextField();
    private final JLabel activeTargetsLabel = new JLabel();
    private final JButton browseBtn;
    private final JButton scanBtn;
    private final JButton cancelBtn;
    private final JButton purgeBtn;
    private final JLabel status = new JLabel(I18n.t("projects.status.initial"));
    private final JProgressBar progress = new JProgressBar();
    private final ProjectTableModel model = new ProjectTableModel();
    private final JTable table = new JTable(model);
    private SwingWorker<?, ?> worker;

    public ProjectsPanel() { this(Theme.CATPPUCCIN); }

    public ProjectsPanel(Theme theme) {
        this.p = Palette.of(theme);
        browseBtn = button(I18n.t("projects.btn.browse"));
        scanBtn = button(I18n.t("projects.btn.scan"));
        cancelBtn = button(I18n.t("projects.btn.cancel"));
        purgeBtn = button(I18n.t("projects.btn.purge"));
        setLayout(new BorderLayout(0, 16));
        setBackground(p.bg());
        setBorder(new EmptyBorder(24, 28, 20, 28));
        add(buildTop(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        pathField.setText(System.getProperty("user.home"));
        browseBtn.addActionListener(e -> chooseFolder());
        scanBtn.addActionListener(e -> startScan());
        pathField.addActionListener(e -> startScan());
        cancelBtn.addActionListener(e -> { if (worker != null) worker.cancel(true); });
        purgeBtn.addActionListener(e -> confirmPurge());
        table.getSelectionModel().addListSelectionListener(e -> refreshButtons());
        refreshButtons();
    }

    private JComponent buildTop() {
        JPanel top = new JPanel(new BorderLayout(0, 14));
        top.setOpaque(false);
        top.add(label(I18n.t("projects.title"), p.fg(), Font.BOLD, 22f), BorderLayout.NORTH);

        JPanel box = new JPanel(new BorderLayout(0, 10));
        box.setOpaque(false);
        box.setBorder(new CompoundBorder(new LineBorder(p.border()), new EmptyBorder(12, 14, 12, 14)));
        box.add(section(I18n.t("projects.sec.dir")), BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 8, 12);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridy = 0; c.gridx = 0; c.weightx = 0;
        grid.add(label(I18n.t("projects.label.path"), p.muted(), Font.PLAIN, 12f), c);
        c.gridx = 1; c.weightx = 1; grid.add(field(pathField), c);
        c.gridx = 2; c.weightx = 0; grid.add(browseBtn, c);
        c.gridx = 3; c.insets = new Insets(0, 0, 8, 0); grid.add(scanBtn, c);

        c.insets = new Insets(0, 0, 8, 12);
        c.gridy = 1; c.gridx = 0; c.weightx = 0; c.gridwidth = 1;
        grid.add(label(I18n.t("projects.label.stack"), p.muted(), Font.PLAIN, 12f), c);

        JPanel presetsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        presetsRow.setOpaque(false);
        for (StackPreset preset : StackPreset.values()) {
            JButton chip = presetChip(preset);
            presetButtons.put(preset, chip);
            presetsRow.add(chip);
        }
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1; c.insets = new Insets(0, -8, 8, 0);
        grid.add(presetsRow, c);

        c.insets = new Insets(0, 0, 8, 12);
        c.gridy = 2; c.gridx = 0; c.gridwidth = 1; c.weightx = 0;
        grid.add(label(I18n.t("projects.label.extra"), p.muted(), Font.PLAIN, 12f), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1; c.insets = new Insets(0, 0, 8, 0);
        grid.add(field(customField), c);
        customField.setToolTipText(I18n.t("projects.placeholder.extra"));
        placeholder(customField, I18n.t("projects.placeholder.extra"));

        c.gridy = 3; c.gridx = 1; c.insets = new Insets(0, 0, 0, 0);
        activeTargetsLabel.setFont(mono(Font.PLAIN, 12f));
        activeTargetsLabel.setForeground(p.muted());
        updatePresetUI();
        grid.add(activeTargetsLabel, c);

        box.add(grid, BorderLayout.CENTER);
        top.add(box, BorderLayout.CENTER);
        return top;
    }

    private JButton presetChip(StackPreset preset) {
        JButton b = new JButton(preset.label) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                boolean selected = (activePreset == preset);
                boolean hover = getModel().isRollover() && isEnabled();

                g2.setColor(selected ? p.select() : hover ? p.border() : p.surface());
                g2.fillRect(0, 0, getWidth(), getHeight());

                g2.setColor(selected ? p.accent() : p.border());
                g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);

                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(selected ? p.accent() : hover ? p.fg() : p.muted());
                g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                              (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        b.setFont(mono(Font.BOLD, 12f));
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(5, 11, 5, 11));
        b.setRolloverEnabled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> {
            activePreset = preset;
            updatePresetUI();
            if (worker == null || worker.isDone()) {
                setStatus(I18n.t("projects.status.preset", preset.label), p.accent());
            }
        });
        return b;
    }

    private void updatePresetUI() {
        activeTargetsLabel.setText("targets (" + activePreset.label + "): " + String.join(", ", activePreset.junk));
        presetButtons.values().forEach(Component::repaint);
    }

    private JLabel section(String text) { return label(text, p.accent(), Font.PLAIN, 14f); }

    private JComponent buildTable() {
        table.setBackground(p.surface());
        table.setForeground(p.fg());
        table.setFont(mono(Font.PLAIN, 13f));
        table.setRowHeight(26);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionBackground(p.select());
        table.setSelectionForeground(p.fg());
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);

        JTableHeader h = table.getTableHeader();
        h.setReorderingAllowed(false);
        h.setPreferredSize(new Dimension(0, 34));
        h.setDefaultRenderer((t, v, sel, foc, r, col) -> {
            JLabel l = label(String.valueOf(v), p.muted(), Font.BOLD, 12f);
            l.setOpaque(true);
            l.setBackground(p.bg());
            l.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, p.border()), new EmptyBorder(0, 10, 0, 10)));
            return l;
        });

        DefaultTableCellRenderer cell = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int col) {
                super.getTableCellRendererComponent(t, v, sel, false, r, col);
                setBorder(new EmptyBorder(0, 10, 0, 10));
                int m = t.convertColumnIndexToModel(col);
                if (v instanceof Long n) { setText(human(n)); setHorizontalAlignment(RIGHT); }
                else setHorizontalAlignment(LEFT);
                if (!sel) {
                    setBackground(r % 2 == 0 ? p.surface() : p.bg());
                    setForeground(m == 3 ? p.yellow() : m == 4 ? p.red() : m == 1 ? p.muted() : p.fg());
                }
                return this;
            }
        };
        table.setDefaultRenderer(Object.class, cell);
        table.setDefaultRenderer(Long.class, cell);
        int[] widths = {180, 320, 90, 220, 90};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(new LineBorder(p.border()));
        sp.getViewport().setBackground(p.surface());
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setOpaque(false);
        center.add(section(I18n.t("projects.sec.found")), BorderLayout.NORTH);
        center.add(sp, BorderLayout.CENTER);
        return center;
    }

    private JComponent buildBottom() {
        JPanel b = new JPanel(new BorderLayout(10, 0));
        b.setOpaque(false);
        status.setFont(mono(Font.PLAIN, 12f));
        status.setForeground(p.muted());
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(120, 6));
        progress.setBorderPainted(false);
        progress.setBackground(p.surface());
        progress.setForeground(p.accent());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(status); left.add(progress);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        purgeBtn.setForeground(p.red());
        right.add(cancelBtn); right.add(purgeBtn);

        b.add(left, BorderLayout.CENTER);
        b.add(right, BorderLayout.EAST);
        return b;
    }

    private JLabel label(String t, Color c, int style, float size) {
        JLabel l = new JLabel(t);
        l.setForeground(c);
        l.setFont(mono(style, size));
        return l;
    }

    private JTextField field(JTextField f) {
        f.setFont(mono(Font.PLAIN, 13f));
        f.setBackground(p.surface());
        f.setForeground(p.fg());
        f.setCaretColor(p.accent());
        f.setBorder(new CompoundBorder(new LineBorder(p.border()), new EmptyBorder(7, 10, 7, 10)));
        return f;
    }

    private void placeholder(JTextField f, String hint) {
        f.putClientProperty("hint", hint);
        f.setUI(new javax.swing.plaf.basic.BasicTextFieldUI() {
            @Override protected void paintSafely(Graphics g) {
                super.paintSafely(g);
                if (f.getText().isEmpty() && !f.isFocusOwner()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(p.muted());
                    Insets in = f.getInsets();
                    g2.drawString(hint, in.left, in.top + g2.getFontMetrics().getAscent());
                    g2.dispose();
                }
            }
        });
        field(f);
    }

    private JButton button(String text) {
        JButton b = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                boolean hover = getModel().isRollover() && isEnabled();
                g2.setColor(hover ? getForeground() : p.surface());
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(isEnabled() ? p.border() : p.select());
                g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(!isEnabled() ? p.muted() : hover ? p.bg() : getForeground());
                g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                              (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        b.setFont(mono(Font.BOLD, 13f));
        b.setForeground(p.fg());
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(7, 14, 7, 14));
        b.setRolloverEnabled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private void setStatus(String text, Color c) { status.setText(text); status.setForeground(c); }

    private void refreshButtons() {
        boolean busy = worker != null && !worker.isDone();
        scanBtn.setEnabled(!busy);
        browseBtn.setEnabled(!busy);
        presetButtons.values().forEach(b -> b.setEnabled(!busy));
        cancelBtn.setVisible(busy);
        progress.setVisible(busy);
        purgeBtn.setEnabled(!busy && selectedWithJunk().size() > 0);
    }

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser(pathField.getText());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle(I18n.t("projects.chooser.title"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            pathField.setText(fc.getSelectedFile().getAbsolutePath());
    }

    public Set<String> junkNames() {
        Set<String> names = new LinkedHashSet<>(activePreset.junk);
        for (String s : customField.getText().split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !t.contains("/") && !t.contains("\\") && !t.equals(".") && !t.equals("..")) names.add(t);
        }
        return names;
    }

    public void startScan() {
        if (worker != null && !worker.isDone()) return;
        Path root;
        try { root = Paths.get(pathField.getText().trim()).toAbsolutePath().normalize(); }
        catch (InvalidPathException ex) { setStatus(I18n.t("projects.status.invalid_path"), p.red()); return; }
        if (!Files.isDirectory(root)) { setStatus(I18n.t("projects.status.missing_dir"), p.red()); return; }

        Set<String> junk = junkNames();
        List<String> markers = activePreset.markers;
        model.setRows(List.of());
        setStatus(I18n.t("projects.status.scanning", root, activePreset.label), p.yellow());

        SwingWorker<List<ProjectInfo>, ProjectInfo> w = new SwingWorker<>() {
            @Override protected List<ProjectInfo> doInBackground() throws IOException {
                List<ProjectInfo> found = new ArrayList<>();
                findProjects(root, junk, markers, info -> { found.add(info); publish(info); }, this::isCancelled);
                return found;
            }
            @Override protected void process(List<ProjectInfo> chunk) {
                model.addRows(chunk);
                setStatus(I18n.t("projects.status.found_so_far", model.getRowCount()), p.yellow());
            }
            @Override protected void done() {
                if (isCancelled()) setStatus(I18n.t("projects.status.cancelled", summary()), p.yellow());
                else try { get(); setStatus("[+] " + summary(), p.green()); }
                catch (Exception ex) { setStatus(I18n.t("projects.status.error", rootMessage(ex)), p.red()); }
                refreshButtons();
            }
        };
        worker = w;
        refreshButtons();
        w.execute();
    }

    interface Sink { void accept(ProjectInfo p); }
    interface Cancel { boolean get(); }

    static void findProjects(Path root, Set<String> junk, Sink sink, Cancel cancel) throws IOException {
        findProjects(root, junk, PROJECT_MARKERS, sink, cancel);
    }

    static void findProjects(Path root, Set<String> junk, List<String> markers, Sink sink, Cancel cancel) throws IOException {
        Set<String> skipDirs = Set.of("Fightcade", "yay", "paru", "proc", "sys", "dev", "run", "usr", "etc", "var");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (cancel.get()) return FileVisitResult.TERMINATE;
                if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(root) && (n.startsWith(".") || skipDirs.contains(n) || junk.contains(n) || DEFAULT_JUNK.contains(n)))
                    return FileVisitResult.SKIP_SUBTREE;
                if (isProject(dir, markers)) {
                    sink.accept(analyze(dir, junk, cancel));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });
    }

    static boolean isProject(Path dir) { return isProject(dir, PROJECT_MARKERS); }

    static boolean isProject(Path dir, List<String> markers) {
        for (String m : markers) if (Files.exists(dir.resolve(m), LinkOption.NOFOLLOW_LINKS)) return true;
        return false;
    }

    static ProjectInfo analyze(Path project, Set<String> junk, Cancel cancel) {
        Map<Path, Long> found = new LinkedHashMap<>();
        AtomicLong total = new AtomicLong();
        try {
            Files.walkFileTree(project, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    if (cancel.get()) return FileVisitResult.TERMINATE;
                    if (!dir.equals(project) && dir.getFileName() != null && junk.contains(dir.getFileName().toString())) {
                        long s = size(dir);
                        found.put(dir, s);
                        total.addAndGet(s);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    total.addAndGet(a.size()); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { }
        return new ProjectInfo(project, total.get(), found);
    }

    static long size(Path dir) {
        AtomicLong s = new AtomicLong();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) { s.addAndGet(a.size()); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { }
        return s.get();
    }

    private List<ProjectInfo> selectedWithJunk() {
        List<ProjectInfo> out = new ArrayList<>();
        for (int v : table.getSelectedRows()) {
            ProjectInfo pi = model.get(table.convertRowIndexToModel(v));
            if (!pi.junk().isEmpty()) out.add(pi);
        }
        return out;
    }

    private void confirmPurge() {
        List<ProjectInfo> sel = selectedWithJunk();
        if (sel.isEmpty()) return;
        long bytes = sel.stream().mapToLong(ProjectInfo::junkBytes).sum();
        StringBuilder sb = new StringBuilder();
        for (ProjectInfo pi : sel) for (Path j : pi.junk().keySet()) sb.append("  rm -rf ").append(j).append('\n');

        JTextArea list = new JTextArea(sb.toString(), Math.min(10, sb.toString().split("\n").length), 60);
        list.setEditable(false);
        list.setFont(mono(Font.PLAIN, 12f));
        list.setBackground(p.surface());
        list.setForeground(p.red());
        JPanel msg = new JPanel(new BorderLayout(0, 8));
        msg.add(new JLabel(I18n.t("projects.confirm.msg", count(sel), sel.size(), human(bytes))), BorderLayout.NORTH);
        msg.add(new JScrollPane(list), BorderLayout.CENTER);

        int r = JOptionPane.showConfirmDialog(this, msg, I18n.t("projects.confirm.title"),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.OK_OPTION) purge(sel);
    }

    private static int count(List<ProjectInfo> l) { return l.stream().mapToInt(x -> x.junk().size()).sum(); }

    private void purge(List<ProjectInfo> sel) {
        setStatus(I18n.t("projects.status.purging"), p.yellow());
        SwingWorker<long[], Void> w = new SwingWorker<>() {
            @Override protected long[] doInBackground() {
                long freed = 0, failed = 0;
                for (ProjectInfo pi : sel) for (Map.Entry<Path, Long> e : pi.junk().entrySet()) {
                    if (isCancelled()) break;
                    if (deleteTree(e.getKey(), pi.root())) freed += e.getValue(); else failed++;
                }
                return new long[]{freed, failed};
            }
            @Override protected void done() {
                try {
                    long[] res = get();
                    for (ProjectInfo pi : sel) model.replace(pi, analyze(pi.root(), junkNames(), () -> false));
                    setStatus(res[1] == 0
                        ? I18n.t("projects.status.purge_ok", human(res[0]))
                        : I18n.t("projects.status.purge_partial", human(res[0]), res[1]),
                        res[1] == 0 ? p.green() : p.yellow());
                } catch (Exception ex) { setStatus(I18n.t("projects.status.error", rootMessage(ex)), p.red()); }
                refreshButtons();
            }
        };
        worker = w;
        refreshButtons();
        w.execute();
    }

    static boolean deleteTree(Path dir, Path projectRoot) {
        Path d = dir.toAbsolutePath().normalize();
        if (!d.startsWith(projectRoot.toAbsolutePath().normalize()) || d.equals(projectRoot)) return false;
        if (Files.isSymbolicLink(d)) {
            try { Files.deleteIfExists(d); return true; } catch (IOException e) { return false; }
        }
        try {
            Files.walkFileTree(d, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path sub, BasicFileAttributes a) throws IOException {
                    if (Files.isSymbolicLink(sub)) { Files.deleteIfExists(sub); return FileVisitResult.SKIP_SUBTREE; }
                    sub.toFile().setWritable(true);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    f.toFile().setWritable(true);
                    Files.deleteIfExists(f);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) throws IOException {
                    f.toFile().setWritable(true);
                    Files.deleteIfExists(f);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path x, IOException e) throws IOException {
                    if (e != null) throw e;
                    x.toFile().setWritable(true);
                    Files.deleteIfExists(x);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException e) { return false; }
    }

    private String summary() {
        long junk = 0;
        for (int i = 0; i < model.getRowCount(); i++) junk += model.get(i).junkBytes();
        return I18n.t("projects.summary", model.getRowCount(), human(junk));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    public static String human(long b) {
        if (b < 1024) return b + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = b; int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", v, u[i]);
    }

    public List<ProjectInfo> getProjects() { return model.rows(); }
    public JTextField getPathField() { return pathField; }
    public JTextField getCustomField() { return customField; }

    public void setTargetDirectory(Path dir) {
        if (dir != null) {
            pathField.setText(dir.toAbsolutePath().toString());
        }
    }

    static class ProjectTableModel extends AbstractTableModel {
        private final String[] cols = {
            I18n.t("projects.col.project"),
            I18n.t("projects.col.path"),
            I18n.t("projects.col.size"),
            I18n.t("projects.col.junk_dirs"),
            I18n.t("projects.col.junk")
        };
        private final List<ProjectInfo> rows = new ArrayList<>();
        void setRows(List<ProjectInfo> r) { rows.clear(); rows.addAll(r); fireTableDataChanged(); }
        void addRows(List<ProjectInfo> r) { int s = rows.size(); rows.addAll(r); if (!r.isEmpty()) fireTableRowsInserted(s, rows.size() - 1); }
        void replace(ProjectInfo old, ProjectInfo now) { int i = rows.indexOf(old); if (i >= 0) { rows.set(i, now); fireTableRowsUpdated(i, i); } }
        ProjectInfo get(int i) { return rows.get(i); }
        List<ProjectInfo> rows() { return List.copyOf(rows); }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 2 || c == 4 ? Long.class : String.class; }
        @Override public Object getValueAt(int r, int c) {
            ProjectInfo pi = rows.get(r);
            return switch (c) {
                case 0 -> String.valueOf(pi.root().getFileName());
                case 1 -> pi.root().toString();
                case 2 -> pi.totalBytes();
                case 3 -> pi.junkNames();
                default -> pi.junkBytes();
            };
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("StorageWizard · Projects");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setContentPane(new ProjectsPanel(args.length > 0 && args[0].equalsIgnoreCase("gruvbox") ? Theme.GRUVBOX : Theme.CATPPUCCIN));
            f.setSize(1000, 640);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
