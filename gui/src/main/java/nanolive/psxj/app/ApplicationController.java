package nanolive.psxj.app;

import nanolive.psxj.config.AppConfig;
import nanolive.psxj.config.ConfigManager;
import nanolive.psxj.gui.MainFrame;
import nanolive.psxj.gui.ThemeManager;
import nanolive.psxj.i18n.I18n;
import nanolive.psxj.library.GameLibrary;
import nanolive.psxj.platform.audio.AudioBackendFactory;
import nanolive.psxj.platform.render.RenderBackendFactory;
import nanolive.psxj.util.Log;
import nanolive.psxj.util.TaskDispatcher;
import java.awt.EventQueue;
import java.nio.file.Path;
import java.util.List;

public final class ApplicationController {

    private final ConfigManager configManager;
    private final AppConfig config;
    private final GameLibrary gameLibrary;
    private final TaskDispatcher tasks;

    private ApplicationController(ConfigManager configManager,
                                  AppConfig config,
                                  GameLibrary gameLibrary,
                                  TaskDispatcher tasks) {
        this.configManager = configManager;
        this.config = config;
        this.gameLibrary = gameLibrary;
        this.tasks = tasks;
    }

    public static void bootstrap(String[] args) {
        var configRoot = Path.of(System.getProperty("user.home"), ".psxj");
        var configManager = new ConfigManager(configRoot);
        var config = configManager.load();
        var tasks = new TaskDispatcher();
        var gameLibrary = new GameLibrary(tasks, config);
        var controller = new ApplicationController(configManager, config, gameLibrary, tasks);
        controller.start(args == null ? List.of() : List.of(args));
    }

    private void start(List<String> args) {
        Log.info("Starting PSXJ");
        setupLookAndFeel();
        I18n.initialize(config.ui().language().toLocale());
        EventQueue.invokeLater(() -> {
            var frame = new MainFrame(
                config,
                configManager,
                gameLibrary,
                RenderBackendFactory::create,
                AudioBackendFactory::create
            );
            frame.setVisible(true);
            gameLibrary.scanAsync(config.libraryRoots());
        });
    }

    private void setupLookAndFeel() {
        ThemeManager.applyTheme(config.ui().theme());
    }
}
