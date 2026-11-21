package com.storagewizard.ui;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public final class MyStuffPanel extends JPanel {
    public enum Theme { CATPPUCCIN, GRUVBOX }
    public enum Category {
        DUPLICATE("mystuff.cat.duplicate"), CACHE("mystuff.cat.cache"),
        LARGE("mystuff.cat.large"), COLD("mystuff.cat.cold");
        private final String key;
        Category(String key) { this.key = key; }
        public String label() { return I18n.t(key); }
    }
    public enum TargetType {
        ALL("mystuff.type.all"),
        FILES("mystuff.type.files"),
        DIRECTORIES("mystuff.type.dirs");

        private final String key;
        TargetType(String key) { this.key = key; }
        public String label() { return I18n.t(key); }
        public boolean matches(boolean isDir) {
            return this == ALL || (this == DIRECTORIES && isDir) || (this == FILES && !isDir);
        }
    }
    public record Entry(String id, String name, Path path, long bytes, Instant lastModified,
                        Category category, boolean directory, long recoverableBytes) {
        public Entry {
            Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(path);
            Objects.requireNonNull(lastModified); Objects.requireNonNull(category);
            if (id.isBlank() || name.isBlank()) throw new IllegalArgumentException("Empty ID/name");
            if (bytes < 0 || recoverableBytes < 0 || recoverableBytes > bytes)
                throw new IllegalArgumentException("Invalid size or recoverable bytes");
        }
        public boolean cleanable() { return recoverableBytes > 0; }
        public String extension() {
            int dot = name.lastIndexOf('.');
            return directory || dot <= 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        }
    }
    public enum SizeRange {
        ALL("mystuff.size.all", 0), MB100("> 100 MB", 100L << 20), GB1("> 1 GB", 1L << 30), GB10("> 10 GB", 10L << 30);
        public final String label; public final long minBytes;
        SizeRange(String label, long minBytes) { this.label = label; this.minBytes = minBytes; }
        public String toString() { return label.startsWith("mystuff.") ? I18n.t(label) : label; }
    }
    public enum Age {
        ANY("mystuff.age.any", 0), M3("mystuff.age.m3", 3), M6("mystuff.age.m6", 6),
        M12("mystuff.age.m12", 12), M24("mystuff.age.m24", 24);
        public final String key; public final int months;
        Age(String key, int months) { this.key = key; this.months = months; }
        public String toString() { return I18n.t(key); }
    }
    public record FilterCriteria(String pattern, long minBytes, Set<String> extensions, int olderThanMonths, TargetType targetType) {
        public FilterCriteria {
            pattern = pattern == null ? "" : pattern.trim();
            extensions = Set.copyOf(extensions);
            targetType = targetType == null ? TargetType.ALL : targetType;
            if (minBytes < 0 || olderThanMonths < 0) throw new IllegalArgumentException("Negative criteria");
        }
        public FilterCriteria(String pattern, long minBytes, Set<String> extensions, int olderThanMonths) {
            this(pattern, minBytes, extensions, olderThanMonths, TargetType.ALL);
        }
        public static FilterCriteria none() { return new FilterCriteria("", 0, Set.of(), 0, TargetType.ALL); }
        public boolean matches(Entry e, Instant now) {
            if (!targetType.matches(e.directory())) return false;
            if (e.bytes() < minBytes) return false;
            if (!extensions.isEmpty() && (e.directory() || !extensions.contains(e.extension()))) return false;
            if (olderThanMonths > 0) {
                Instant limit = now.atZone(ZoneOffset.UTC).minusMonths(olderThanMonths).toInstant();
                if (!e.lastModified().isBefore(limit)) return false;
            }
            if (pattern.isEmpty()) return true;
            String p = pattern.toLowerCase(Locale.ROOT);
            String name = e.name().toLowerCase(Locale.ROOT), path = e.path().toString().toLowerCase(Locale.ROOT);
            if (p.indexOf('*') < 0 && p.indexOf('?') < 0) return name.contains(p) || path.contains(p);
            StringBuilder rx = new StringBuilder();
            for (char ch : p.toCharArray())
                rx.append(ch == '*' ? ".*" : ch == '?' ? "." : java.util.regex.Pattern.quote(String.valueOf(ch)));
            String regex = rx.toString();
            return name.matches(regex) || path.matches(regex);
        }
    }
    @FunctionalInterface public interface FileFilterService {
        List<Entry> filter(FilterCriteria criteria) throws Exception;
    }
    @FunctionalInterface public interface CompressionHandler {
        Path compress(Entry entry) throws Exception;
    }
    public record Statistics(long analyzedBytes, long duplicateFiles, long potentialBytes) {}
    public record CleanupResult(Set<String> removedIds, Map<String, String> failures) {
        public CleanupResult {
            removedIds = Set.copyOf(removedIds); failures = Map.copyOf(failures);
            if (!Collections.disjoint(removedIds, failures.keySet()))
                throw new IllegalArgumentException("An ID cannot be both removed and failed");
        }
    }
    @FunctionalInterface public interface CleanupHandler {
        CleanupResult clean(List<Entry> entries) throws Exception;
    }
    @FunctionalInterface public interface Confirmation {
        boolean confirm(Component parent, List<Entry> entries);
    }

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
                case TEXT -> text; case DIM -> dim; case ACCENT -> accent; case OK -> ok; case ERROR -> error;
            };
        }
    }
    public static final List<String> QUICK_EXTENSIONS = List.of(".zip", ".mkv", ".log", ".iso");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Font MONO = chooseFont();
    private static final int COL_NAME = 0, COL_SIZE = 1, COL_DATE = 2, COL_ACTIONS = 3;

    private Theme theme;
    private Palette palette;
    private long analyzedBytes;
    private boolean busy;
    private TargetType activeTargetType = TargetType.ALL;
    private final Map<TargetType, JButton> typeButtons = new EnumMap<>(TargetType.class);
    private final List<Entry> allEntries = new ArrayList<>();
    private FilterCriteria activeCriteria = FilterCriteria.none();
    private final EntryModel model = new EntryModel();
    private final JTable table = new JTable(model);
    private final JLabel analyzed = label("0 B", Tone.TEXT, 18);
    private final JLabel duplicates = label("0", Tone.ACCENT, 18);
    private final JLabel potential = label("0 B", Tone.OK, 18);
    private final JTextField patternField = new PlaceholderField(I18n.t("mystuff.placeholder.pattern"));
    private final JComboBox<SizeRange> sizeCombo = new JComboBox<>(SizeRange.values());
    private final Map<String, JCheckBox> extBoxes = new LinkedHashMap<>();
    private final JTextField extraExt = new PlaceholderField(I18n.t("mystuff.placeholder.ext"));
    private final JComboBox<Age> ageCombo = new JComboBox<>(Age.values());
    private final JButton apply = button(I18n.t("mystuff.btn.apply"), Tone.ACCENT);
    private final JButton reset = button(I18n.t("mystuff.btn.reset"), Tone.DIM);
    private final JLabel status = label(I18n.t("mystuff.status.empty"), Tone.DIM, 12);
    private final JLabel selection = label(I18n.t("mystuff.selection", 0, 0), Tone.DIM, 12);
    private final JButton details = button(I18n.t("mystuff.btn.view"), Tone.ACCENT);
    private final JButton compress = button(I18n.t("mystuff.btn.compress"), Tone.OK);
    private final JButton clean = button(I18n.t("mystuff.btn.delete"), Tone.ERROR);

    private FileFilterService filterService;
    private CompressionHandler compressionHandler;
    private CleanupHandler cleanupHandler;
    private Confirmation confirmation = this::confirmCleanup;
    private Consumer<Entry> onView = this::showDetails;
    private Consumer<CleanupResult> onCleanupCompleted = result -> {};
    private Consumer<Path> onCompressed = path -> {};

    public MyStuffPanel(Theme theme) {
        this.theme = Objects.requireNonNull(theme); palette = Palette.of(theme);
        setLayout(new BorderLayout(0, 16));
        setBorder(new EmptyBorder(24, 28, 20, 28));

        JPanel top = transparent(new BorderLayout(0, 14));
        JLabel title = label(I18n.t("mystuff.title"), Tone.TEXT, 22);
        title.setFont(MONO.deriveFont(Font.BOLD, 22f)); top.add(title, BorderLayout.NORTH);
        JPanel statistics = transparent(new GridLayout(1, 3, 16, 0));
        statistics.add(stat(I18n.t("mystuff.stat.analyzed"), analyzed));
        statistics.add(stat(I18n.t("mystuff.stat.duplicates"), duplicates));
        statistics.add(stat(I18n.t("mystuff.stat.potential"), potential));
        top.add(statistics, BorderLayout.CENTER);
        top.add(buildFilters(), BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        table.setFont(MONO.deriveFont(13f)); table.setRowHeight(40);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(true); table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0)); table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new CellRenderer());
        table.setDefaultRenderer(Long.class, new CellRenderer());
        table.setDefaultRenderer(Instant.class, new CellRenderer());
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(0, 34));
        table.getTableHeader().setDefaultRenderer(new HeaderRenderer());
        int[] widths = {420, 110, 150, 150};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(COL_NAME).setMinWidth(220);
        table.getColumnModel().getColumn(COL_ACTIONS).setMinWidth(140);
        JScrollPane scroll = new JScrollPane(table);
        scroll.putClientProperty("framed", true);
        scroll.setPreferredSize(new Dimension(600, 260));
        JPanel center = transparent(new BorderLayout(0, 8));
        center.add(label(I18n.t("mystuff.sec.results"), Tone.ACCENT, 14), BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER); add(center, BorderLayout.CENTER);

        JPanel footer = transparent(new BorderLayout(0, 10));
        JPanel buttons = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.add(details); buttons.add(Box.createHorizontalStrut(8));
        buttons.add(compress); buttons.add(Box.createHorizontalStrut(8)); buttons.add(clean);
        JPanel row = transparent(new BorderLayout());
        row.add(buttons, BorderLayout.WEST); row.add(selection, BorderLayout.EAST);
        footer.add(row, BorderLayout.CENTER); footer.add(status, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);

        table.getSelectionModel().addListSelectionListener(e -> updateActions());
        details.addActionListener(e -> viewSelected());
        compress.addActionListener(e -> compressSelected());
        clean.addActionListener(e -> requestCleanup());
        apply.addActionListener(e -> applyFilters());
        reset.addActionListener(e -> resetFilters());
        patternField.addActionListener(e -> applyFilters());
        extraExt.addActionListener(e -> applyFilters());
        table.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { maybePopup(e); }
            public void mouseReleased(MouseEvent e) { maybePopup(e); }
            public void mouseClicked(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint()), c = table.columnAtPoint(e.getPoint());
                if (r < 0 || e.isPopupTrigger()) return;
                if (SwingUtilities.isLeftMouseButton(e) && table.convertColumnIndexToModel(c) == COL_ACTIONS) {
                    table.setRowSelectionInterval(r, r); showActionsMenu(e.getX(), e.getY());
                } else if (e.getClickCount() == 2) viewSelected();
            }
        });
        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "view");
        table.getActionMap().put("view", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { viewSelected(); }
        });
        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
        table.getActionMap().put("delete", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { requestCleanup(); }
        });
        applyTheme(); updateActions();
    }

    private JComponent buildFilters() {
        JPanel box = transparent(new BorderLayout(0, 10));
        box.putClientProperty("framedPad", true);
        box.add(label(I18n.t("mystuff.sec.criteria"), Tone.ACCENT, 14), BorderLayout.NORTH);
        JPanel grid = transparent(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 8, 12); c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; grid.add(label(I18n.t("mystuff.label.name"), Tone.DIM, 12), c);
        c.gridx = 1; c.weightx = 1; c.gridwidth = 3; grid.add(patternField, c);
        c.gridwidth = 1; c.weightx = 0;
        c.gridx = 4; grid.add(label(I18n.t("mystuff.label.size"), Tone.DIM, 12), c);
        c.gridx = 5; c.weightx = 0.4; grid.add(sizeCombo, c); c.weightx = 0;

        c.gridx = 0; c.gridy = 1; grid.add(label(I18n.t("mystuff.label.ext"), Tone.DIM, 12), c);
        JPanel exts = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (String ext : QUICK_EXTENSIONS) {
            JCheckBox cb = new JCheckBox(ext); cb.setFont(MONO.deriveFont(13f)); cb.setOpaque(false);
            cb.setFocusPainted(false); extBoxes.put(ext, cb); exts.add(cb); exts.add(Box.createHorizontalStrut(6));
        }
        c.gridx = 1; c.gridwidth = 2; grid.add(exts, c);
        c.gridx = 3; c.gridwidth = 1; c.weightx = 0.6; grid.add(extraExt, c); c.weightx = 0;
        c.gridx = 4; grid.add(label(I18n.t("mystuff.label.age"), Tone.DIM, 12), c);
        c.gridx = 5; c.weightx = 0.4; grid.add(ageCombo, c); c.weightx = 0;

        c.gridx = 0; c.gridy = 2; c.gridwidth = 1; c.insets = new Insets(4, 0, 0, 12);
        grid.add(label(I18n.t("mystuff.label.show"), Tone.DIM, 12), c);

        JPanel typeBar = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (TargetType tt : TargetType.values()) {
            JButton chip = typeChip(tt);
            typeButtons.put(tt, chip);
            typeBar.add(chip);
            typeBar.add(Box.createHorizontalStrut(8));
        }

        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.add(reset); actions.add(Box.createHorizontalStrut(8)); actions.add(apply);

        JPanel bottomRow = transparent(new BorderLayout());
        bottomRow.add(typeBar, BorderLayout.WEST);
        bottomRow.add(actions, BorderLayout.EAST);

        c.gridx = 1; c.gridy = 2; c.gridwidth = 5; c.weightx = 1; c.insets = new Insets(4, 0, 0, 0);
        grid.add(bottomRow, c);

        box.add(grid, BorderLayout.CENTER);
        for (JComboBox<?> combo : List.of(sizeCombo, ageCombo)) {
            combo.setFont(MONO.deriveFont(13f)); combo.putClientProperty("combo", true);
        }
        apply.putClientProperty("primary", true);
        return box;
    }

    private JButton typeChip(TargetType tt) {
        JButton b = new JButton(tt.label()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                boolean sel = (activeTargetType == tt);
                boolean hov = getModel().isRollover() && isEnabled();
                g2.setColor(sel ? palette.border() : palette.surface());
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(sel ? palette.accent() : palette.border());
                g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(sel ? palette.accent() : hov ? palette.text() : palette.dim());
                g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                              (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        b.setFont(MONO.deriveFont(Font.BOLD, 12f));
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(6, 10, 6, 10));
        b.setRolloverEnabled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> {
            activeTargetType = tt;
            typeButtons.values().forEach(Component::repaint);
            if (!allEntries.isEmpty()) {
                showEntries(allEntries.stream().filter(en -> activeTargetType.matches(en.directory())).toList());
            } else {
                applyFilters();
            }
        });
        return b;
    }

    public void setResults(long analyzedBytes, List<Entry> entries) {
        requireEdt();
        if (busy) throw new IllegalStateException("Operation in progress");
        if (analyzedBytes < 0) throw new IllegalArgumentException("Negative analyzed bytes");
        List<Entry> copy = List.copyOf(entries); validateIds(copy);
        this.analyzedBytes = analyzedBytes;
        allEntries.clear(); allEntries.addAll(copy);
        showEntries(copy.stream().filter(e -> activeCriteria.matches(e, Instant.now())).toList());
        updateStatistics();
    }
    public Statistics getStatistics() {
        requireEdt();
        long count = allEntries.stream().filter(e -> e.category() == Category.DUPLICATE && !e.directory()).count();
        long bytes = 0;
        for (Entry entry : allEntries) bytes = Math.addExact(bytes, entry.recoverableBytes());
        return new Statistics(analyzedBytes, count, bytes);
    }
    public FilterCriteria getCriteria() {
        Set<String> exts = new LinkedHashSet<>();
        extBoxes.forEach((ext, cb) -> { if (cb.isSelected()) exts.add(ext); });
        for (String raw : extraExt.getText().split("[,;\\s]+")) {
            String ext = raw.trim().toLowerCase(Locale.ROOT);
            if (ext.isEmpty()) continue;
            exts.add(ext.startsWith(".") ? ext : "." + ext);
        }
        return new FilterCriteria(patternField.getText(), ((SizeRange) sizeCombo.getSelectedItem()).minBytes,
            exts, ((Age) ageCombo.getSelectedItem()).months, activeTargetType);
    }
    public FilterCriteria getActiveCriteria() { return activeCriteria; }
    public List<Entry> getEntries() { requireEdt(); return List.copyOf(model.entries); }
    public List<Entry> getSelectedEntries() {
        requireEdt(); List<Entry> entries = new ArrayList<>();
        for (int row : table.getSelectedRows()) entries.add(model.entries.get(table.convertRowIndexToModel(row)));
        return List.copyOf(entries);
    }
    public Theme getTheme() { return theme; }
    public void setTheme(Theme theme) {
        requireEdt(); this.theme = Objects.requireNonNull(theme); palette = Palette.of(theme); applyTheme();
    }
    public void setFileFilterService(FileFilterService service) { requireEdt(); filterService = service; }
    public void setCompressionHandler(CompressionHandler handler) { requireEdt(); compressionHandler = handler; updateActions(); }
    public void setCleanupHandler(CleanupHandler handler) {
        requireEdt(); if (busy) throw new IllegalStateException("Operation in progress");
        cleanupHandler = handler; updateActions();
    }
    public void setConfirmation(Confirmation c) { requireEdt(); confirmation = Objects.requireNonNull(c); }
    public void setOnView(Consumer<Entry> callback) { requireEdt(); onView = Objects.requireNonNull(callback); }
    public void setOnCleanupCompleted(Consumer<CleanupResult> cb) { requireEdt(); onCleanupCompleted = Objects.requireNonNull(cb); }
    public void setOnCompressed(Consumer<Path> cb) { requireEdt(); onCompressed = Objects.requireNonNull(cb); }
    public boolean isBusy() { return busy; }

    public void applyFilters() {
        requireEdt();
        if (busy) return;
        FilterCriteria criteria = getCriteria();
        FileFilterService service = filterService;
        if (service == null) {
            activeCriteria = criteria;
            showEntries(allEntries.stream().filter(e -> criteria.matches(e, Instant.now())).toList());
            return;
        }
        setBusy(true); setStatus(I18n.t("mystuff.status.searching"), Tone.ACCENT);
        new SwingWorker<List<Entry>, Void>() {
            protected List<Entry> doInBackground() throws Exception {
                List<Entry> result = List.copyOf(Objects.requireNonNull(service.filter(criteria), "Null result"));
                validateIds(result); return result;
            }
            protected void done() {
                setBusy(false);
                try {
                    activeCriteria = criteria;
                    List<Entry> fetched = get();
                    allEntries.clear();
                    allEntries.addAll(fetched);
                    showEntries(fetched.stream().filter(e -> activeTargetType.matches(e.directory())).toList());
                    updateStatistics();
                }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); setStatus(I18n.t("mystuff.status.interrupted"), Tone.ERROR); }
                catch (ExecutionException | java.util.concurrent.CancellationException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    setStatus(I18n.t("mystuff.status.filter_err", cause.getMessage()), Tone.ERROR);
                }
            }
        }.execute();
    }
    public void resetFilters() {
        requireEdt();
        patternField.setText(""); extraExt.setText("");
        sizeCombo.setSelectedItem(SizeRange.ALL); ageCombo.setSelectedItem(Age.ANY);
        extBoxes.values().forEach(cb -> cb.setSelected(false));
        activeTargetType = TargetType.ALL;
        typeButtons.values().forEach(Component::repaint);
        applyFilters();
    }
    public void viewSelected() {
        requireEdt(); List<Entry> selected = getSelectedEntries();
        if (!busy && selected.size() == 1) onView.accept(selected.get(0));
    }
    public void compressSelected() {
        requireEdt();
        CompressionHandler handler = compressionHandler;
        List<Entry> selected = getSelectedEntries();
        if (busy || handler == null || selected.size() != 1) return;
        Entry entry = selected.get(0);
        setBusy(true); setStatus(I18n.t("mystuff.status.compressing", entry.name()), Tone.ACCENT);
        new SwingWorker<Path, Void>() {
            protected Path doInBackground() throws Exception {
                return Objects.requireNonNull(handler.compress(entry), "No .zst path returned");
            }
            protected void done() {
                setBusy(false);
                try { Path out = get(); setStatus(I18n.t("mystuff.status.compressed", out), Tone.OK); onCompressed.accept(out); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); setStatus(I18n.t("mystuff.status.compress_interrupted"), Tone.ERROR); }
                catch (ExecutionException | java.util.concurrent.CancellationException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    setStatus(I18n.t("mystuff.status.compress_err", cause.getMessage()), Tone.ERROR);
                }
            }
        }.execute();
    }
    public void requestCleanup() {
        requireEdt();
        CleanupHandler handler = cleanupHandler;
        if (busy || handler == null) return;
        List<Entry> selected = getSelectedEntries().stream().filter(Entry::cleanable).toList();
        if (selected.isEmpty()) return;
        if (!confirmation.confirm(this, selected)) { setStatus(I18n.t("mystuff.status.delete_cancelled"), Tone.DIM); return; }
        setBusy(true); setStatus(I18n.t("mystuff.status.deleting", selected.size()), Tone.ACCENT);
        new SwingWorker<CleanupResult, Void>() {
            protected CleanupResult doInBackground() throws Exception {
                CleanupResult result = Objects.requireNonNull(handler.clean(selected), "Null result");
                Set<String> requested = new HashSet<>(); selected.forEach(e -> requested.add(e.id()));
                if (!requested.containsAll(result.removedIds()) || !requested.containsAll(result.failures().keySet()))
                    throw new IllegalArgumentException("Service returned unrequested IDs");
                Map<String, String> failures = new LinkedHashMap<>(result.failures());
                for (String id : requested)
                    if (!result.removedIds().contains(id) && !failures.containsKey(id))
                        failures.put(id, "Unconfirmed result");
                return new CleanupResult(result.removedIds(), failures);
            }
            protected void done() {
                CleanupResult result = null;
                try {
                    result = get();
                    Set<String> ids = result.removedIds();
                    table.clearSelection();
                    allEntries.removeIf(e -> ids.contains(e.id()));
                    model.entries.removeIf(e -> ids.contains(e.id()));
                    model.fireTableDataChanged(); updateStatistics();
                    if (result.failures().isEmpty()) {
                        setStatus(I18n.t("mystuff.status.deleted", ids.size(), summary()), Tone.OK);
                    } else {
                        String firstErr = result.failures().values().iterator().next();
                        setStatus(I18n.t("mystuff.status.deleted_partial", ids.size(), result.failures().size(), firstErr), Tone.ERROR);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); setStatus(I18n.t("mystuff.status.delete_interrupted"), Tone.ERROR);
                } catch (ExecutionException | java.util.concurrent.CancellationException e) {
                    setStatus(I18n.t("mystuff.status.delete_unconfirmed"), Tone.ERROR);
                } finally { setBusy(false); }
                if (result != null) onCleanupCompleted.accept(result);
            }
        }.execute();
    }
    public static String summaryText(List<Entry> entries) {
        long bytes = 0; for (Entry e : entries) bytes = Math.addExact(bytes, e.bytes());
        int n = entries.size();
        if (n == 0) return I18n.t("mystuff.summary.none");
        return n == 1 ? I18n.t("mystuff.summary.one", formatBytes(bytes))
                      : I18n.t("mystuff.summary.many", n, formatBytes(bytes));
    }

    private String summary() { return summaryText(model.entries); }
    private void showEntries(List<Entry> entries) {
        table.clearSelection(); model.entries.clear(); model.entries.addAll(entries);
        model.fireTableDataChanged(); updateActions();
        if (allEntries.isEmpty() && filterService == null) setStatus(I18n.t("mystuff.status.empty"), Tone.DIM);
        else setStatus((entries.isEmpty() ? "[i] " : "[+] ") + summary(), entries.isEmpty() ? Tone.DIM : Tone.OK);
    }
    private static void validateIds(List<Entry> entries) {
        Set<String> ids = new HashSet<>();
        for (Entry e : entries) if (!ids.add(e.id())) throw new IllegalArgumentException("Duplicate ID: " + e.id());
    }
    private void setBusy(boolean value) {
        busy = value; table.setEnabled(!value); apply.setEnabled(!value); reset.setEnabled(!value);
        typeButtons.values().forEach(b -> b.setEnabled(!value));
        updateActions();
    }
    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int r = table.rowAtPoint(e.getPoint());
        if (r < 0) return;
        if (!table.isRowSelected(r)) table.setRowSelectionInterval(r, r);
        showActionsMenu(e.getX(), e.getY());
    }
    private void showActionsMenu(int x, int y) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBorder(new LineBorder(palette.border()));
        menu.setBackground(palette.surface());
        menu.add(menuItem(I18n.t("mystuff.btn.compress"), Tone.OK, compress.isEnabled(), this::compressSelected));
        menu.add(menuItem(I18n.t("mystuff.btn.view"), Tone.ACCENT, details.isEnabled(), this::viewSelected));
        menu.add(menuItem(I18n.t("mystuff.btn.delete_short"), Tone.ERROR, clean.isEnabled(), this::requestCleanup));
        menu.show(table, x, y);
    }
    private JMenuItem menuItem(String text, Tone tone, boolean enabled, Runnable action) {
        JMenuItem item = new JMenuItem(text); item.setFont(MONO.deriveFont(13f));
        item.setBackground(palette.surface()); item.setForeground(palette.color(tone)); item.setOpaque(true);
        item.setBorder(new EmptyBorder(6, 10, 6, 14)); item.setEnabled(enabled);
        item.addActionListener(e -> action.run()); return item;
    }
    private boolean confirmCleanup(Component parent, List<Entry> entries) {
        JTextArea message = new JTextArea(); message.setEditable(false);
        StringBuilder text = new StringBuilder(I18n.t("mystuff.confirm.header", entries.size())).append("\n\n");
        for (Entry entry : entries) text.append(entry.directory() ? "[DIR] " : "      ").append(entry.path()).append('\n');
        text.append('\n').append(I18n.t("mystuff.confirm.footer"));
        message.setText(text.toString()); message.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(message); scroll.setPreferredSize(new Dimension(540, 220)); skin(scroll);
        String delBtn = I18n.t("mystuff.btn.delete_short");
        String cancelBtn = I18n.t("mystuff.btn.cancel");
        JOptionPane pane = new JOptionPane(scroll, JOptionPane.WARNING_MESSAGE, JOptionPane.YES_NO_OPTION,
            null, new String[]{delBtn, cancelBtn}, cancelBtn);
        pane.setBackground(palette.bg());
        JDialog dialog = pane.createDialog(parent, I18n.t("mystuff.confirm.title"));
        dialog.setVisible(true); dialog.dispose();
        return delBtn.equals(pane.getValue());
    }
    private void showDetails(Entry entry) {
        String body = I18n.t("mystuff.details.body",
            entry.category().label(), entry.name(),
            entry.directory() ? I18n.t("mystuff.details.type.dir") : I18n.t("mystuff.details.type.file"),
            entry.path(), formatBytes(entry.bytes()), entry.bytes(),
            formatDate(entry.lastModified()), formatBytes(entry.recoverableBytes()), entry.id());
        JTextArea text = new JTextArea(body);
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(text); scroll.setPreferredSize(new Dimension(540, 240)); skin(scroll);
        JOptionPane pane = new JOptionPane(scroll, JOptionPane.PLAIN_MESSAGE);
        pane.setBackground(palette.bg());
        JDialog dialog = pane.createDialog(this, I18n.t("mystuff.details.title"));
        dialog.setVisible(true); dialog.dispose();
    }
    private void updateStatistics() {
        Statistics s = getStatistics(); analyzed.setText(formatBytes(s.analyzedBytes()));
        duplicates.setText(Long.toString(s.duplicateFiles())); potential.setText(formatBytes(s.potentialBytes()));
    }
    private void updateActions() {
        List<Entry> selected = getSelectedEntries();
        long eligible = selected.stream().filter(Entry::cleanable).count();
        selection.setText(I18n.t("mystuff.selection", selected.size(), eligible));
        details.setEnabled(!busy && selected.size() == 1);
        compress.setEnabled(!busy && compressionHandler != null && selected.size() == 1);
        clean.setEnabled(!busy && cleanupHandler != null && eligible > 0);
    }
    private void setStatus(String text, Tone tone) {
        status.setText(text); status.putClientProperty("tone", tone);
        status.setForeground(palette.color(tone)); status.setToolTipText(text);
    }
    private JPanel stat(String title, JLabel value) {
        JPanel p = transparent(new BorderLayout(0, 6)); p.putClientProperty("stat", true);
        p.add(label(title, Tone.DIM, 12), BorderLayout.NORTH); p.add(value, BorderLayout.CENTER); return p;
    }
    private static JPanel transparent(LayoutManager layout) { JPanel p = new JPanel(layout); p.setOpaque(false); return p; }
    private static JLabel label(String text, Tone tone, int size) {
        JLabel l = new JLabel(text); l.setFont(MONO.deriveFont((float) size)); l.putClientProperty("tone", tone); return l;
    }
    private static JButton button(String text, Tone tone) {
        JButton b = new JButton(text); b.setFont(MONO.deriveFont(13f)); b.putClientProperty("tone", tone);
        b.setContentAreaFilled(false); b.setOpaque(true); b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); return b;
    }
    private void applyTheme() {
        skin(this); table.getTableHeader().setBackground(palette.bg()); table.getTableHeader().setOpaque(true);
        table.setSelectionBackground(palette.border()); table.setSelectionForeground(palette.text());
        revalidate(); repaint();
    }
    private void skin(Component component) {
        component.setBackground(palette.bg()); component.setForeground(palette.text());
        if (component instanceof JComponent jc) {
            if (jc.getClientProperty("tone") instanceof Tone tone) jc.setForeground(palette.color(tone));
            if (Boolean.TRUE.equals(jc.getClientProperty("framed"))) jc.setBorder(new LineBorder(palette.border()));
            if (Boolean.TRUE.equals(jc.getClientProperty("framedPad")))
                jc.setBorder(new CompoundBorder(new LineBorder(palette.border()), new EmptyBorder(12, 14, 12, 14)));
            if (Boolean.TRUE.equals(jc.getClientProperty("stat")))
                jc.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, palette.border()), new EmptyBorder(0, 0, 10, 0)));
        }
        if (component instanceof JTable t) { t.setBackground(palette.surface()); t.setGridColor(palette.border()); }
        if (component instanceof JTextField f) {
            f.setBackground(palette.surface()); f.setForeground(palette.text()); f.setCaretColor(palette.accent());
            f.setFont(MONO.deriveFont(13f)); f.setSelectionColor(palette.border()); f.setSelectedTextColor(palette.text());
            f.setBorder(new CompoundBorder(new LineBorder(palette.border()), new EmptyBorder(7, 10, 7, 10)));
        }
        if (component instanceof JCheckBox cb) { cb.setForeground(palette.text()); cb.setIcon(new BoxIcon(false)); cb.setSelectedIcon(new BoxIcon(true)); }
        if (component instanceof JComboBox<?> combo) {
            combo.setBackground(palette.surface()); combo.setForeground(palette.text());
            combo.setBorder(new LineBorder(palette.border()));
            combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
                public void paintCurrentValueBackground(Graphics g, Rectangle b, boolean focus) {
                    g.setColor(palette.surface()); g.fillRect(b.x, b.y, b.width, b.height);
                }
                public void paintCurrentValue(Graphics g, Rectangle b, boolean focus) {
                    paintCurrentValueBackground(g, b, focus);
                    g.setFont(MONO.deriveFont(13f)); g.setColor(palette.text());
                    ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    FontMetrics fm = g.getFontMetrics(); Object v = comboBox.getSelectedItem();
                    g.drawString(v == null ? "" : v.toString(), b.x + 8, b.y + (b.height - fm.getHeight()) / 2 + fm.getAscent());
                }
                protected JButton createArrowButton() {
                    JButton b = new JButton("v"); b.setFont(MONO.deriveFont(12f));
                    b.setBackground(palette.surface()); b.setForeground(palette.accent());
                    b.setBorder(new EmptyBorder(0, 8, 0, 8)); b.setFocusPainted(false); return b;
                }
            });
            @SuppressWarnings("unchecked") JComboBox<Object> cx = (JComboBox<Object>) combo;
            cx.setRenderer(new DefaultListCellRenderer() {
                public Component getListCellRendererComponent(JList<?> list, Object v, int i, boolean sel, boolean f) {
                    super.getListCellRendererComponent(list, v, i, sel, f);
                    setFont(MONO.deriveFont(13f)); setBorder(new EmptyBorder(5, 8, 5, 8));
                    setBackground(sel ? palette.border() : palette.surface()); setForeground(palette.text());
                    list.setBackground(palette.surface()); return this;
                }
            });
        }
        if (component instanceof JTextArea text) {
            if (text.isOpaque()) text.setBackground(palette.surface());
            text.setFont(MONO.deriveFont(13f)); text.setCaretColor(palette.accent());
        }
        if (component instanceof JScrollBar bar) {
            bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
                protected void configureScrollBarColors() { thumbColor = palette.border(); trackColor = palette.bg(); }
                protected JButton createDecreaseButton(int o) { return arrow(); }
                protected JButton createIncreaseButton(int o) { return arrow(); }
                private JButton arrow() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
            });
        }
        if (component instanceof JButton b && b.getClientProperty("tone") != null) {
            boolean primary = Boolean.TRUE.equals(b.getClientProperty("primary"));
            b.setBackground(primary ? palette.accent() : palette.surface());
            if (primary) b.setForeground(palette.bg());
            b.setBorder(new CompoundBorder(new LineBorder(primary ? palette.accent() : palette.border()), new EmptyBorder(8, 12, 8, 12)));
        }
        if (component instanceof Container container && !(component instanceof JComboBox))
            for (Component child : container.getComponents()) skin(child);
    }
    private final class BoxIcon implements Icon {
        private final boolean on;
        BoxIcon(boolean on) { this.on = on; }
        public int getIconWidth() { return 16; }
        public int getIconHeight() { return 16; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(palette.surface()); g2.fillRect(x, y + 1, 14, 14);
            g2.setColor(on ? palette.accent() : palette.border()); g2.drawRect(x, y + 1, 13, 13);
            if (on) { g2.fillRect(x + 4, y + 5, 6, 6); }
            g2.dispose();
        }
    }
    private final class PlaceholderField extends JTextField {
        private final String placeholder;
        PlaceholderField(String placeholder) { this.placeholder = placeholder; }
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!getText().isEmpty() || palette == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(palette.dim()); g2.setFont(getFont());
            Insets in = getInsets(); FontMetrics fm = g2.getFontMetrics();
            g2.drawString(placeholder, in.left, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }
    private final class CellRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focused, row, column);
            Entry e = model.entries.get(t.convertRowIndexToModel(row));
            int col = t.convertColumnIndexToModel(column);
            setFont(MONO.deriveFont(13f)); setOpaque(true);
            setBackground(selected ? palette.border() : (row % 2 == 0 ? palette.surface() : palette.bg()));
            setForeground(palette.text()); setHorizontalAlignment(col == COL_SIZE ? RIGHT : LEFT);
            setBorder(new CompoundBorder(new LineBorder(focused ? palette.accent() : getBackground()), new EmptyBorder(0, 10, 0, 10)));
            switch (col) {
                case COL_NAME -> {
                    String dim = String.format("#%02x%02x%02x", palette.dim().getRed(), palette.dim().getGreen(), palette.dim().getBlue());
                    String tag = String.format("#%02x%02x%02x", catColor(e).getRed(), catColor(e).getGreen(), catColor(e).getBlue());
                    setText("<html><span style='color:" + tag + "'>[" + e.category().label() + "]</span> "
                        + esc((e.directory() ? "[dir] " : "") + e.name())
                        + "<br><span style='color:" + dim + ";font-size:90%'>" + esc(e.path().toString()) + "</span></html>");
                    setToolTipText(e.path().toString());
                }
                case COL_SIZE -> { setText(formatBytes(e.bytes())); setToolTipText(e.bytes() + " bytes"); }
                case COL_DATE -> { setText(formatDate(e.lastModified())); setToolTipText(getText()); }
                default -> { setText("[z] [v] [x]  ..."); setForeground(palette.accent());
                    setToolTipText("Click: Compress with Zstd · View · Delete"); }
            }
            return this;
        }
        private Color catColor(Entry e) {
            return switch (e.category()) {
                case LARGE -> palette.accent(); case CACHE -> palette.ok(); case COLD -> palette.dim(); default -> palette.error();
            };
        }
    }
    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private final class HeaderRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int col) {
            super.getTableCellRendererComponent(t, v, s, f, r, col);
            setFont(MONO.deriveFont(Font.BOLD, 12f)); setForeground(palette.dim()); setBackground(palette.bg());
            String arrow = "";
            if (t.getRowSorter() != null)
                for (RowSorter.SortKey key : t.getRowSorter().getSortKeys())
                    if (key.getColumn() == t.convertColumnIndexToModel(col)) arrow = key.getSortOrder() == SortOrder.ASCENDING ? " ^" : " v";
            setText(v + arrow); setHorizontalAlignment(t.convertColumnIndexToModel(col) == COL_SIZE ? RIGHT : LEFT);
            setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, palette.border()), new EmptyBorder(0, 10, 0, 10)));
            return this;
        }
    }
    private static final class EntryModel extends AbstractTableModel {
        final List<Entry> entries = new ArrayList<>();
        private final String[] columns = {
            I18n.t("mystuff.col.name"),
            I18n.t("mystuff.col.size"),
            I18n.t("mystuff.col.date"),
            I18n.t("mystuff.col.actions")
        };
        public int getRowCount() { return entries.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int c) { return columns[c]; }
        public Class<?> getColumnClass(int c) { return c == COL_SIZE ? Long.class : c == COL_DATE ? Instant.class : String.class; }
        public Object getValueAt(int row, int c) {
            Entry e = entries.get(row);
            return switch (c) { case COL_NAME -> e.name(); case COL_SIZE -> e.bytes(); case COL_DATE -> e.lastModified(); default -> ""; };
        }
        public boolean isCellEditable(int r, int c) { return false; }
    }
    public static String formatBytes(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative bytes");
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB", "PB", "EB"};
        double value = bytes; int index = -1;
        do { value /= 1024; index++; } while (value >= 1024 && index < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[index]);
    }
    public static String formatDate(Instant instant) { return DATE.format(instant.atZone(ZoneId.systemDefault())); }
    private static Font chooseFont() {
        Set<String> names = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String name : new String[]{"JetBrains Mono", "Fira Code", "Consolas", "DejaVu Sans Mono", "Menlo"})
            if (names.contains(name)) return new Font(name, Font.PLAIN, 13);
        return new Font(Font.MONOSPACED, Font.PLAIN, 13);
    }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Use from Swing EDT");
    }
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MyStuffPanel panel = new MyStuffPanel(args.length > 0 && args[0].equalsIgnoreCase("gruvbox") ? Theme.GRUVBOX : Theme.CATPPUCCIN);
            Instant now = Instant.now();
            panel.setResults(128L << 30, List.of(
                new Entry("demo-1", "backup-copy.zip", Path.of("/demo/backups/backup-copy.zip"), 850L << 20, now.minus(Duration.ofDays(400)), Category.DUPLICATE, false, 850L << 20),
                new Entry("demo-2", "node_modules", Path.of("/demo/projects/web/node_modules"), 620L << 20, now.minus(Duration.ofDays(20)), Category.CACHE, true, 620L << 20),
                new Entry("demo-3", "recording.mkv", Path.of("/demo/videos/recording.mkv"), 6L << 30, now.minus(Duration.ofDays(250)), Category.LARGE, false, 0),
                new Entry("demo-4", "ubuntu-22.04.iso", Path.of("/demo/downloads/ubuntu-22.04.iso"), 3L << 30, now.minus(Duration.ofDays(800)), Category.COLD, false, 3L << 30),
                new Entry("demo-5", "server.log", Path.of("/demo/logs/server.log"), 140L << 20, now.minus(Duration.ofDays(2)), Category.LARGE, false, 140L << 20)));
            JFrame frame = new JFrame("StorageWizard · My Stuff [DEMO]");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); frame.setContentPane(panel);
            frame.setSize(1040, 780); frame.setMinimumSize(new Dimension(760, 620));
            frame.setLocationRelativeTo(null); frame.setVisible(true);
        });
    }
}
