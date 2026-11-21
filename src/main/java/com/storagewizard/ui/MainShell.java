package com.storagewizard.ui;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.nio.file.*;
import java.io.IOException;

/**
 * MainShell — ventana principal con estética de consola.
 *
 * Menú lateral izquierdo (Scan, My stuff, Projects, Options) y panel
 * central de bienvenida con mensajes de estado (espacio ganado, etc.).
 *
 * Tema: new MainShell(MainShell.Theme.CATPPUCCIN) o Theme.GRUVBOX.
 */
public class MainShell extends JPanel {

  public enum Theme { CATPPUCCIN, GRUVBOX }
  public enum Tone { OK, WARN, DANGER, INFO }

  /** Aviso cuando el usuario cambia de sección. */
  public interface OnNavigate { void onNavigate(String key); }

  // ---------------- Paletas ----------------

  public static final class Palette {
    public final Color bg, surface, border, fg, fgDim, accent, ok, warn, danger;

    Palette(Color bg, Color surface, Color border, Color fg, Color fgDim,
            Color accent, Color ok, Color warn, Color danger) {
      this.bg = bg; this.surface = surface; this.border = border;
      this.fg = fg; this.fgDim = fgDim; this.accent = accent;
      this.ok = ok; this.warn = warn; this.danger = danger;
    }
  }

  public static Palette catppuccin() {
    return new Palette(
        hex(0x1E1E2E), hex(0x313244), hex(0x45475A), // bg, surface, borde
        hex(0xCDD6F4), hex(0x9399B2),                // texto, texto tenue
        hex(0x89B4FA),                               // acento (azul)
        hex(0xA6E3A1), hex(0xF9E2AF), hex(0xF38BA8)); // ok, warn, peligro
  }

  public static Palette gruvbox() {
    return new Palette(
        hex(0x282828), hex(0x3C3836), hex(0x504945),
        hex(0xEBDBB2), hex(0xA89984),
        hex(0xFABD2F),                               // acento (amarillo)
        hex(0xB8BB26), hex(0xFE8019), hex(0xFB4934));
  }

  private static Color hex(int v) { return new Color(v); }

  // ---------------- Estado ----------------

  private Palette pal;
  private final Font fontTitle, fontMono, fontSmall;

  private final CardLayout cards = new CardLayout();
  private final JPanel center = new JPanel(cards);
  private final Map<String, NavButton> nav = new LinkedHashMap<>();
  private final Map<String, JComponent> panels = new LinkedHashMap<>();
  private final JPanel statusBox = new JPanel();
  private JPanel reqBox;
  private JLabel reqHeader, reqJava, reqFfmpeg, reqZstd;
  private JTextArea installGuide;
  private boolean hasFfmpeg, hasZstd;
  private OnNavigate onNavigate;
  private String currentKey;

  public MainShell(Theme theme) {
    this.pal = (theme == Theme.GRUVBOX) ? gruvbox() : catppuccin();

    fontTitle = pickFont(Font.BOLD, 20);
    fontMono  = pickFont(Font.PLAIN, 13);
    fontSmall = pickFont(Font.PLAIN, 11);

    setLayout(new BorderLayout());
    setBackground(pal.bg);

    JPanel sidebar = buildSidebar();

    center.setOpaque(false);
    center.setBorder(new EmptyBorder(24, 28, 24, 28));

    JComponent welcome = buildWelcome();
    center.add(welcome, "welcome");
    panels.put("welcome", welcome);

    String[][] sections = {
        {"scan", "Scan"},
        {"mystuff", "My stuff"},
        {"projects", "Projects"},
        {"media", "Media"},
        {"options", "Options"},
    };
    for (String[] s : sections) {
      JComponent ph = placeholder(s[1]);
      center.add(ph, s[0]);
      panels.put(s[0], ph);
      addNav(sidebar, s[0], s[1]);
    }

    add(sidebar, BorderLayout.WEST);
    add(center, BorderLayout.CENTER);

    showPanel("welcome");
  }

  // ---------------- Sidebar ----------------

