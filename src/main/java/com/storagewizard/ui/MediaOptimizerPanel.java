package com.storagewizard.ui;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.List;

/**
 * MediaOptimizer: Finds heavy media files and compresses them asynchronously with FFmpeg.
 */
public class MediaOptimizerPanel extends JPanel {

    public enum Theme { CATPPUCCIN, GRUVBOX }

    record Palette(Color bg, Color surface, Color border, Color fg, Color muted,
                   Color accent, Color green, Color yellow, Color red, Color select) {
        static Palette of(Theme t) {
            return t == Theme.GRUVBOX
                ? new Palette(hex(0x282828), hex(0x3C3836), hex(0x504945), hex(0xEBDBB2), hex(0xA89984),
                              hex(0xFABD2F), hex(0xB8BB26), hex(0xFE8019), hex(0xFB4934), hex(0x504945))
                : new Palette(hex(0x1e1e2e), hex(0x313244), hex(0x45475a), hex(0xcdd6f4), hex(0x9399B2),
                              hex(0x89B4FA), hex(0xa6e3a1), hex(0xf9e2af), hex(0xf38ba8), hex(0x45475a));
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

    public static final Set<String> DEFAULT_MEDIA_EXTS = Set.of(
        "mp4", "mkv", "avi", "mov", "webm", "flv", "m4v", "wmv"
    );

    public record MediaInfo(Path file, long sizeBytes, String status) {
        public MediaInfo withStatus(String newStatus) {
            return new MediaInfo(file, sizeBytes, newStatus);
        }
    }

    private Palette p;
    private final JTextField pathField = new JTextField();
    private final JButton browseBtn;
    private final JButton scanBtn;
    private final JButton cancelBtn;
    private final JButton optimizeBtn;
    private final JLabel status = new JLabel(I18n.t("media.status.initial"));
    private final JProgressBar progress = new JProgressBar();
    private final MediaTableModel model = new MediaTableModel();
    private final JTable table = new JTable(model);
    private SwingWorker<?, ?> worker;

    private final JLabel lblTotalFiles = label("0", Font.PLAIN, 18f, Color.GRAY);
    private final JLabel lblTotalSize = label("0 B", Font.PLAIN, 18f, Color.GRAY);

    public MediaOptimizerPanel() { this(Theme.CATPPUCCIN); }

    public MediaOptimizerPanel(Theme theme) {
        this.p = Palette.of(theme);
        browseBtn = button(I18n.t("media.btn.browse"));
        scanBtn = button(I18n.t("media.btn.scan"));
        cancelBtn = button(I18n.t("media.btn.cancel"));
        optimizeBtn = button(I18n.t("media.btn.optimize"));

        setLayout(new BorderLayout(0, 16));
        setBackground(p.bg());
        setBorder(new EmptyBorder(24, 28, 20, 28));

        add(buildHeaderSection(), BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        pathField.setText(System.getProperty("user.home"));
        browseBtn.addActionListener(e -> chooseFolder());
        scanBtn.addActionListener(e -> startScan());
        pathField.addActionListener(e -> startScan());
        cancelBtn.addActionListener(e -> { if (worker != null) worker.cancel(true); });
        optimizeBtn.addActionListener(e -> confirmOptimize());

        table.getSelectionModel().addListSelectionListener(e -> refreshButtons());
        refreshButtons();
        updateColors();
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

    public void setTargetDirectory(Path dir) {
        if (dir != null) {
            pathField.setText(dir.toAbsolutePath().toString());
        }
    }

    private void updateColors() {
        lblTotalFiles.setForeground(p.accent());
        lblTotalSize.setForeground(p.green());
        table.setBackground(p.surface());
        table.setForeground(p.fg());
        table.setSelectionBackground(p.select());
        table.setSelectionForeground(p.fg());
    }

    private JComponent buildHeaderSection() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);

        JLabel title = label(I18n.t("media.title"), Font.BOLD, 22f, p.fg());
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel subtitle = label(I18n.t("media.subtitle"), Font.PLAIN, 12f, p.muted());
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(subtitle);
        header.add(Box.createVerticalStrut(16));

        JPanel statistics = new JPanel(new GridLayout(1, 2, 16, 0));
        statistics.setOpaque(false);
        statistics.setAlignmentX(Component.LEFT_ALIGNMENT);

        statistics.add(createStatBox(I18n.t("media.stat.files"), lblTotalFiles));
        statistics.add(createStatBox(I18n.t("media.stat.size"), lblTotalSize));

        header.add(statistics);
        return header;
    }

    private JPanel createStatBox(String title, JLabel valueLabel) {
        JPanel box = new JPanel(new BorderLayout(0, 6));
        box.setOpaque(false);
        box.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, p.border()), new EmptyBorder(0, 0, 10, 0)));
        box.add(label(title, Font.PLAIN, 12f, p.muted()), BorderLayout.NORTH);
        box.add(valueLabel, BorderLayout.CENTER);
        return box;
    }

    private JLabel section(String text) { return label(text, Font.PLAIN, 14f, p.accent()); }

    private JComponent buildCenterPanel() {
        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);

        JPanel criteriaBox = new JPanel(new BorderLayout(0, 10));
        criteriaBox.setOpaque(false);
        criteriaBox.setBorder(new CompoundBorder(new LineBorder(p.border()), new EmptyBorder(12, 14, 12, 14)));

        JLabel sec1 = section(I18n.t("media.sec.dir"));
        criteriaBox.add(sec1, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 0, 8, 12);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        grid.add(label(I18n.t("media.label.path"), Font.PLAIN, 12f, p.muted()), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        grid.add(field(pathField), gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        grid.add(browseBtn, gbc);
        gbc.gridx = 3;
        grid.add(scanBtn, gbc);

        JPanel subGrid = new JPanel(new BorderLayout(0, 6));
        subGrid.setOpaque(false);
        subGrid.add(grid, BorderLayout.CENTER);
        subGrid.add(label(I18n.t("media.formats", String.join(", ", DEFAULT_MEDIA_EXTS)), Font.PLAIN, 12f, p.muted()), BorderLayout.SOUTH);

        criteriaBox.add(subGrid, BorderLayout.CENTER);
        center.add(criteriaBox, BorderLayout.NORTH);

        JPanel resultsBox = new JPanel(new BorderLayout(0, 8));
        resultsBox.setOpaque(false);
        JLabel sec2 = section(I18n.t("media.sec.results"));
        resultsBox.add(sec2, BorderLayout.NORTH);
        resultsBox.add(buildTable(), BorderLayout.CENTER);

        center.add(resultsBox, BorderLayout.CENTER);
        return center;
    }

    private JComponent buildTable() {
        table.setBackground(p.surface());
        table.setForeground(p.fg());
        table.setFont(mono(Font.PLAIN, 13f));
        table.setRowHeight(32);
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
            JLabel l = label(String.valueOf(v), Font.BOLD, 12f, p.muted());
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
                if (v instanceof Long n) { setText(ProjectsPanel.human(n)); setHorizontalAlignment(RIGHT); }
                else setHorizontalAlignment(LEFT);
                if (!sel) {
                    setBackground(r % 2 == 0 ? p.surface() : p.bg());
                    setForeground(m == 2 ? p.yellow() : m == 3 ? p.green() : p.fg());
                }
                return this;
            }
        };
        table.setDefaultRenderer(Object.class, cell);
        table.setDefaultRenderer(Long.class, cell);
        int[] widths = {340, 100, 120, 180};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(new LineBorder(p.border()));
        sp.getViewport().setBackground(p.surface());
        return sp;
    }

    private JComponent buildBottom() {
        JPanel b = new JPanel(new BorderLayout(10, 0));
        b.setOpaque(false);
        status.setFont(mono(Font.PLAIN, 12f));
        status.setForeground(p.muted());
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(100, 4));
        progress.setBorderPainted(false);
        progress.setBackground(p.surface());
        progress.setForeground(p.accent());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(status); left.add(progress);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        optimizeBtn.setForeground(p.green());
        right.add(cancelBtn); right.add(optimizeBtn);

        b.add(left, BorderLayout.CENTER);
        b.add(right, BorderLayout.EAST);
        return b;
    }

    private JLabel label(String text, int style, float size, Color c) {
        JLabel l = new JLabel(text);
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
        b.setFont(mono(Font.BOLD, 12f));
        b.setForeground(p.fg());
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(6, 12, 6, 12));
        b.setRolloverEnabled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private void setStatus(String text, Color c) { status.setText(text); status.setForeground(c); }

    private void updateMetrics() {
        int totalFiles = model.getRowCount();
        long totalBytes = 0;
        for (int i = 0; i < totalFiles; i++) totalBytes += model.get(i).sizeBytes();
        lblTotalFiles.setText(String.valueOf(totalFiles));
        lblTotalSize.setText(ProjectsPanel.human(totalBytes));
        updateColors();
    }

    private void refreshButtons() {
        boolean busy = worker != null && !worker.isDone();
        scanBtn.setEnabled(!busy);
        browseBtn.setEnabled(!busy);
        cancelBtn.setVisible(busy);
        progress.setVisible(busy);
        optimizeBtn.setEnabled(!busy && table.getSelectedRowCount() > 0);
    }

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser(pathField.getText());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle(I18n.t("media.chooser.title"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            pathField.setText(fc.getSelectedFile().getAbsolutePath());
    }

    public void startScan() {
        if (worker != null && !worker.isDone()) return;
        Path root;
        try { root = Paths.get(pathField.getText().trim()).toAbsolutePath().normalize(); }
        catch (InvalidPathException ex) { setStatus(I18n.t("media.status.invalid_path"), p.red()); return; }
        if (!Files.isDirectory(root)) { setStatus(I18n.t("media.status.missing_dir"), p.red()); return; }

        model.setRows(List.of());
        updateMetrics();
        setStatus(I18n.t("media.status.scanning", root), p.yellow());

        Set<String> skipDirs = Set.of("proc", "sys", "dev", "run", "usr", "etc", "var");

        SwingWorker<List<MediaInfo>, MediaInfo> w = new SwingWorker<>() {
            @Override protected List<MediaInfo> doInBackground() throws IOException {
                List<MediaInfo> found = new ArrayList<>();
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (isCancelled()) return FileVisitResult.TERMINATE;
                        if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                        String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (!dir.equals(root) && (n.startsWith(".") || skipDirs.contains(n))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (isCancelled()) return FileVisitResult.TERMINATE;
                        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        int idx = name.lastIndexOf('.');
                        if (idx > 0 && idx < name.length() - 1) {
                            String ext = name.substring(idx + 1);
                            if (DEFAULT_MEDIA_EXTS.contains(ext)) {
                                MediaInfo info = new MediaInfo(file, attrs.size(), I18n.t("media.item.pending"));
                                found.add(info);
                                publish(info);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
                });
                return found;
            }
            @Override protected void process(List<MediaInfo> chunk) {
                model.addRows(chunk);
                updateMetrics();
                setStatus(I18n.t("media.status.found_so_far", model.getRowCount()), p.yellow());
            }
            @Override protected void done() {
                if (isCancelled()) setStatus(I18n.t("media.status.cancelled"), p.yellow());
                else try { get(); setStatus(I18n.t("media.status.complete", model.getRowCount()), p.green()); updateMetrics(); }
                catch (Exception ex) { setStatus(I18n.t("media.status.error", ex.getMessage()), p.red()); }
                refreshButtons();
            }
        };
        worker = w;
        refreshButtons();
        w.execute();
    }

    private void confirmOptimize() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) return;

        int r = JOptionPane.showConfirmDialog(this,
            I18n.t("media.confirm.msg", rows.length),
            I18n.t("media.confirm.title"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);

        if (r == JOptionPane.OK_OPTION) {
            List<MediaInfo> selected = new ArrayList<>();
            for (int v : rows) selected.add(model.get(table.convertRowIndexToModel(v)));
            runFFmpegOptimization(selected);
        }
    }

    private void runFFmpegOptimization(List<MediaInfo> targets) {
        setStatus(I18n.t("media.status.optimizing"), p.yellow());
        SwingWorker<Integer, Void> w = new SwingWorker<>() {
            @Override protected Integer doInBackground() throws Exception {
                int successCount = 0;
                for (MediaInfo mi : targets) {
                    if (isCancelled()) break;
                    Path input = mi.file();
                    Path output = input.resolveSibling(input.getFileName().toString() + ".optimized.mp4");

                    ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg", "-i", input.toAbsolutePath().toString(),
                        "-vcodec", "libx264", "-crf", "28", "-preset", "medium",
                        "-acodec", "aac", "-b:a", "128k", "-y",
                        output.toAbsolutePath().toString()
                    );
                    pb.redirectErrorStream(true);
                    Process process = pb.start();

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        while (reader.readLine() != null) {
                            if (isCancelled()) { process.destroyForcibly(); break; }
                        }
                    }

                    int exitCode = process.waitFor();
                    if (exitCode == 0 && Files.exists(output) && Files.size(output) > 0) {
                        Files.move(output, input, StandardCopyOption.REPLACE_EXISTING);
                        successCount++;
                    } else {
                        if (Files.exists(output)) Files.deleteIfExists(output);
                    }
                }
                return successCount;
            }

            @Override protected void done() {
                try {
                    int count = get();
                    setStatus(I18n.t("media.status.opt_complete", count), p.green());
                    startScan();
                } catch (Exception ex) {
                    setStatus(I18n.t("media.status.opt_error", ex.getMessage()), p.red());
                }
                refreshButtons();
            }
        };
        worker = w;
        refreshButtons();
        w.execute();
    }

    static class MediaTableModel extends AbstractTableModel {
        private final String[] cols = {
            I18n.t("media.col.file"),
            I18n.t("media.col.size"),
            I18n.t("media.col.format"),
            I18n.t("media.col.status")
        };
        private final List<MediaInfo> rows = new ArrayList<>();
        void setRows(List<MediaInfo> r) { rows.clear(); rows.addAll(r); fireTableDataChanged(); }
        void addRows(List<MediaInfo> r) { int s = rows.size(); rows.addAll(r); if (!r.isEmpty()) fireTableRowsInserted(s, rows.size() - 1); }
        MediaInfo get(int i) { return rows.get(i); }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 1 ? Long.class : String.class; }
        @Override public Object getValueAt(int r, int c) {
            MediaInfo mi = rows.get(r);
            return switch (c) {
                case 0 -> mi.file().getFileName().toString();
                case 1 -> mi.sizeBytes();
                case 2 -> {
                    String name = mi.file().getFileName().toString();
                    int idx = name.lastIndexOf('.');
                    yield idx > 0 ? name.substring(idx + 1).toUpperCase(Locale.ROOT) : "FILE";
                }
                default -> mi.status();
            };
        }
    }
}
