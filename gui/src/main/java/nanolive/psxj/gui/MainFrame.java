package nanolive.psxj.gui;

import nanolive.psxj.config.AppConfig;
import nanolive.psxj.config.ConfigManager;
import nanolive.psxj.config.GameHistory;
import nanolive.psxj.emu.EmulationState;
import nanolive.psxj.emu.PsxEmulator;
import nanolive.psxj.emu.hardware.HardwareProfile;
import nanolive.psxj.emu.state.SaveStateManager;
import nanolive.psxj.i18n.I18n;
import nanolive.psxj.library.GameEntry;
import nanolive.psxj.library.GameLibrary;
import nanolive.psxj.library.GameLibraryListener;
import nanolive.psxj.emu.api.AudioBackend;
import nanolive.psxj.emu.api.GamepadBackend;
import nanolive.psxj.emu.api.RenderBackend;
import nanolive.psxj.platform.input.SdlGamepadBackend;
import nanolive.psxj.platform.render.GameOverlayHost;
import nanolive.psxj.retroachievements.RetroAchievementsService;
import nanolive.psxj.metadata.GameMetadataService;
import nanolive.psxj.gui.panels.SettingsUi;
import nanolive.psxj.util.Log;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;

public final class MainFrame extends JFrame implements GameLibraryListener {

    private final AppConfig config;
    private final ConfigManager configManager;
    private final GameLibrary gameLibrary;
    private final GameTableModel tableModel = new GameTableModel();
    private final JTable table = new JTable(tableModel);
    private final TableRowSorter<GameTableModel> sorter = new TableRowSorter<>(tableModel);
    private final java.util.Map<String, ImageIcon> coverIcons = new java.util.concurrent.ConcurrentHashMap<>();
    private final GameDetailsPanel detailsPanel = new GameDetailsPanel(this::bootSelectedGame, this::pauseResume, this::stopEmulation);
    private final JTextField searchField = new JTextField();
    private final JComboBox<SortMode> sortMode = new JComboBox<>(SortMode.values());
    private final JLabel biosBadge = new BadgeLabel();
    private final JLabel statusLabel = new JLabel(" ");
    private final Function<AppConfig, RenderBackend> renderFactory;
    private final Function<AppConfig, AudioBackend> audioFactory;
    private final SaveStateManager saveStateManager;
    private final RetroAchievementsService retroAchievements;
    private final GameMetadataService metadataService;
    private final KeyEventDispatcher inputDispatcher = this::dispatchPadKey;
    private volatile PsxEmulator emulator;
    private volatile GameOverlayHost gameOverlayHost;
    private CompletableFuture<Void> emulationStopFuture = CompletableFuture.completedFuture(null);
    private GameEntry runningGame;
    private Instant runningStartedAt = Instant.EPOCH;
    private Instant runningLastLaunchAt = Instant.EPOCH;

