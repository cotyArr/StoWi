package com.storagewizard.ui;

import oshi.SystemInfo;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HWPartition;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * ScanPanel — Java 17+, OSHI 6.6.5.
 */
public final class ScanPanel extends JPanel {
    public enum Theme { CATPPUCCIN, GRUVBOX }

    public static final class StorageUnit {
        public final String device, model, kind;
        public final long bytes;
        public final long usedBytes;
        public final List<Path> mounts;

        public StorageUnit(String device, String model, String kind, long bytes, List<Path> mounts) {
            this(device, model, kind, bytes, -1L, mounts);
        }

        public StorageUnit(String device, String model, String kind, long bytes, long usedBytes, List<Path> mounts) {
            this.device = Objects.requireNonNull(device);
            this.model = Objects.requireNonNull(model);
            this.kind = Objects.requireNonNull(kind);
            this.bytes = bytes;
            this.usedBytes = usedBytes;
            this.mounts = List.copyOf(mounts);
        }

        public boolean isSelectable() { return !mounts.isEmpty(); }
        public String label() { return model + "  " + formatBytes(bytes); }

        public double usageRatio() {
            if (bytes <= 0 || usedBytes < 0) return 0.0;
            return Math.min(1.0, Math.max(0.0, (double) usedBytes / bytes));
        }
    }

    /** Inyectable: permite usar otro lector de hardware o probar sin discos reales. */
    public interface StorageProvider { List<StorageUnit> read() throws Exception; }
    
    public static final class SystemStorageProvider implements StorageProvider {
        @Override public List<StorageUnit> read() throws Exception {
            List<StorageUnit> result = new ArrayList<>();
            SystemInfo si = new SystemInfo();

            System.out.println("[DEBUG] Consultando discos con OSHI...");
            List<HWDiskStore> disks = si.getHardware().getDiskStores();
            System.out.println("[DEBUG] Discos encontrados por OSHI: " + disks.size());

            for (HWDiskStore disk : disks) {
                Set<Path> mounts = new LinkedHashSet<>();
                boolean usb = false;
                
                System.out.println("[DEBUG] Analizando disco: " + disk.getName() + " - Modelo: " + disk.getModel());
                for (HWPartition partition : disk.getPartitions()) {
                    String mp = partition.getMountPoint();
                    System.out.println("[DEBUG]   -> Partición: " + partition.getIdentification() + " | MountPoint: " + mp);
                    if (mp != null && !mp.isBlank()) {
                        mounts.add(Path.of(mp));
                    }
                    String identifier = partition.getIdentification();
                    usb |= identifier != null && identifier.toLowerCase(Locale.ROOT).contains("usb");
                }

                // Forzamos la raíz si no detectó puntos de montaje para que al menos aparezca
                if (mounts.isEmpty()) {
                    mounts.add(Path.of("/"));
                }

                long[] usage = computeMountUsage(mounts, disk.getSize());

                String model = disk.getModel() == null ? "" : disk.getModel().trim();
                String device = disk.getName();
                if (model.isBlank() || model.equalsIgnoreCase("unknown")) model = device;
                
                String kind = usb ? "USB / external" : "NVMe / disk";
                result.add(new StorageUnit(device, model, kind, usage[0], usage[1], new ArrayList<>(mounts)));
            }

            // Si por alguna razón OSHI devuelve 0 discos en tu Linux, metemos un fallback manual con la raíz
            if (result.isEmpty()) {
                System.out.println("[DEBUG] OSHI no devolvió discos, creando fallback manual del sistema de archivos.");
                List<Path> rootMount = List.of(Path.of("/"));
                long[] usage = computeMountUsage(rootMount, 500_000_000_000L);
                result.add(new StorageUnit("/dev/root", "CachyOS Root Storage", "NVMe", usage[0], usage[1], rootMount));
            }

            return List.copyOf(result);
        }

        private static long[] computeMountUsage(Collection<Path> mounts, long hardwareSize) {
            long totalFs = 0;
            long usedFs = 0;
            Set<FileStore> seenStores = new HashSet<>();

            for (Path mount : mounts) {
                try {
                    FileStore store = Files.getFileStore(mount);
                    if (seenStores.add(store)) {
                        long t = store.getTotalSpace();
                        long u = t - store.getUnallocatedSpace();
                        if (t > 0) {
                            totalFs += t;
                            usedFs += Math.max(0, u);
                        }
                    }
                } catch (Exception ignored) {}
            }

            long finalTotal = hardwareSize > 0 ? hardwareSize : totalFs;
            long finalUsed = seenStores.isEmpty() ? -1L : Math.min(usedFs, finalTotal);
            return new long[]{finalTotal, finalUsed};
        }
    }

