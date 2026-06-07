package com.vlessclient.app;

/**
 * Resolves the running application version from the git tag the build was
 * produced from, so the About screen and the auto-update check never drift
 * from the actual release.
 *
 * <p>The value is resolved once, in order of preference, from:
 * <ol>
 *   <li>the {@code app.version} system property — injected into the packaged
 *       macOS app by jpackage ({@code --java-options "-Dapp.version=<tag>"}),
 *       where {@code <tag>} is the git tag the release workflow built;</li>
 *   <li>the {@code Implementation-Version} of the JAR manifest, when running
 *       from a built jar;</li>
 *   <li>{@code "dev"} as a last resort for IDE / source runs.</li>
 * </ol>
 */
public final class AppVersion {

    /** Resolved at class-load; the version cannot change during a run. */
    public static final String VERSION = resolve();

    private AppVersion() {
    }

    private static String resolve() {
        String fromProperty = System.getProperty("app.version");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.strip();
        }
        String fromManifest = AppVersion.class.getPackage().getImplementationVersion();
        if (fromManifest != null && !fromManifest.isBlank()) {
            return fromManifest.strip();
        }
        return "dev";
    }
}