    public MainFrame(AppConfig config,
                     ConfigManager configManager,
                     GameLibrary gameLibrary,
                     Function<AppConfig, RenderBackend> renderFactory,
                     Function<AppConfig, AudioBackend> audioFactory) {
        super(I18n.tr("app.title"));
        this.config = config;
        this.configManager = configManager;
        this.gameLibrary = gameLibrary;
        this.renderFactory = renderFactory;
        this.audioFactory = audioFactory;
        this.saveStateManager = new SaveStateManager(config.saveStateDirectory());
        this.retroAchievements = new RetroAchievementsService(config.retroAchievements());
        this.metadataService = new GameMetadataService();
        SettingsUi.padCombo(sortMode);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setPreferredSize(new Dimension(1180, 760));
        setLayout(new BorderLayout());
        ((JComponent) getContentPane()).setBorder(ModernUi.windowContentBorder());
        setJMenuBar(createMenuBar());
        add(createHeader(), BorderLayout.NORTH);
        add(createCenter(), BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                requestClose();
            }
        });

        configureTable();
        bindLibraryControls();

        gameLibrary.addListener(this);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(inputDispatcher);
        refreshUiState();
        pack();
        setLocationRelativeTo(null);
        ModernUi.installPointingHands(this);
    }

    public void applyThemeSelection() {
        ThemeManager.applyTheme(config.ui().theme());
        SwingUtilities.updateComponentTreeUI(this);
        ((JComponent) getContentPane()).setBorder(ModernUi.windowContentBorder());
        detailsPanel.repaint();
        table.repaint();
        refreshUiState();
    }

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu(I18n.tr("menu.file"));
        JMenuItem selectBios = new JMenuItem(I18n.tr("menu.file.selectBios"));
        JMenuItem addLibrary = new JMenuItem(I18n.tr("menu.file.addLibrary"));
        JMenuItem exit = new JMenuItem(I18n.tr("menu.file.exit"));
        selectBios.addActionListener(event -> chooseBios());
        addLibrary.addActionListener(event -> addLibraryRoot());
        exit.addActionListener(event -> requestClose());
        file.add(selectBios);
        file.add(addLibrary);
        file.addSeparator();
        file.add(exit);

        JMenu emulation = new JMenu(I18n.tr("menu.emulation"));
        JMenuItem boot = new JMenuItem(I18n.tr("menu.emulation.boot"));
        JMenuItem pause = new JMenuItem(I18n.tr("menu.emulation.pauseResume"));
        JMenuItem stop = new JMenuItem(I18n.tr("menu.emulation.stop"));
        JMenu saveState = new JMenu(I18n.tr("menu.emulation.saveState"));
        JMenu loadState = new JMenu(I18n.tr("menu.emulation.loadState"));
        boot.addActionListener(event -> bootSelectedGame());
        pause.addActionListener(event -> pauseResume());
        stop.addActionListener(event -> stopEmulation());
        for (int slot = 1; slot <= 9; slot++) {
            int selectedSlot = slot;
            JMenuItem saveSlot = new JMenuItem(I18n.tr("menu.emulation.slot", slot));
            JMenuItem loadSlot = new JMenuItem(I18n.tr("menu.emulation.slot", slot));
            saveSlot.addActionListener(event -> saveState(selectedSlot));
            loadSlot.addActionListener(event -> loadState(selectedSlot));
            if (slot == 1) {
                saveSlot.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
                loadSlot.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0));
            }
            saveState.add(saveSlot);
            loadState.add(loadSlot);
        }
        emulation.add(boot);
        emulation.add(pause);
        emulation.add(stop);
        emulation.addSeparator();
        emulation.add(saveState);
        emulation.add(loadState);

        JMenu settings = new JMenu(I18n.tr("menu.settings"));
        JMenuItem preferences = new JMenuItem(I18n.tr("menu.settings.preferences"));
        preferences.addActionListener(event -> openSettings());
        settings.add(preferences);

        JMenu help = new JMenu(I18n.tr("menu.help"));
        JMenuItem about = new JMenuItem(I18n.tr("menu.help.about"));
        about.addActionListener(event -> new AboutDialog(this).setVisible(true));
        help.add(about);

        bar.add(file);
        bar.add(emulation);
        bar.add(settings);
        bar.add(help);
        return bar;
    }

    private JComponent createCenter() {
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        splitPane.setResizeWeight(0.70);
        splitPane.setContinuousLayout(true);
        splitPane.setLeftComponent(createLibraryPanel());
        splitPane.setRightComponent(detailsPanel);
        return splitPane;
    }

    private JComponent createHeader() {
        JPanel root = new JPanel(new BorderLayout(16, 0));
        root.setBorder(BorderFactory.createEmptyBorder(18, 18, 16, 18));

        JPanel titleBlock = new JPanel();
        titleBlock.setOpaque(false);
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("PSXJ");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        JLabel subtitle = new JLabel(I18n.tr("hero.subtitle"));
        subtitle.setForeground(ModernUi.secondaryText());
        titleBlock.add(title);
        titleBlock.add(Box.createVerticalStrut(3));
        titleBlock.add(subtitle);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        biosBadge.setBorder(BorderFactory.createEmptyBorder(7, 11, 7, 11));
        actions.add(biosBadge);
        actions.add(createHeaderButton(I18n.tr("toolbar.bootBios"), event -> bootBios()));
        actions.add(createHeaderButton(I18n.tr("toolbar.settings"), event -> openSettings()));

        root.add(titleBlock, BorderLayout.CENTER);
        root.add(actions, BorderLayout.EAST);
        return root;
    }

    private JComponent createLibraryPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));

        JPanel controls = new JPanel(new BorderLayout(12, 0));
        controls.setOpaque(false);
        searchField.putClientProperty("JTextField.placeholderText", I18n.tr("library.search"));
        controls.add(searchField, BorderLayout.CENTER);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightControls.setOpaque(false);
        rightControls.add(createHeaderButton(I18n.tr("toolbar.addLibrary"), event -> addLibraryRoot()));
        rightControls.add(sortMode);
        controls.add(rightControls, BorderLayout.EAST);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(ModernUi.cardBorder(0, 0));

        root.add(controls, BorderLayout.NORTH);
        root.add(scrollPane, BorderLayout.CENTER);
        return root;
    }

    private JPanel createStatusBar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, ModernUi.divider()),
            BorderFactory.createEmptyBorder(9, 16, 10, 16)));
        statusLabel.setForeground(ModernUi.secondaryText());
        panel.add(statusLabel, BorderLayout.WEST);
        return panel;
    }

    private void configureTable() {
        table.setRowSorter(sorter);
        table.setRowHeight(64);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 8));
        table.setFocusable(false);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(false);
        table.setFillsViewportHeight(true);
        table.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setPreferredSize(new Dimension(header.getPreferredSize().width, 38));
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));

        sorter.setComparator(0, String.CASE_INSENSITIVE_ORDER);
        sorter.setComparator(1, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        sorter.setComparator(2, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        sorter.setComparator(3, Comparator.comparingLong(value -> (Long) value));
        sorter.setComparator(4, Comparator.naturalOrder());
        applySort();

        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                refreshUiState();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && event.getButton() == MouseEvent.BUTTON1) {
                    bootSelectedGame();
                }
            }
        });

        table.getColumnModel().getColumn(0).setPreferredWidth(380);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(120);
        table.getColumnModel().getColumn(4).setPreferredWidth(140);
        table.getColumnModel().getColumn(0).setCellRenderer(new TitleCellRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new PlainCellRenderer(SwingConstants.LEFT));
        table.getColumnModel().getColumn(2).setCellRenderer(new RegionCellRenderer());
        table.getColumnModel().getColumn(3).setCellRenderer(new DurationCellRenderer());
        table.getColumnModel().getColumn(4).setCellRenderer(new InstantCellRenderer());
    }

    private void bindLibraryControls() {
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyFilter();
            }
        });
        sortMode.addActionListener(event -> applySort());
    }

    private void applyFilter() {
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isBlank()) {
            sorter.setRowFilter(null);
            return;
        }
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends GameTableModel, ? extends Integer> entry) {
                GameEntry game = tableModel.getRow(entry.getIdentifier());
                return contains(game.title(), query)
                    || contains(game.serial(), query)
                    || contains(game.region(), query)
                    || contains(game.path().toString(), query)
                    || contains(game.path().getFileName().toString(), query);
            }
        });
    }

    private void applySort() {
        SortMode selected = (SortMode) sortMode.getSelectedItem();
        if (selected == null) {
            selected = SortMode.TITLE;
        }
        switch (selected) {
            case TITLE -> sorter.setSortKeys(List.of(new javax.swing.RowSorter.SortKey(0, javax.swing.SortOrder.ASCENDING)));
            case RECENT -> sorter.setSortKeys(List.of(new javax.swing.RowSorter.SortKey(4, javax.swing.SortOrder.DESCENDING)));
            case MOST_PLAYED -> sorter.setSortKeys(List.of(new javax.swing.RowSorter.SortKey(3, javax.swing.SortOrder.DESCENDING)));
        }
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private void chooseBios() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(I18n.tr("dialog.selectBios"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            config.setBiosPath(chooser.getSelectedFile().toPath());
            configManager.save(config);
            refreshUiState();
        }
    }

    private void addLibraryRoot() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(I18n.tr("dialog.selectLibraryFolder"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path path = chooser.getSelectedFile().toPath();
            config.setLibraryRoots(concat(config.libraryRoots(), path));
            configManager.save(config);
            gameLibrary.scanAsync(config.libraryRoots());
        }
    }

    private List<Path> concat(List<Path> roots, Path newRoot) {
        List<Path> mutable = new ArrayList<>(roots);
        mutable.add(newRoot);
        return List.copyOf(mutable);
    }

    private void openSettings() {
        new SettingsDialog(this, config, configManager, retroAchievements).setVisible(true);
        refreshUiState();
    }

    void applyLiveEmulationSettings() {
        PsxEmulator current = emulator;
        if (current != null) {
            current.setOverclockPercent(config.emulation().overclockPercent());
        }
        GameOverlayHost overlay = gameOverlayHost;
        if (overlay != null) {
            overlay.setRetroAchievementsEnabled(config.retroAchievements().enabled());
        }
    }

    private void requestClose() {
        CompletableFuture<Void> cleanup = stopEmulation();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(inputDispatcher);
        super.dispose();
        cleanup.orTimeout(10, TimeUnit.SECONDS).whenComplete((unused, failure) -> {
            if (failure != null) {
                Log.error("Application shutdown timed out", failure);
            } else {
                retroAchievements.close();
            }
            metadataService.close();
            System.exit(0);
        });
    }

    private GameEntry selectedGame() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return null;
        }
        int modelRow = table.convertRowIndexToModel(row);
        return tableModel.getRow(modelRow);
    }

    private boolean isSelectedGameRunning(GameEntry selected) {
        return selected != null && runningGame != null && selected.libraryId().equals(runningGame.libraryId());
    }

    private void bootSelectedGame() {
        GameEntry selected = selectedGame();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, I18n.tr("error.noGameSelected"));
            return;
        }
        if (config.biosPath() == null) {
            JOptionPane.showMessageDialog(this, I18n.tr("error.noBios"));
            return;
        }
        if (!confirmRegionMismatch(selected)) {
            return;
        }

        stopEmulation().whenComplete((unused, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure != null) {
                showEmulationStopFailure(failure);
            } else {
                bootSelectedGameNow(selected);
            }
        }));
    }

    private void bootSelectedGameNow(GameEntry selected) {
        try {
            PsxEmulator newEmulator = createEmulator();
            newEmulator.setStopListener(() -> SwingUtilities.invokeLater(() -> onEmulationStopped(newEmulator)));
            RenderBackend renderBackend = renderFactory.apply(config);
            renderBackend.setKeyEventHandler((key, pressed) ->
                newEmulator.setPadButtonState(padMaskForRenderKey(key), pressed));
            gameOverlayHost = configureGameOverlay(renderBackend, newEmulator, selected);
            newEmulator.setBackends(renderBackend, audioFactory.apply(config), createGamepadBackend());
            renderBackend.setCloseRequestHandler(() -> requestStopFromRenderWindow(newEmulator));
            newEmulator.attachMemoryCards(gameCardPath(selected, 1), gameCardPath(selected, 2));
            newEmulator.loadGame(selected.path(), selected.title());
            emulator = newEmulator;
            retroAchievements.startGame(newEmulator, selected.path(), gameOverlayHost);
            newEmulator.start();

            Instant launchTime = Instant.now();
            GameEntry updated = persistGameHistory(selected, selected.totalPlayTimeSeconds(), launchTime);
            runningGame = updated;
            runningStartedAt = launchTime;
            runningLastLaunchAt = launchTime;
            refreshUiState();
            statusLabel.setText(I18n.tr(
                "status.runningGame",
                updated.title(),
                UiFormatters.humanizeEnum(config.video().renderer()),
                UiFormatters.humanizeEnum(config.audio().backend())
            ));
        } catch (Exception ex) {
            stopEmulation();
            Log.error("Failed to boot game", ex);
            JOptionPane.showMessageDialog(this, ex.getMessage(), I18n.tr("error.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void bootBios() {
        if (config.biosPath() == null) {
            JOptionPane.showMessageDialog(this, I18n.tr("error.noBios"));
            return;
        }

        stopEmulation().whenComplete((unused, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure != null) {
                showEmulationStopFailure(failure);
            } else {
                bootBiosNow();
            }
        }));
    }

    private void bootBiosNow() {
        try {
            PsxEmulator newEmulator = createEmulator();
            newEmulator.setStopListener(() -> SwingUtilities.invokeLater(() -> onEmulationStopped(newEmulator)));
            RenderBackend renderBackend = renderFactory.apply(config);
            renderBackend.setKeyEventHandler((key, pressed) ->
                newEmulator.setPadButtonState(padMaskForRenderKey(key), pressed));
            gameOverlayHost = configureGameOverlay(renderBackend, newEmulator, null);
            newEmulator.setBackends(renderBackend, audioFactory.apply(config), createGamepadBackend());
            renderBackend.setCloseRequestHandler(() -> requestStopFromRenderWindow(newEmulator));
            newEmulator.prepareBiosBoot(sharedCardPath(1), sharedCardPath(2));
            emulator = newEmulator;
            newEmulator.start();

            runningGame = null;
            runningStartedAt = Instant.EPOCH;
            runningLastLaunchAt = Instant.EPOCH;
            refreshUiState();
            statusLabel.setText(I18n.tr(
                "status.runningBios",
                UiFormatters.humanizeEnum(config.video().renderer()),
                UiFormatters.humanizeEnum(config.audio().backend())
            ));
        } catch (Exception ex) {
            stopEmulation();
            Log.error("Failed to boot BIOS", ex);
            JOptionPane.showMessageDialog(this, ex.getMessage(), I18n.tr("error.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private PsxEmulator createEmulator() {
        return new PsxEmulator(config.biosPath(), config.emulation().overclockPercent());
    }

    private boolean requestStopFromRenderWindow(PsxEmulator target) {
        boolean accepted = !config.ui().confirmOnExit() || confirmStopEmulation(target);
        if (accepted) {
            SwingUtilities.invokeLater(() -> {
                if (emulator == target) {
                    stopEmulation();
                } else {
                    target.requestStop();
                    CompletableFuture.runAsync(target::close);
                }
            });
        }
        return accepted;
    }

    private boolean confirmStopEmulation(PsxEmulator target) {
        if (SwingUtilities.isEventDispatchThread()) {
            return showStopEmulationDialog(target);
        }
        var accepted = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            SwingUtilities.invokeAndWait(() -> accepted.set(showStopEmulationDialog(target)));
            return accepted.get();
        } catch (Exception ex) {
            Log.warn("Could not show the stop-emulation confirmation: " + ex.getMessage());
            return false;
        }
    }

    private boolean showStopEmulationDialog(PsxEmulator target) {
        boolean pausedForDialog = target.state() == EmulationState.RUNNING;
        if (pausedForDialog) {
            target.pause();
        }

        JOptionPane optionPane = new JOptionPane(
            I18n.tr("dialog.confirmStopEmulation"),
            JOptionPane.QUESTION_MESSAGE,
            JOptionPane.YES_NO_OPTION
        );
        JDialog dialog = optionPane.createDialog(this, I18n.tr("dialog.confirmStopEmulationTitle"));
        dialog.setModalityType(Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setAlwaysOnTop(true);
        dialog.setAutoRequestFocus(true);
        ModernUi.installPointingHands(dialog);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                dialog.toFront();
                dialog.requestFocus();
            }
        });

        dialog.setVisible(true);
        dialog.dispose();
        boolean accepted = Integer.valueOf(JOptionPane.YES_OPTION).equals(optionPane.getValue());
        if (!accepted && pausedForDialog && target.state() == EmulationState.PAUSED) {
            target.resume();
        }
        return accepted;
    }

    private boolean confirmRegionMismatch(GameEntry game) {
        HardwareProfile profile = HardwareProfile.detectKnown(config.biosPath());
        if (profile == null || GameRegionCompatibility.isCompatible(game.region(), profile.region())) {
            return true;
        }
        return JOptionPane.showConfirmDialog(
            this,
            I18n.tr("dialog.regionMismatch", game.region(),
                GameRegionCompatibility.displayName(profile.region())),
            I18n.tr("dialog.regionMismatchTitle"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION;
    }

    private GameOverlayHost configureGameOverlay(RenderBackend renderBackend,
                                                 PsxEmulator target,
                                                 GameEntry game) {
        if (!(renderBackend instanceof GameOverlayHost overlay)) {
            return null;
        }
        overlay.setRetroAchievementsEnabled(config.retroAchievements().enabled());
        if (game != null) {
            overlay.configureSaveStateOverlay(
                () -> occupiedSaveStateSlots(game.libraryId()),
                slot -> SwingUtilities.invokeLater(() -> saveState(slot)),
                slot -> SwingUtilities.invokeLater(() -> loadState(slot)));
        }
        java.util.concurrent.atomic.AtomicBoolean pausedByOverlay =
            new java.util.concurrent.atomic.AtomicBoolean();
        overlay.setOverlayOpenListener(open -> {
            if (!config.emulation().pauseWhenOverlayOpen()) return;
            if (open) {
                if (target.state() == EmulationState.RUNNING) {
                    target.pause();
                    pausedByOverlay.set(true);
                }
            } else if (pausedByOverlay.compareAndSet(true, false)
                && target.state() == EmulationState.PAUSED) {
                target.resume();
            }
        });
        target.setMemoryCardWriteListener(slot ->
            overlay.showOverlayToast(I18n.tr("overlay.memoryCardSaved", slot)));
        return overlay;
    }

    private GameOverlayHost.SaveStateSlot[] occupiedSaveStateSlots(String gameId) {
        GameOverlayHost.SaveStateSlot[] occupied = new GameOverlayHost.SaveStateSlot[9];
        for (int slot = 1; slot <= occupied.length; slot++) {
            Path path = saveStateManager.slotPath(gameId, slot);
            if (Files.isRegularFile(path)) {
                try {
                    occupied[slot - 1] = new GameOverlayHost.SaveStateSlot(true,
                        Files.getLastModifiedTime(path).toInstant());
                } catch (IOException ignored) {
                    occupied[slot - 1] = new GameOverlayHost.SaveStateSlot(true, null);
                }
            } else {
                occupied[slot - 1] = GameOverlayHost.SaveStateSlot.empty();
            }
        }
        return occupied;
    }

    private GamepadBackend createGamepadBackend() {
        return config.input().enableGamepad()
            ? new SdlGamepadBackend(
                config.input().deadZonePercent(),
                config.input().enableRumble(),
                (connected, name) -> {
                    GameOverlayHost overlay = gameOverlayHost;
                    if (overlay != null) {
                        overlay.showOverlayToast(I18n.tr(connected
                            ? "overlay.deviceConnected" : "overlay.deviceDisconnected", name));
                    }
                }
            )
            : null;
    }

    private Path gameCardPath(GameEntry entry, int slot) {
        if (entry.hasKnownSerial()) {
            var profile = config.gameProfile(entry.serial());
            var override = slot == 1
                ? profile.memoryCard1Path()
                : profile.memoryCard2Path();
            if (override.isPresent()) {
                return override.get();
            }
            if (slot == 1 && config.memoryCardDirectory() != null) {
                return config.memoryCardDirectory().resolve(entry.serial() + "-slot1.mcd");
            }
        }
        return sharedCardPath(slot);
    }

    private Path sharedCardPath(int slot) {
        Path directory = config.memoryCardDirectory();
        return directory == null ? null : directory.resolve("shared-card-" + slot + ".mcd");
    }

    private void onEmulationStopped(PsxEmulator stoppedEmulator) {
        if (emulator == stoppedEmulator) {
            emulator = null;
            gameOverlayHost = null;
            recordRunningSessionIfNeeded();
            emulationStopFuture = CompletableFuture.runAsync(() -> {
                stoppedEmulator.terminationFuture().join();
                stoppedEmulator.close();
                retroAchievements.stopGame();
            });
        }
        refreshUiState();
    }

    private void pauseResume() {
        if (emulator == null) {
            return;
        }
        if (emulator.state() == EmulationState.RUNNING) {
            emulator.pause();
        } else if (emulator.state() == EmulationState.PAUSED) {
            emulator.resume();
        }
        refreshUiState();
    }

    private void saveState(int slot) {
        PsxEmulator current = emulator;
        GameEntry game = runningGame;
        if (current == null || game == null) {
            JOptionPane.showMessageDialog(this, I18n.tr("error.noRunningGame"));
            return;
        }
        statusLabel.setText(I18n.tr("status.stateSaving", slot));
        GameOverlayHost overlay = gameOverlayHost;
        if (overlay != null) {
            overlay.showOverlayToast(I18n.tr("overlay.stateSaving", slot));
        }
        CompletableFuture.runAsync(() -> {
            try {
                saveStateManager.save(current, game.libraryId(), slot);
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }).whenComplete((unused, error) -> SwingUtilities.invokeLater(() -> {
            if (!isDisplayable()) {
                return;
            }
            if (error == null) {
                statusLabel.setText(I18n.tr("status.stateSaved", slot));
                if (overlay != null) {
                    overlay.refreshOverlaySlots();
                    overlay.showOverlayToast(I18n.tr("overlay.stateSaved", slot));
                }
            } else {
                if (overlay != null) {
                    overlay.showOverlayToast(I18n.tr("overlay.stateSaveFailed", slot));
                }
                showStateError(I18n.tr("error.stateSave"), error);
            }
        }));
    }

    private void loadState(int slot) {
        PsxEmulator current = emulator;
        GameEntry game = runningGame;
        if (current == null || game == null) {
            JOptionPane.showMessageDialog(this, I18n.tr("error.noRunningGame"));
            return;
        }
        if (!Files.isRegularFile(saveStateManager.slotPath(game.libraryId(), slot))) {
            JOptionPane.showMessageDialog(this, I18n.tr("error.stateMissing", slot));
            return;
        }
        statusLabel.setText(I18n.tr("status.stateLoading", slot));
        GameOverlayHost overlay = gameOverlayHost;
        if (overlay != null) {
            overlay.showOverlayToast(I18n.tr("overlay.stateLoading", slot));
        }
        CompletableFuture.runAsync(() -> {
            try {
                saveStateManager.load(current, game.libraryId(), slot);
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }).whenComplete((unused, error) -> SwingUtilities.invokeLater(() -> {
            if (!isDisplayable()) {
                return;
            }
            if (error == null) {
                retroAchievements.resetAfterStateLoad();
                statusLabel.setText(I18n.tr("status.stateLoaded", slot));
                if (overlay != null) {
                    overlay.showOverlayToast(I18n.tr("overlay.stateLoaded", slot));
                }
            } else {
                if (overlay != null) {
                    overlay.showOverlayToast(I18n.tr("overlay.stateLoadFailed", slot));
                }
                showStateError(I18n.tr("error.stateLoad"), error);
            }
        }));
    }

    private void showStateError(String operation, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
            ? error.getCause()
            : error;
        Log.error(operation, cause);
        JOptionPane.showMessageDialog(
            this,
            I18n.tr("error.stateOperation", operation, cause.getMessage()),
            I18n.tr("error.title"),
            JOptionPane.ERROR_MESSAGE
        );
        updateStatus();
    }

    private boolean dispatchPadKey(KeyEvent event) {
        if (event.getSource() instanceof Component component) {
            Window sourceWindow = SwingUtilities.getWindowAncestor(component);
            if (sourceWindow != null && sourceWindow != this) {
                return false;
            }
        }
        PsxEmulator current = emulator;
        if (current == null) {
            return false;
        }
        int mask = switch (event.getKeyCode()) {
            case KeyEvent.VK_UP -> nanolive.psxj.emu.devices.SioController.PAD_UP;
            case KeyEvent.VK_RIGHT -> nanolive.psxj.emu.devices.SioController.PAD_RIGHT;
            case KeyEvent.VK_DOWN -> nanolive.psxj.emu.devices.SioController.PAD_DOWN;
            case KeyEvent.VK_LEFT -> nanolive.psxj.emu.devices.SioController.PAD_LEFT;
            case KeyEvent.VK_ENTER -> nanolive.psxj.emu.devices.SioController.PAD_START;
            case KeyEvent.VK_SPACE -> nanolive.psxj.emu.devices.SioController.PAD_SELECT;
            case KeyEvent.VK_X -> nanolive.psxj.emu.devices.SioController.PAD_CROSS;
            case KeyEvent.VK_Z -> nanolive.psxj.emu.devices.SioController.PAD_SQUARE;
            case KeyEvent.VK_S -> nanolive.psxj.emu.devices.SioController.PAD_CIRCLE;
            case KeyEvent.VK_A -> nanolive.psxj.emu.devices.SioController.PAD_TRIANGLE;
            case KeyEvent.VK_Q -> nanolive.psxj.emu.devices.SioController.PAD_L1;
            case KeyEvent.VK_W -> nanolive.psxj.emu.devices.SioController.PAD_R1;
            case KeyEvent.VK_1 -> nanolive.psxj.emu.devices.SioController.PAD_L2;
            case KeyEvent.VK_2 -> nanolive.psxj.emu.devices.SioController.PAD_R2;
            default -> 0;
        };
        if (mask == 0) {
            return false;
        }
        current.setPadButtonState(mask, event.getID() == KeyEvent.KEY_PRESSED);
        return false;
    }

    private static int padMaskForRenderKey(RenderBackend.InputKey key) {
        return switch (key) {
            case UP -> nanolive.psxj.emu.devices.SioController.PAD_UP;
            case RIGHT -> nanolive.psxj.emu.devices.SioController.PAD_RIGHT;
            case DOWN -> nanolive.psxj.emu.devices.SioController.PAD_DOWN;
            case LEFT -> nanolive.psxj.emu.devices.SioController.PAD_LEFT;
            case START -> nanolive.psxj.emu.devices.SioController.PAD_START;
            case SELECT -> nanolive.psxj.emu.devices.SioController.PAD_SELECT;
            case CROSS -> nanolive.psxj.emu.devices.SioController.PAD_CROSS;
            case SQUARE -> nanolive.psxj.emu.devices.SioController.PAD_SQUARE;
            case CIRCLE -> nanolive.psxj.emu.devices.SioController.PAD_CIRCLE;
            case TRIANGLE -> nanolive.psxj.emu.devices.SioController.PAD_TRIANGLE;
            case L1 -> nanolive.psxj.emu.devices.SioController.PAD_L1;
            case R1 -> nanolive.psxj.emu.devices.SioController.PAD_R1;
            case L2 -> nanolive.psxj.emu.devices.SioController.PAD_L2;
            case R2 -> nanolive.psxj.emu.devices.SioController.PAD_R2;
        };
    }

    private CompletableFuture<Void> stopEmulation() {
        PsxEmulator current = emulator;
        if (current == null) {
            return emulationStopFuture;
        }
        emulator = null;
        gameOverlayHost = null;
        recordRunningSessionIfNeeded();
        current.requestStop();
        refreshUiState();
        CompletableFuture<Void> cleanup = CompletableFuture.runAsync(() -> {
            current.terminationFuture().join();
            current.close();
            retroAchievements.stopGame();
        });
        emulationStopFuture = cleanup;
        cleanup.whenComplete((unused, failure) -> {
            if (failure != null) {
                Log.error("Failed to finish emulation shutdown", failure);
            }
            SwingUtilities.invokeLater(this::refreshUiState);
        });
        return cleanup;
    }

    private void showEmulationStopFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        Log.error("Could not stop the previous emulation session", cause);
        JOptionPane.showMessageDialog(this, cause.getMessage(),
            I18n.tr("error.title"), JOptionPane.ERROR_MESSAGE);
    }

    private void recordRunningSessionIfNeeded() {
        if (runningGame == null || Instant.EPOCH.equals(runningStartedAt)) {
            return;
        }
        long sessionSeconds = Math.max(0L, Duration.between(runningStartedAt, Instant.now()).getSeconds());
        persistGameHistory(runningGame, runningGame.totalPlayTimeSeconds() + sessionSeconds, runningLastLaunchAt);
        runningGame = null;
        runningStartedAt = Instant.EPOCH;
        runningLastLaunchAt = Instant.EPOCH;
    }

    private GameEntry persistGameHistory(GameEntry base, long totalPlayTimeSeconds, Instant lastPlayed) {
        GameHistory history = config.gameHistory(base.libraryId());
        history.setKey(base.libraryId());
        history.setTitle(base.title());
        history.setSerial(base.serial());
        history.setTotalPlayTimeSeconds(totalPlayTimeSeconds);
        history.setLastPlayedEpochSecond(lastPlayed == null || Instant.EPOCH.equals(lastPlayed) ? 0 : lastPlayed.getEpochSecond());
        config.putGameHistory(history);
        configManager.save(config);

        GameEntry updated = new GameEntry(
            base.libraryId(),
            base.title(),
            base.serial(),
            base.region(),
            base.path(),
            totalPlayTimeSeconds,
            lastPlayed == null ? Instant.EPOCH : lastPlayed
        );
        gameLibrary.refreshGame(updated);
        return updated;
    }

    private void refreshUiState() {
        GameEntry selected = selectedGame();
        EmulationState emulationState = emulator == null ? EmulationState.STOPPED : emulator.state();
        detailsPanel.showGame(selected, emulationState, isSelectedGameRunning(selected));
        GameMetadataService.Media media = metadataService.mediaFor(selected);
        detailsPanel.setArtwork(media.cover(), media.logo());
        biosBadge.setText(config.biosPath() == null
            ? I18n.tr("status.biosMissing")
            : config.biosPath().getFileName().toString());
        updateStatus();
    }

    private void updateStatus() {
        String biosInfo = config.biosPath() == null ? I18n.tr("status.biosMissing") : config.biosPath().getFileName().toString();
        EmulationState emuState = emulator == null ? EmulationState.STOPPED : emulator.state();
        GameEntry selection = selectedGame();
        String gameText = selection == null ? I18n.tr("status.noGameSelected") : selection.title();
        statusLabel.setText(I18n.tr("status.line", biosInfo, UiFormatters.humanizeEnum(emuState), gameText, tableModel.getRowCount()));
    }

    @Override
    public void onLibraryUpdated(List<GameEntry> entries) {
        String selectedId = selectedGame() == null ? runningGame == null ? null : runningGame.libraryId() : selectedGame().libraryId();
        tableModel.setRows(entries);
        metadataService.refresh(entries, () -> SwingUtilities.invokeLater(this::artworkUpdated));
        selectGameById(selectedId);
        refreshUiState();
    }

    private void artworkUpdated() {
        table.repaint();
        GameEntry selected = selectedGame();
        GameMetadataService.Media media = metadataService.mediaFor(selected);
        detailsPanel.setArtwork(media.cover(), media.logo());
    }

    private void selectGameById(String libraryId) {
        if (libraryId == null) {
            return;
        }
        for (int modelRow = 0; modelRow < tableModel.getRowCount(); modelRow++) {
            if (!tableModel.getRow(modelRow).libraryId().equals(libraryId)) {
                continue;
            }
            int viewRow = table.convertRowIndexToView(modelRow);
            if (viewRow >= 0) {
                table.setRowSelectionInterval(viewRow, viewRow);
                table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
            }
            return;
        }
    }

    private JButton createHeaderButton(String text, java.awt.event.ActionListener listener) {
        JButton button = new JButton(text);
        button.addActionListener(listener);
        ModernUi.styleButton(button, false);
        return button;
    }

    private final class TitleCellRenderer extends JPanel implements TableCellRenderer {

        private final JLabel artwork = new JLabel();
        private final JLabel title = new JLabel();
        private final JLabel subtitle = new JLabel();

        private TitleCellRenderer() {
            setLayout(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
            title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
            subtitle.setFont(subtitle.getFont().deriveFont(12f));
            artwork.setPreferredSize(new Dimension(36, 44));
            JPanel text = new JPanel(new BorderLayout(0, 4));
            text.setOpaque(false);
            text.add(title, BorderLayout.NORTH);
            text.add(subtitle, BorderLayout.CENTER);
            add(artwork, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table,
                                                       Object value,
                                                       boolean isSelected,
                                                       boolean hasFocus,
                                                       int row,
                                                       int column) {
            GameEntry entry = tableModel.getRow(table.convertRowIndexToModel(row));
            title.setText(entry.title());
            subtitle.setText(entry.path().getFileName().toString());
            BufferedImage cover = metadataService.mediaFor(entry).cover();
            artwork.setIcon(cover == null ? null : coverIcons.computeIfAbsent(entry.libraryId(),
                ignored -> new ImageIcon(cover.getScaledInstance(
                    36, 44, java.awt.Image.SCALE_SMOOTH))));
            artwork.setVisible(cover != null);
            Color background = isSelected ? table.getSelectionBackground() : row % 2 == 0
                ? new Color(255, 255, 255, ThemeManager.isDarkTheme() ? 8 : 18)
                : new Color(255, 255, 255, 0);
            setOpaque(true);
            setBackground(background);
            title.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            subtitle.setForeground(isSelected ? table.getSelectionForeground() : new Color(140, 148, 164));
            return this;
        }
    }

    private static class PlainCellRenderer extends DefaultTableCellRenderer {

        private PlainCellRenderer(int alignment) {
            setHorizontalAlignment(alignment);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table,
                                                       Object value,
                                                       boolean isSelected,
                                                       boolean hasFocus,
                                                       int row,
                                                       int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            return this;
        }
    }

    private static final class RegionCellRenderer extends PlainCellRenderer {

        private RegionCellRenderer() {
            super(SwingConstants.CENTER);
            setIconTextGap(8);
        }

        @Override
        protected void setValue(Object value) {
            String region = value == null ? "Unknown" : value.toString();
            setIcon(new RegionFlagIcon(region));
            setText(region);
        }
    }

    private static final class BadgeLabel extends JLabel {

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                Color accent = ModernUi.accent();
                g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                    ThemeManager.isDarkTheme() ? 35 : 24));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private static final class DurationCellRenderer extends PlainCellRenderer {

        private DurationCellRenderer() {
            super(SwingConstants.RIGHT);
        }

        @Override
        protected void setValue(Object value) {
            setText(UiFormatters.formatDuration(value instanceof Long longValue ? longValue : 0L));
        }
    }

    private static final class InstantCellRenderer extends PlainCellRenderer {

        private InstantCellRenderer() {
            super(SwingConstants.RIGHT);
        }

        @Override
        protected void setValue(Object value) {
            setText(value instanceof Instant instant ? UiFormatters.formatInstant(instant) : "-");
        }
    }

    private enum SortMode {
        TITLE("library.sort.title"),
        RECENT("library.sort.recent"),
        MOST_PLAYED("library.sort.playTime");

        private final String key;

        SortMode(String key) {
            this.key = key;
        }

        @Override
        public String toString() {
            return I18n.tr(key);
        }
    }

}
