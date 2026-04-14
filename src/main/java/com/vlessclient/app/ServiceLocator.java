package com.vlessclient.app;

import com.vlessclient.model.AppSettings;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.RoutingService;
import com.vlessclient.service.ShareLinkExporter;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.SingBoxConfigGenerator;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.service.UpdateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple service locator providing manual dependency injection.
 * Holds singleton instances of core application services.
 */
public class ServiceLocator {

    private static final Logger log = LoggerFactory.getLogger(ServiceLocator.class);
    private static final Map<Class<?>, Object> services = new ConcurrentHashMap<>();
    private static String singBoxPath;

    private ServiceLocator() {
    }

    /**
     * Creates and registers all service instances.
     */
    public static void initialize() {
        singBoxPath = resolveSingBoxPath();
        log.info("sing-box binary path: {}", singBoxPath != null ? singBoxPath : "NOT FOUND");

        ConfigStore configStore = new ConfigStore();
        register(ConfigStore.class, configStore);

        register(AppSettings.class, configStore.getSettings());

        ThemeManager themeManager = new ThemeManager();
        register(ThemeManager.class, themeManager);

        if (singBoxPath != null) {
            SingBoxEngine singBoxEngine = new SingBoxEngine(Path.of(singBoxPath));
            register(SingBoxEngine.class, singBoxEngine);
        } else {
            log.warn("sing-box binary not found; SingBoxEngine will not be available");
        }

        SingBoxConfigGenerator configGenerator = new SingBoxConfigGenerator();
        register(SingBoxConfigGenerator.class, configGenerator);

        ShareLinkParser shareLinkParser = new ShareLinkParser();
        register(ShareLinkParser.class, shareLinkParser);

        ShareLinkExporter shareLinkExporter = new ShareLinkExporter();
        register(ShareLinkExporter.class, shareLinkExporter);

        TrafficMonitor trafficMonitor = new TrafficMonitor();
        register(TrafficMonitor.class, trafficMonitor);

        LatencyTester latencyTester = new LatencyTester();
        register(LatencyTester.class, latencyTester);

        RoutingService routingService = new RoutingService();
        register(RoutingService.class, routingService);

        SubscriptionService subscriptionService =
                new SubscriptionService(configStore, shareLinkParser);
        register(SubscriptionService.class, subscriptionService);
        subscriptionService.startAutoRefresh();

        UpdateManager updateManager = new UpdateManager();
        register(UpdateManager.class, updateManager);
        updateManager.startPeriodicCheck();

        log.info("ServiceLocator initialized");
    }

    /**
     * Retrieves a registered service instance.
     *
     * @param type the service class
     * @param <T>  the service type
     * @return the singleton instance
     * @throws IllegalArgumentException if no service of that type is registered
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(Class<T> type) {
        Object service = services.get(type);
        if (service == null) {
            throw new IllegalArgumentException("No service registered for: " + type.getName());
        }
        return (T) service;
    }

    /**
     * Registers a service instance.
     */
    public static <T> void register(Class<T> type, T instance) {
        services.put(type, instance);
    }

    /**
     * Returns the resolved path to the sing-box binary, or null if not found.
     */
    public static String getSingBoxPath() {
        return singBoxPath;
    }

    /**
     * Cleans up resources on application shutdown.
     */
    public static void shutdown() {
        log.info("ServiceLocator shutting down");

        try {
            Object updater = services.get(UpdateManager.class);
            if (updater instanceof UpdateManager updateManager) {
                updateManager.shutdown();
            }
        } catch (Exception e) {
            log.error("Error stopping UpdateManager during shutdown", e);
        }

        try {
            Object subService = services.get(SubscriptionService.class);
            if (subService instanceof SubscriptionService subscriptionService) {
                subscriptionService.stopAutoRefresh();
            }
        } catch (Exception e) {
            log.error("Error stopping SubscriptionService during shutdown", e);
        }

        try {
            Object monitor = services.get(TrafficMonitor.class);
            if (monitor instanceof TrafficMonitor trafficMonitor) {
                trafficMonitor.stop();
            }
        } catch (Exception e) {
            log.error("Error stopping TrafficMonitor during shutdown", e);
        }

        try {
            Object tester = services.get(LatencyTester.class);
            if (tester instanceof LatencyTester latencyTester) {
                latencyTester.shutdown();
            }
        } catch (Exception e) {
            log.error("Error stopping LatencyTester during shutdown", e);
        }

        try {
            Object engine = services.get(SingBoxEngine.class);
            if (engine instanceof SingBoxEngine singBoxEngine && singBoxEngine.isRunning()) {
                log.info("Stopping sing-box engine");
                singBoxEngine.stop();
            }
        } catch (Exception e) {
            log.error("Error stopping SingBoxEngine during shutdown", e);
        }

        services.clear();
    }

    /**
     * Resolves the sing-box binary path by checking multiple locations:
     * 1. Bundled inside the macOS .app bundle: Contents/Resources/sing-box
     * 2. Common install location: /usr/local/bin/sing-box
     * 3. Anywhere on the system PATH
     */
    private static String resolveSingBoxPath() {
        // 1. Check bundled location (macOS .app bundle)
        String bundledPath = System.getProperty("java.home");
        if (bundledPath != null) {
            Path appBundlePath = Path.of(bundledPath).getParent();
            if (appBundlePath != null) {
                Path resourcePath = appBundlePath.resolve("Resources").resolve("sing-box");
                if (Files.isExecutable(resourcePath)) {
                    return resourcePath.toAbsolutePath().toString();
                }
            }
        }

        // 2. Check /usr/local/bin/sing-box
        Path usrLocalPath = Path.of("/usr/local/bin/sing-box");
        if (Files.isExecutable(usrLocalPath)) {
            return usrLocalPath.toString();
        }

        // 3. Check system PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                Path candidate = Path.of(dir, "sing-box");
                if (Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            }
        }

        return null;
    }
}