  private JPanel buildSidebar() {
    JPanel side = new JPanel();
    side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
    side.setBackground(pal.bg);
    side.setBorder(new CompoundBorder(
        new MatteBorder(0, 0, 0, 1, pal.border),
        new EmptyBorder(18, 10, 18, 10)));

    // El logo vuelve a la pantalla de bienvenida.
    JLabel logo = new JLabel("STORAGEWIZARD");
    logo.setFont(pickFont(Font.BOLD, 13));
    logo.setForeground(pal.accent);
    logo.setAlignmentX(Component.LEFT_ALIGNMENT);
    logo.setBorder(new EmptyBorder(0, 6, 14, 6));
    logo.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    logo.addMouseListener(new MouseAdapter() {
      @Override public void mouseClicked(MouseEvent e) { showPanel("welcome"); }
    });
    side.add(logo);

    side.add(Box.createVerticalStrut(2));
    return side;
  }

  private void addNav(JComponent side, String key, String label) {
    NavButton b = new NavButton(key, label);
    b.setAlignmentX(Component.LEFT_ALIGNMENT);
    side.add(b);
    side.add(Box.createVerticalStrut(2));
    nav.put(key, b);
  }

  private final class NavButton extends JComponent {
    final String key;
    final String label;
    boolean selected;
    boolean hover;

    NavButton(String key, String label) {
      this.key = key;
      this.label = label;
      setPreferredSize(new Dimension(190, 34));
      setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
      setOpaque(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      addMouseListener(new MouseAdapter() {
        @Override public void mousePressed(MouseEvent e) { showPanel(key); }
        @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
        @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
      });
    }

    @Override protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                          RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      int w = getWidth(), h = getHeight();

      g2.setColor(selected ? pal.surface : hover ? mix(pal.surface, pal.bg) : pal.bg);
      g2.fillRect(0, 0, w, h);

      if (selected) {
        g2.setColor(pal.accent);
        g2.fillRect(0, 0, 3, h);
      }

      g2.setFont(fontMono);
      g2.setColor(selected ? pal.accent : hover ? pal.fg : pal.fgDim);
      String prefix = selected ? "> " : "  ";
      FontMetrics fm = g2.getFontMetrics();
      g2.drawString(prefix + label, 14, (h - fm.getHeight()) / 2 + fm.getAscent());
      g2.dispose();
    }
  }