    private static final class Palette {
        final Color bg, surface, border, text, dim, accent, green, warning, danger;
        Palette(Theme theme) {
            boolean g = theme == Theme.GRUVBOX;
            bg = new Color(g ? 0x282828 : 0x1E1E2E);
            surface = new Color(g ? 0x3C3836 : 0x313244);
            border = new Color(g ? 0x504945 : 0x45475A);
            text = new Color(g ? 0xEBDBB2 : 0xCDD6F4);
            dim = new Color(g ? 0xA89984 : 0x9399B2);
            accent = new Color(g ? 0xFABD2F : 0x89B4FA);
            green = new Color(g ? 0xB8BB26 : 0xA6E3A1);
            warning = new Color(g ? 0xFE8019 : 0xF9E2AF);
            danger = new Color(g ? 0xFB4934 : 0xF38BA8);
        }
    }

    private Palette pal;
    private final StorageProvider provider;
    private final JPanel unitList = new JPanel();
    private final JLabel status = new JLabel("[i] Reading storage devices...");
    private final JButton refresh;
    private final JButton drive;
    private Consumer<StorageUnit> onStorageSelected;
    private Runnable onGoogleDrive;
    private Runnable onHelp;
    private SwingWorker<List<StorageUnit>, Void> worker;
    private boolean loaded;

    public ScanPanel(Theme theme) { this(theme, new SystemStorageProvider()); }
    public ScanPanel(Theme theme, StorageProvider provider) {
        pal = new Palette(Objects.requireNonNull(theme));
        this.provider = Objects.requireNonNull(provider);
        setLayout(new BorderLayout(0, 20));
        setBackground(pal.bg);
        setBorder(new EmptyBorder(32, 28, 24, 28));

        JPanel header = transparent(new BorderLayout(16, 0));
        JLabel title = label("Select storage unit:", 22, pal.text);
        title.setFont(mono(Font.BOLD, 22));
        header.add(title, BorderLayout.CENTER);
        refresh = button("↻", pal.dim);
        refresh.setToolTipText("Refresh storage devices");
        refresh.getAccessibleContext().setAccessibleName("Refresh storage devices");
        refresh.setPreferredSize(new Dimension(40, 36));
        refresh.addActionListener(e -> refreshUnits());
        header.add(refresh, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        unitList.setLayout(new BoxLayout(unitList, BoxLayout.Y_AXIS));
        unitList.setBackground(pal.bg);
        JScrollPane scroll = new JScrollPane(unitList);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(pal.bg);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        JPanel middle = transparent(new BorderLayout(0, 12));
        middle.add(scroll, BorderLayout.CENTER);
        JPanel cloud = transparent(new BorderLayout());
        cloud.setBorder(new MatteBorder(1, 0, 0, 0, pal.border));
        drive = button("[cloud]  Google Drive", pal.accent);
        drive.setHorizontalAlignment(SwingConstants.LEFT);
        drive.setBorder(new EmptyBorder(16, 12, 16, 12));
        drive.addActionListener(e -> {
            if (onGoogleDrive == null) setStatus("[!] Google Drive is not configured.", pal.warning);
            else onGoogleDrive.run();
        });
        cloud.add(drive);
        middle.add(cloud, BorderLayout.SOUTH);
        add(middle, BorderLayout.CENTER);

        JPanel footer = transparent(new BorderLayout(8, 12));
        status.setFont(mono(Font.PLAIN, 11));
        status.setForeground(pal.dim);
        footer.add(status, BorderLayout.NORTH);
        JButton help = button("Help", pal.dim);
        help.setBorder(new EmptyBorder(6, 0, 0, 0));
        help.setHorizontalAlignment(SwingConstants.LEFT);
        help.addActionListener(e -> {
            if (onHelp != null) onHelp.run();
            else showHelp();
        });
        JPanel helpLine = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        helpLine.add(help);
        footer.add(helpLine, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
    }

    @Override public void addNotify() {
        super.addNotify();
        if (!loaded) { loaded = true; refreshUnits(); }
    }

    /** Llamar en el EDT; la consulta de hardware se ejecuta fuera del EDT. */
    public void refreshUnits() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshUnits);
            return;
        }
        if (worker != null && !worker.isDone()) return;
        refresh.setEnabled(false);
        unitList.removeAll();
        setStatus("[i] Reading storage devices...", pal.dim);
        unitList.revalidate(); unitList.repaint();
        worker = new SwingWorker<>() {
            @Override protected List<StorageUnit> doInBackground() throws Exception { return provider.read(); }
            @Override protected void done() {
                refresh.setEnabled(true);
                try {
                    List<StorageUnit> units = get();
                    renderUnits(units);
                    long mounted = units.stream().filter(StorageUnit::isSelectable).count();
                    setStatus(units.isEmpty() ? "[!] No physical disks detected."
                            : "[+] " + units.size() + " disks found · " + mounted + " available", 
                            units.isEmpty() ? pal.warning : pal.green);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    setStatus("[!] Storage detection interrupted.", pal.warning);
                } catch (ExecutionException | RuntimeException e) {
                    setStatus("[!] Cannot read storage devices. Retry or check permissions.", pal.warning);
                    status.setToolTipText(e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                }
            }
        };
        worker.execute();
    }

    private void renderUnits(List<StorageUnit> units) {
        unitList.removeAll();
        for (StorageUnit unit : units) {
            JPanel row = transparent(new BorderLayout(0, 6));
            row.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, pal.border),
                    new EmptyBorder(8, 0, 14, 0)));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 118));
            row.setPreferredSize(new Dimension(380, 118));

            JButton select = button("[disk]  " + unit.label(), pal.text);
            select.setHorizontalAlignment(SwingConstants.LEFT);
            select.setBorder(new EmptyBorder(8, 12, 8, 12));
            select.setEnabled(unit.isSelectable());
            select.setToolTipText(unit.label() + " — " + unit.device);
            select.addActionListener(e -> {
                if (onStorageSelected == null) setStatus("[!] Scan engine is not configured.", pal.warning);
                else onStorageSelected.accept(unit);
            });
            row.add(select, BorderLayout.NORTH);

            String paths = unit.mounts.stream().map(Path::toString).reduce((a, b) -> a + ", " + b).orElse("not mounted");
            
            JPanel infoAndBar = transparent(new BorderLayout(0, 6));
            infoAndBar.setBorder(new EmptyBorder(0, 12, 0, 12));

            JPanel metaRow = transparent(new BorderLayout(8, 0));
            JLabel details = label(unit.kind + " · " + paths, 11, pal.dim);
            details.setToolTipText(paths);
            metaRow.add(details, BorderLayout.WEST);

            if (unit.usedBytes >= 0 && unit.bytes > 0) {
                int pct = (int) Math.round(unit.usageRatio() * 100);
                long freeBytes = Math.max(0, unit.bytes - unit.usedBytes);
                String usageText = formatBytes(unit.usedBytes) + " used · " + formatBytes(freeBytes) + " free (" + pct + "%)";
                JLabel usageLabel = label(usageText, 11, pct >= 90 ? pal.danger : pct >= 75 ? pal.warning : pal.dim);
                metaRow.add(usageLabel, BorderLayout.EAST);
            }

            infoAndBar.add(metaRow, BorderLayout.NORTH);
            infoAndBar.add(new UsageBar(unit), BorderLayout.SOUTH);

            row.add(infoAndBar, BorderLayout.CENTER);
            unitList.add(row);
        }
        unitList.add(Box.createVerticalGlue());
        unitList.revalidate(); unitList.repaint();
    }

    /** Barra visual de capacidad bicolor (espacio usado vs libre) con soporte de temas. */
    private final class UsageBar extends JComponent {
        private final StorageUnit unit;

        UsageBar(StorageUnit unit) {
            this.unit = unit;
            setPreferredSize(new Dimension(0, 8));
            setMinimumSize(new Dimension(100, 8));
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth();
            int h = getHeight();

            // Fondo (espacio libre)
            g2.setColor(pal.surface);
            g2.fillRect(0, 0, w, h);

            // Relleno (espacio usado)
            if (unit.usedBytes >= 0 && unit.bytes > 0) {
                double ratio = unit.usageRatio();
                int fillW = (int) Math.round((w - 2) * ratio);
                Color barColor = ratio >= 0.90 ? pal.danger
                               : ratio >= 0.75 ? pal.warning
                               : pal.accent;
                g2.setColor(barColor);
                g2.fillRect(1, 1, Math.max(0, fillW), h - 2);
            }

            // Borde estilo consola
            g2.setColor(pal.border);
            g2.drawRect(0, 0, w - 1, h - 1);
            g2.dispose();
        }
    }

    public void setOnStorageSelected(Consumer<StorageUnit> callback) { onStorageSelected = callback; }
    public void setOnGoogleDrive(Runnable callback) { onGoogleDrive = callback; }
    public void setOnHelp(Runnable callback) { onHelp = callback; }

    private void showHelp() {
        JTextArea text = new JTextArea("Select a mounted disk to send it to the scan engine.\n\n"
                + "Connect external storage, then use Refresh. Disks without mounted volumes cannot be scanned.\n\n"
                + "Google Drive needs your application's Google connection flow. No connection is made by this panel.");
        text.setFont(mono(Font.PLAIN, 13)); text.setForeground(pal.text); text.setBackground(pal.bg);
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        text.setBorder(new EmptyBorder(18, 18, 18, 18));
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Help", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.add(text); dialog.setSize(490, 280); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    private JButton button(String text, Color color) {
        JButton b = new JButton(text);
        b.setFont(mono(Font.PLAIN, 13)); b.setForeground(color); b.setBackground(pal.surface);
        b.setBorder(new EmptyBorder(8, 12, 8, 12)); b.setFocusPainted(false);
        b.setContentAreaFilled(false); b.setOpaque(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { if (b.isEnabled()) { b.setOpaque(true); b.repaint(); } }
            @Override public void mouseExited(MouseEvent e) { b.setOpaque(false); b.repaint(); }
        });
        b.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { b.setContentAreaFilled(true); b.repaint(); }
            @Override public void focusLost(FocusEvent e) { b.setContentAreaFilled(false); b.repaint(); }
        });
        return b;
    }
    private JLabel label(String text, int size, Color color) {
        JLabel l = new JLabel(text); l.setFont(mono(Font.PLAIN, size)); l.setForeground(color); return l;
    }
    private void setStatus(String text, Color color) { status.setText(text); status.setForeground(color); status.setToolTipText(text); }
    private static JPanel transparent(LayoutManager layout) { JPanel p = new JPanel(layout); p.setOpaque(false); return p; }
    private static Font mono(int style, int size) {
        Set<String> available = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String name : new String[]{"JetBrains Mono", "Fira Code", "Consolas", "DejaVu Sans Mono", "Menlo"})
            if (available.contains(name)) return new Font(name, style, size);
        return new Font(Font.MONOSPACED, style, size);
    }
    
    public static String formatBytes(long bytes) {
        if (bytes < 0) return "capacity unknown";
        if (bytes == 0) return "0.0 MB";
        if (bytes >= 1_000_000_000_000L) return String.format(Locale.ROOT, "%.1f TB", bytes / 1e12);
        if (bytes >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1e6);
    }

    /** Cambia el tema del panel en tiempo real. */
    public void setTheme(Theme theme) {
        Objects.requireNonNull(theme);
        Palette oldPal = this.pal;
        this.pal = new Palette(theme);

        setBackground(pal.bg);
        unitList.setBackground(pal.bg);

        // Actualizamos directamente los botones fijos del panel
        drive.setForeground(pal.accent);
        drive.setBackground(pal.surface);
        refresh.setForeground(pal.dim);
        refresh.setBackground(pal.surface);

        // Mapeamos todos los colores del árbol (incluyendo "Select storage unit:", "Help" y bordes)
        Map<Color, Color> colorMap = new HashMap<>();
        colorMap.put(oldPal.bg, pal.bg);
        colorMap.put(oldPal.surface, pal.surface);
        colorMap.put(oldPal.border, pal.border);
        colorMap.put(oldPal.text, pal.text);
        colorMap.put(oldPal.dim, pal.dim);
        colorMap.put(oldPal.accent, pal.accent);
        colorMap.put(oldPal.green, pal.green);
        colorMap.put(oldPal.warning, pal.warning);
        colorMap.put(oldPal.danger, pal.danger);

        recolorScanTree(this, colorMap);

        // Volvemos a renderizar la lista de discos con la nueva paleta
        if (worker != null && worker.isDone()) {
            try {
                List<StorageUnit> currentUnits = worker.get();
                renderUnits(currentUnits);
            } catch (Exception ignored) {}
        }

        revalidate();
        repaint();
    }

    private static void recolorScanTree(Component c, Map<Color, Color> map) {
        if (c.getBackground() != null && map.containsKey(c.getBackground())) {
            c.setBackground(map.get(c.getBackground()));
        }
        if (c.getForeground() != null && map.containsKey(c.getForeground())) {
            c.setForeground(map.get(c.getForeground()));
        }
        if (c instanceof JScrollPane sp && sp.getViewport() != null) {
            Color vpBg = sp.getViewport().getBackground();
            if (vpBg != null && map.containsKey(vpBg)) {
                sp.getViewport().setBackground(map.get(vpBg));
            }
        }
        if (c instanceof JComponent jc && jc.getBorder() != null) {
            Border b = jc.getBorder();
            if (b instanceof MatteBorder mb && map.containsKey(mb.getMatteColor())) {
                Insets in = mb.getBorderInsets();
                jc.setBorder(new MatteBorder(in.top, in.left, in.bottom, in.right, map.get(mb.getMatteColor())));
            }
        }
        if (c instanceof Container cont) {
            for (Component child : cont.getComponents()) {
                recolorScanTree(child, map);
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ScanPanel p = new ScanPanel(args.length > 0 && args[0].equalsIgnoreCase("gruvbox") ? Theme.GRUVBOX : Theme.CATPPUCCIN);
            p.setOnStorageSelected(unit -> p.setStatus("[+] Selected: " + unit.model, p.pal.green));
            JFrame frame = new JFrame("StorageWizard · Scan");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(p); frame.setSize(740, 520); frame.setMinimumSize(new Dimension(560, 380));
            frame.setLocationRelativeTo(null); frame.setVisible(true);
        });
    }
}