  // ---------------- Panel central ----------------
    
private JComponent buildWelcome() {
    JPanel p = new JPanel();
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    p.setOpaque(false);

    JLabel title = new JLabel(I18n.t("welcome.title"));
    title.setFont(fontTitle);
    title.setForeground(pal.fg);
    title.setAlignmentX(Component.LEFT_ALIGNMENT);

    JLabel sub = new JLabel(I18n.t("welcome.subtitle"));
    sub.setFont(fontSmall);
    sub.setForeground(pal.fgDim);
    sub.setAlignmentX(Component.LEFT_ALIGNMENT);

    // --- System requirements card ---
    reqBox = new JPanel();
    reqBox.setLayout(new BoxLayout(reqBox, BoxLayout.Y_AXIS));
    reqBox.setBackground(pal.surface);
    reqBox.setAlignmentX(Component.LEFT_ALIGNMENT);
    reqBox.setBorder(new CompoundBorder(
        new LineBorder(pal.border),
        new EmptyBorder(12, 14, 12, 14)));

    reqHeader = new JLabel(I18n.t("req.header"));
    reqHeader.setFont(pickFont(Font.BOLD, 13));
    reqHeader.setForeground(pal.accent);
    reqHeader.setAlignmentX(Component.LEFT_ALIGNMENT);

    String javaVer = System.getProperty("java.version", "17+");
    reqJava = new JLabel(I18n.t("req.java", javaVer));
    reqJava.setFont(fontMono);
    reqJava.setForeground(pal.ok);
    reqJava.setAlignmentX(Component.LEFT_ALIGNMENT);

    reqFfmpeg = new JLabel(I18n.t("req.ffmpeg.checking"));
    reqFfmpeg.setFont(fontMono);
    reqFfmpeg.setForeground(pal.fgDim);
    reqFfmpeg.setAlignmentX(Component.LEFT_ALIGNMENT);

    reqZstd = new JLabel(I18n.t("req.zstd.checking"));
    reqZstd.setFont(fontMono);
    reqZstd.setForeground(pal.fgDim);
    reqZstd.setAlignmentX(Component.LEFT_ALIGNMENT);

    installGuide = new JTextArea(I18n.t("req.install.guide"));
    installGuide.setEditable(false);
    installGuide.setFont(fontSmall);
    installGuide.setBackground(pal.bg);
    installGuide.setForeground(pal.fgDim);
    installGuide.setBorder(new EmptyBorder(8, 10, 8, 10));
    installGuide.setAlignmentX(Component.LEFT_ALIGNMENT);
    installGuide.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));

    reqBox.add(reqHeader);
    reqBox.add(Box.createVerticalStrut(8));
    reqBox.add(reqJava);
    reqBox.add(Box.createVerticalStrut(4));
    reqBox.add(reqFfmpeg);
    reqBox.add(Box.createVerticalStrut(4));
    reqBox.add(reqZstd);
    reqBox.add(Box.createVerticalStrut(10));
    reqBox.add(installGuide);
    reqBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 230));

    statusBox.setLayout(new BoxLayout(statusBox, BoxLayout.Y_AXIS));
    statusBox.setOpaque(false);
    statusBox.setAlignmentX(Component.LEFT_ALIGNMENT);
    statusBox.setBorder(new EmptyBorder(16, 0, 0, 0));

    p.add(title);
    p.add(Box.createVerticalStrut(6));
    p.add(sub);
    p.add(Box.createVerticalStrut(16));
    p.add(reqBox);
    p.add(Box.createVerticalStrut(12));
    p.add(statusBox);
    p.add(Box.createVerticalGlue());

    checkDependenciesAsync();
    return p;
  }

  private void checkDependenciesAsync() {
    new SwingWorker<boolean[], Void>() {
      @Override protected boolean[] doInBackground() {
        return new boolean[] {
            checkBinary("ffmpeg", "-version"),
            checkBinary("zstd", "--version")
        };
      }
      @Override protected void done() {
        try {
          boolean[] res = get();
          hasFfmpeg = res[0];
          hasZstd = res[1];
          reqFfmpeg.setText(I18n.t(hasFfmpeg ? "req.ffmpeg.ok" : "req.ffmpeg.missing"));
          reqFfmpeg.setForeground(hasFfmpeg ? pal.ok : pal.warn);
          reqZstd.setText(I18n.t(hasZstd ? "req.zstd.ok" : "req.zstd.missing"));
          reqZstd.setForeground(hasZstd ? pal.ok : pal.warn);
        } catch (Exception ignored) {}
      }
    }.execute();
  }
  private static boolean checkBinary(String... cmd) {
    try {
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      return p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }


private JComponent placeholder(String label) {
    JPanel p = new JPanel();
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    p.setOpaque(false);

    JLabel t = new JLabel(label);
    t.setFont(fontTitle);
    t.setForeground(pal.fg);
    t.setAlignmentX(Component.LEFT_ALIGNMENT);

    JLabel hint = new JLabel(I18n.t("shell.placeholder", label));
    hint.setFont(fontSmall);
    hint.setForeground(pal.fgDim);
    hint.setAlignmentX(Component.LEFT_ALIGNMENT);

    p.add(Box.createVerticalGlue());
    p.add(t);
    p.add(Box.createVerticalStrut(6));
    p.add(hint);
    p.add(Box.createVerticalGlue());
    return p;
  }
  // ---------------- API pública ----------------

  /** Reemplaza el placeholder de una sección por contenido real. */
  public void attach(String key, JComponent panel) {
    JComponent old = panels.get(key);
    if (old == null) throw new IllegalArgumentException("Sección desconocida: " + key);
    center.remove(old);
    center.add(panel, key);
    panels.put(key, panel);
    center.revalidate();
    center.repaint();
    cards.show(center, currentKey);
  }

  /** Añade una línea de estado al bloque de bienvenida. */
  public void pushStatus(String text, Tone tone) {
    JLabel l = new JLabel(prefix(tone) + text);
    l.setFont(fontMono);
    l.setForeground(color(tone));
    l.setAlignmentX(Component.LEFT_ALIGNMENT);
    statusBox.add(l);
    statusBox.add(Box.createVerticalStrut(4));
    statusBox.revalidate();
    statusBox.repaint();
  }

  /** Limpia las líneas de estado del bloque de bienvenida. */
  public void clearStatus() {
    statusBox.removeAll();
    statusBox.revalidate();
    statusBox.repaint();
  }

  public void showPanel(String key) {
    currentKey = key;
    cards.show(center, key);
    for (Map.Entry<String, NavButton> e : nav.entrySet()) {
      e.getValue().selected = e.getKey().equals(key);
      e.getValue().repaint();
    }
    
    // Si entramos a la sección "scan", forzamos la lectura de discos de inmediato
    if ("scan".equals(key)) {
      JComponent panel = panels.get("scan");
      if (panel instanceof ScanPanel scanPanel) {
        scanPanel.refreshUnits();
      }
    }

    if (onNavigate != null) onNavigate.onNavigate(key);
  }

  public void setOnNavigate(OnNavigate l) { this.onNavigate = l; }

  // ---------------- Detalles ----------------

  private static String prefix(Tone t) {
    switch (t) {
      case OK:     return "[+] ";
      case WARN:   return "[!] ";
      case DANGER: return "[x] ";
      default:     return "[i] ";
    }
  }

  private Color color(Tone t) {
    switch (t) {
      case OK:     return pal.ok;
      case WARN:   return pal.warn;
      case DANGER: return pal.danger;
      default:     return pal.fgDim;
    }
  }

  private static Color mix(Color a, Color b) {
    return new Color((a.getRed() + b.getRed()) / 2,
                     (a.getGreen() + b.getGreen()) / 2,
                     (a.getBlue() + b.getBlue()) / 2);
  }

  private static Font pickFont(int style, int size) {
    String[] candidates = { "JetBrains Mono", "Fira Code", "Consolas", "DejaVu Sans Mono", "Menlo" };
    Set<String> available = new HashSet<>(Arrays.asList(
        GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
    for (String c : candidates) if (available.contains(c)) return new Font(c, style, size);
    return new Font(Font.MONOSPACED, style, size);
  }

  /** Cambia el tema de toda la aplicación en tiempo real. */
  public void setTheme(Theme theme) {
    this.pal = (theme == Theme.GRUVBOX) ? gruvbox() : catppuccin();
    setBackground(pal.bg);
    center.setBackground(pal.bg);

    // 1. Pintar la barra lateral (sidebar) y el título STORAGEWIZARD con el nuevo tema
    for (Component comp : getComponents()) {
      if (comp instanceof JPanel side && comp != center) {
        side.setBackground(pal.bg);
        side.setBorder(new CompoundBorder(
            new MatteBorder(0, 0, 0, 1, pal.border),
            new EmptyBorder(18, 10, 18, 10)));
        for (Component c : side.getComponents()) {
          if (c instanceof JLabel logo) logo.setForeground(pal.accent);
        }
      }
    }

    // 2. Actualizar textos de la pantalla Welcome
    JComponent welcome = panels.get("welcome");
    if (welcome instanceof JPanel wp) {
      wp.setBackground(pal.bg);
      for (Component c : wp.getComponents()) {
        if (c instanceof JLabel lbl) {
          lbl.setForeground(lbl.getFont().getSize() > 20 ? pal.fg : pal.fgDim);
        }
      }
    }
    
    if (reqBox != null) {
      reqBox.setBackground(pal.surface);
      reqBox.setBorder(new CompoundBorder(
          new LineBorder(pal.border),
          new EmptyBorder(12, 14, 12, 14)));
      reqHeader.setForeground(pal.accent);
      reqJava.setForeground(pal.ok);
      reqFfmpeg.setForeground(hasFfmpeg ? pal.ok : pal.warn);
      reqZstd.setForeground(hasZstd ? pal.ok : pal.warn);
      installGuide.setBackground(pal.bg);
      installGuide.setForeground(pal.fgDim);
    }

    // 3. Propagar el tema a todos los paneles (SIN llamar a updateComponentTreeUI)
    for (Map.Entry<String, JComponent> entry : panels.entrySet()) {
      JComponent panel = entry.getValue();
      if (panel instanceof MyStuffPanel ms) {
        ms.setTheme(theme == Theme.GRUVBOX ? MyStuffPanel.Theme.GRUVBOX : MyStuffPanel.Theme.CATPPUCCIN);
      } else if (panel instanceof ScanPanel sp) {
        sp.setTheme(theme == Theme.GRUVBOX ? ScanPanel.Theme.GRUVBOX : ScanPanel.Theme.CATPPUCCIN);
      } else if (panel instanceof OptionsPanel op) {
        op.setTheme(theme == Theme.GRUVBOX ? OptionsPanel.Theme.GRUVBOX : OptionsPanel.Theme.CATPPUCCIN);
      } else if (panel instanceof ProjectsPanel pp) {
        pp.setTheme(theme == Theme.GRUVBOX ? ProjectsPanel.Theme.GRUVBOX : ProjectsPanel.Theme.CATPPUCCIN);
      } else if (panel instanceof MediaOptimizerPanel mp) {
        mp.setTheme(theme == Theme.GRUVBOX ? MediaOptimizerPanel.Theme.GRUVBOX : MediaOptimizerPanel.Theme.CATPPUCCIN);
      }
    }

    revalidate();
    repaint();
  }

  private static final java.nio.file.Path CONFIG_FILE = java.nio.file.Paths.get(
      System.getProperty("user.home"), ".config", "storagewizard", "settings.properties");

  private static OptionsPanel.Settings loadSavedSettings() {
    java.util.Properties props = new java.util.Properties();
    if (java.nio.file.Files.isRegularFile(CONFIG_FILE)) {
      try (java.io.InputStream in = java.nio.file.Files.newInputStream(CONFIG_FILE)) {
        props.load(in);
      } catch (java.io.IOException ignored) {}
    }
    String themeStr = props.getProperty("theme", "CATPPUCCIN");
    OptionsPanel.Theme theme = "GRUVBOX".equalsIgnoreCase(themeStr)
        ? OptionsPanel.Theme.GRUVBOX
        : OptionsPanel.Theme.CATPPUCCIN;
    String language = props.getProperty("language", "en");

    java.util.List<java.nio.file.Path> dirs = new java.util.ArrayList<>();
    String rawDirs = props.getProperty("scanDirectories", "");
    if (!rawDirs.isBlank()) {
      for (String part : rawDirs.split(java.io.File.pathSeparator)) {
        if (!part.isBlank()) {
          java.nio.file.Path p = java.nio.file.Paths.get(part.trim());
          if (java.nio.file.Files.isDirectory(p)) dirs.add(p);
        }
      }
    }
    return new OptionsPanel.Settings(dirs, theme, language);
  }

  private static void saveSettings(OptionsPanel.Settings s) {
    try {
      java.nio.file.Files.createDirectories(CONFIG_FILE.getParent());
      java.util.Properties props = new java.util.Properties();
      props.setProperty("theme", s.theme().name());
      props.setProperty("language", s.language());
      String joinedDirs = s.scanDirectories().stream()
          .map(java.nio.file.Path::toString)
          .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
      props.setProperty("scanDirectories", joinedDirs);
      try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(CONFIG_FILE)) {
        props.store(out, "StorageWizard Configuration");
      }
    } catch (java.io.IOException ignored) {}
  }




// ---------------- Demo ----------------
  public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> {
        JFrame f = new JFrame("StorageWizard");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        MainShell shell = new MainShell(Theme.CATPPUCCIN);

        // Referencia dinámica al directorio seleccionado (por defecto user.home, cambia al elegir un disco/pendrive)
        final java.util.concurrent.atomic.AtomicReference<java.nio.file.Path> activeRoot =
            new java.util.concurrent.atomic.AtomicReference<>(java.nio.file.Path.of(System.getProperty("user.home")));

        // 1. Instanciamos todos los paneles para poder comunicarlos entre sí
        ScanPanel scanPanel = new ScanPanel(ScanPanel.Theme.CATPPUCCIN);
        MyStuffPanel myStuff = new MyStuffPanel(MyStuffPanel.Theme.CATPPUCCIN);
        ProjectsPanel projectsPanel = new ProjectsPanel(ProjectsPanel.Theme.CATPPUCCIN);
        MediaOptimizerPanel mediaPanel = new MediaOptimizerPanel(MediaOptimizerPanel.Theme.CATPPUCCIN);
        OptionsPanel optionsPanel = new OptionsPanel(OptionsPanel.Theme.CATPPUCCIN);

        // 2. Servicio de filtrado con exclusión de rutas críticas/sistema y soporte de directorios
        var backendFilterService = new Object() {
            // Rutas absolutas del sistema operativo que nunca deben escanearse ni borrarse
            private static final Set<String> SYSTEM_PATHS = Set.of(
                "/proc", "/sys", "/dev", "/run", "/boot", "/usr", "/etc", "/lib", "/lib64", "/bin", "/sbin", "/var/lib"
            );

            // Carpetas de configuración, toolchains y cachés críticas del usuario
            private static final Set<String> PROTECTED_USER_DIRS = Set.of(
                ".arduino15", ".config", ".local", ".ssh", ".gnupg", ".pki",
                ".rustup", ".cargo", ".m2", ".gradle", ".nvm", ".npm",
                ".steam", ".wine", ".mozilla", ".zen", ".git", " Fightcade"
            );

            private boolean shouldSkipDir(java.nio.file.Path dir, java.nio.file.Path root, String searchPattern) {
                String abs = dir.toAbsolutePath().toString();
                for (String sys : SYSTEM_PATHS) {
                    if (abs.equals(sys) || abs.startsWith(sys + "/")) return true;
                }
                if (dir.equals(root) || dir.getFileName() == null) return false;

                String name = dir.getFileName().toString();
                // Si el usuario buscó explícitamente ese término (ej. ".terraform" o ".cache"), permitimos entrar
                if (!searchPattern.isEmpty() && name.toLowerCase(Locale.ROOT).contains(searchPattern)) {
                    return false;
                }
                // Saltamos carpetas protegidas o cualquier dotfile oculto en la raíz del home
                if (PROTECTED_USER_DIRS.contains(name)) return true;
                if (name.startsWith(".") && !name.equals(".cache") && !name.equals(".terraform") && !name.equals(".venv")) {
                    return true;
                }
                return false;
            }

            private long dirSize(java.nio.file.Path dir) {
                java.util.concurrent.atomic.AtomicLong total = new java.util.concurrent.atomic.AtomicLong();
                try {
                    java.nio.file.Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<>() {
                        @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path f, java.nio.file.attribute.BasicFileAttributes a) {
                            total.addAndGet(a.size());
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                        @Override public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path f, java.io.IOException e) {
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
                } catch (Exception ignored) {}
                return total.get();
            }

            public java.util.List<MyStuffPanel.Entry> filterDirectory(java.nio.file.Path root, MyStuffPanel.FilterCriteria criteria) {
                java.util.List<MyStuffPanel.Entry> entries = new java.util.ArrayList<>();
                java.time.Instant now = java.time.Instant.now();
                String pat = criteria.pattern() == null ? "" : criteria.pattern().trim().toLowerCase(Locale.ROOT);
                MyStuffPanel.TargetType targetType = criteria.targetType();

                try {
                    if (java.nio.file.Files.exists(root)) {
                        java.nio.file.Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                            @Override
                            public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                                if (entries.size() >= 500) return java.nio.file.FileVisitResult.TERMINATE;
                                if (java.nio.file.Files.isSymbolicLink(dir)) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                                if (shouldSkipDir(dir, root, pat)) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                                if (dir.equals(root)) return java.nio.file.FileVisitResult.CONTINUE;

                                // Evitamos listar elementos donde el usuario ni siquiera tiene permiso de escritura en la carpeta padre
                                // if (!java.nio.file.Files.isWritable(dir)) return java.nio.file.FileVisitResult.SKIP_SUBTREE;

                                String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString();
                                boolean nameMatches = pat.isEmpty()
                                    ? (targetType == MyStuffPanel.TargetType.DIRECTORIES && dir.getParent().equals(root))
                                    : dirName.toLowerCase(Locale.ROOT).contains(pat);

                                if (targetType != MyStuffPanel.TargetType.FILES && criteria.extensions().isEmpty() && nameMatches) {
                                    long size = dirSize(dir);
                                    if (size >= criteria.minBytes()) {
                                        java.time.Instant lastMod = attrs.lastModifiedTime().toInstant();
                                        MyStuffPanel.Category cat = size > (100L << 20) ? MyStuffPanel.Category.LARGE : MyStuffPanel.Category.CACHE;
                                        entries.add(new MyStuffPanel.Entry(
                                            dir.toAbsolutePath().toString(),
                                            dirName,
                                            dir,
                                            size,
                                            lastMod,
                                            cat,
                                            true,
                                            size
                                        ));
                                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                                    }
                                }
                                return java.nio.file.FileVisitResult.CONTINUE;
                            }

                            @Override
                            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                                if (targetType == MyStuffPanel.TargetType.DIRECTORIES) return java.nio.file.FileVisitResult.CONTINUE;
                                if (attrs.size() < criteria.minBytes()) return java.nio.file.FileVisitResult.CONTINUE;
                                if (file.getParent() != null && !java.nio.file.Files.isWritable(file.getParent())) {
                                    return java.nio.file.FileVisitResult.CONTINUE;
                                }

                                String fileName = file.getFileName().toString();
                                if (!criteria.extensions().isEmpty()) {
                                    int dot = fileName.lastIndexOf('.');
                                    String ext = dot > 0 ? fileName.substring(dot).toLowerCase(Locale.ROOT) : "";
                                    if (!criteria.extensions().contains(ext)) return java.nio.file.FileVisitResult.CONTINUE;
                                }

                                if (criteria.olderThanMonths() > 0) {
                                    java.time.Instant limit = now.atZone(java.time.ZoneOffset.UTC).minusMonths(criteria.olderThanMonths()).toInstant();
                                    if (!attrs.lastModifiedTime().toInstant().isBefore(limit)) return java.nio.file.FileVisitResult.CONTINUE;
                                }

                                if (!pat.isEmpty()) {
                                    String nameLower = fileName.toLowerCase(Locale.ROOT);
                                    String pathLower = file.toString().toLowerCase(Locale.ROOT);
                                    if (!nameLower.contains(pat) && !pathLower.contains(pat)) return java.nio.file.FileVisitResult.CONTINUE;
                                }

                                String id = file.toAbsolutePath().toString();
                                long size = attrs.size();
                                java.time.Instant lastMod = attrs.lastModifiedTime().toInstant();
                                MyStuffPanel.Category cat = size > (100L << 20) ? MyStuffPanel.Category.LARGE : MyStuffPanel.Category.CACHE;

                                entries.add(new MyStuffPanel.Entry(id, fileName, file, size, lastMod, cat, false, size));
                                return entries.size() < 500 ? java.nio.file.FileVisitResult.CONTINUE : java.nio.file.FileVisitResult.TERMINATE;
                            }

                            @Override
                            public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path file, java.io.IOException e) {
                                return java.nio.file.FileVisitResult.CONTINUE;
                            }
                        });
                    }
                } catch (Exception ignored) {}
                return entries;
            }
        };

        myStuff.setFileFilterService(uiCriteria -> backendFilterService.filterDirectory(activeRoot.get(), uiCriteria));

        // 3. Manejador para Comprimir con Zstd (archivos sueltos y carpetas completas con tar --zstd)
        myStuff.setCompressionHandler(entry -> {
            java.nio.file.Path inputPath = entry.path().toAbsolutePath();
            if (java.nio.file.Files.isDirectory(inputPath)) {
                java.nio.file.Path outputPath = inputPath.resolveSibling(inputPath.getFileName().toString() + ".tar.zst");
                ProcessBuilder pb = new ProcessBuilder(
                    "tar", "--zstd", "-cf", outputPath.toString(),
                    "-C", inputPath.getParent().toString(), inputPath.getFileName().toString()
                );
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (p.waitFor() == 0) return outputPath;
                throw new RuntimeException("Falló la compresión del directorio con tar --zstd");
            } else {
                java.nio.file.Path outputPath = inputPath.resolveSibling(inputPath.getFileName().toString() + ".zst");
                ProcessBuilder pb = new ProcessBuilder("zstd", "-T0", inputPath.toString(), "-o", outputPath.toString());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (p.waitFor() == 0) return outputPath;
                throw new RuntimeException("Falló la ejecución de zstd en el sistema");
            }
        });

        // 4. Manejador para Eliminar / Limpiar archivos y directorios (con soporte de symlinks rotos y solo lectura)
        myStuff.setCleanupHandler(entries -> {
            Set<String> removed = new HashSet<>();
            Map<String, String> failures = new HashMap<>();
            for (MyStuffPanel.Entry entry : entries) {
                java.nio.file.Path p = entry.path().toAbsolutePath().normalize();
                try {
                    if (!java.nio.file.Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        removed.add(entry.id());
                        continue;
                    }
                    if (java.nio.file.Files.isSymbolicLink(p) || !java.nio.file.Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        p.toFile().setWritable(true);
                        java.nio.file.Files.deleteIfExists(p);
                    } else {
                        java.nio.file.Files.walkFileTree(p, new java.nio.file.SimpleFileVisitor<>() {
                            @Override
                            public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                                if (java.nio.file.Files.isSymbolicLink(dir)) {
                                    java.nio.file.Files.deleteIfExists(dir);
                                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                                }
                                dir.toFile().setWritable(true);
                                return java.nio.file.FileVisitResult.CONTINUE;
                            }
                            @Override
                            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                                file.toFile().setWritable(true);
                                java.nio.file.Files.deleteIfExists(file);
                                return java.nio.file.FileVisitResult.CONTINUE;
                            }
                            @Override
                            public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path file, java.io.IOException exc) throws java.io.IOException {
                                file.toFile().setWritable(true);
                                java.nio.file.Files.deleteIfExists(file);
                                return java.nio.file.FileVisitResult.CONTINUE;
                            }
                            @Override
                            public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path dir, java.io.IOException exc) throws java.io.IOException {
                                if (exc != null) throw exc;
                                dir.toFile().setWritable(true);
                                java.nio.file.Files.deleteIfExists(dir);
                                return java.nio.file.FileVisitResult.CONTINUE;
                            }
                        });
                    }
                    removed.add(entry.id());
                } catch (java.nio.file.AccessDeniedException ade) {
                    failures.put(entry.id(), "Permiso denegado: " + ade.getFile());
                } catch (Exception e) {
                    failures.put(entry.id(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            }
            return new MyStuffPanel.CleanupResult(removed, failures);
        });

        // 5. CONEXIÓN CLAVE: Al elegir una unidad en ScanPanel, propagamos su punto de montaje a todos los paneles
        scanPanel.setOnStorageSelected(unit -> {
            if (unit.mounts.isEmpty()) return;
            // Si tiene varios puntos de montaje (ej. partición EFI y partición de datos), priorizamos el que no sea /boot
            java.nio.file.Path selectedMount = unit.mounts.stream()
                .filter(m -> !m.toString().startsWith("/boot"))
                .findFirst()
                .orElse(unit.mounts.get(0));

            activeRoot.set(selectedMount);
            projectsPanel.setTargetDirectory(selectedMount);
            mediaPanel.setTargetDirectory(selectedMount);

            shell.pushStatus("Target storage set to: " + unit.model + " (" + selectedMount + ")", Tone.OK);
            
            // Saltamos a My Stuff y disparamos la búsqueda inicial sobre la unidad seleccionada
            shell.showPanel("mystuff");
            myStuff.applyFilters();
        });
  
  // 5b. CONEXIÓN DE OPTIONS PANEL: Carga inicial, tema en vivo y persistencia
        OptionsPanel.Settings saved = loadSavedSettings();
        optionsPanel.loadSettings(saved);
        if (!saved.scanDirectories().isEmpty()) {
            java.nio.file.Path lastDir = saved.scanDirectories().get(saved.scanDirectories().size() - 1);
            activeRoot.set(lastDir);
            projectsPanel.setTargetDirectory(lastDir);
            mediaPanel.setTargetDirectory(lastDir);
        }

        optionsPanel.setOnThemeChanged(optTheme -> {
            MainShell.Theme shellTheme = (optTheme == OptionsPanel.Theme.GRUVBOX)
                ? MainShell.Theme.GRUVBOX
                : MainShell.Theme.CATPPUCCIN;
            shell.setTheme(shellTheme);
            shell.pushStatus("Theme switched to " + optTheme.name(), Tone.INFO);
        });

        optionsPanel.setOnSettingsChanged(settings -> {
            saveSettings(settings);
            if (!settings.scanDirectories().isEmpty()) {
                java.nio.file.Path latestDir = settings.scanDirectories().get(settings.scanDirectories().size() - 1);
                activeRoot.set(latestDir);
                projectsPanel.setTargetDirectory(latestDir);
                mediaPanel.setTargetDirectory(latestDir);
                shell.pushStatus("Settings saved · Active dir: " + latestDir, Tone.OK);
            } else {
                shell.pushStatus("Settings saved to ~/.config/storagewizard/settings.properties", Tone.OK);
            }
        });

        // 6. Adjuntamos todos los paneles a la shell
        shell.attach("scan", scanPanel);
        shell.attach("mystuff", myStuff);
        shell.attach("projects", projectsPanel);
        shell.attach("media", mediaPanel);
        shell.attach("options", optionsPanel);

        if (saved.theme() == OptionsPanel.Theme.GRUVBOX) {
            shell.setTheme(MainShell.Theme.GRUVBOX);
        }

        f.setContentPane(shell);
        shell.setBackground(shell.pal.bg);

        shell.pushStatus("StorageWizard ready — select a drive in Scan to begin", Tone.INFO);

        f.setSize(960, 640);
        f.setMinimumSize(new Dimension(760, 520));
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    });
  }
}
