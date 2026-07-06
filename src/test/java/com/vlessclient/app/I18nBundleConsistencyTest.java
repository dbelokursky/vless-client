package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Guards the invariants of the i18n bundles: en/ru key parity, resolution of every key
 * referenced from production code, and MessageFormat validity of placeholder patterns.
 */
class I18nBundleConsistencyTest {

    private static final String EN_BUNDLE = "/i18n/messages_en.properties";
    private static final String RU_BUNDLE = "/i18n/messages_ru.properties";

    /** Matches string-literal keys passed to I18n.get("...") or I18n.binding("..."). */
    private static final Pattern KEY_REFERENCE =
            Pattern.compile("I18n\\.(?:get|binding)\\(\\s*\"([^\"]+)\"");

    /** Matches values containing a MessageFormat placeholder such as {0} or {1,number}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d");

    @Test
    void enAndRuKeySetsMatch() {
        Set<String> enKeys = loadBundle(EN_BUNDLE).stringPropertyNames();
        Set<String> ruKeys = loadBundle(RU_BUNDLE).stringPropertyNames();
        assertThat(enKeys).isNotEmpty();

        Set<String> missingInRu = new TreeSet<>(enKeys);
        missingInRu.removeAll(ruKeys);
        Set<String> missingInEn = new TreeSet<>(ruKeys);
        missingInEn.removeAll(enKeys);

        assertThat(missingInRu).as("keys present in en but missing in ru").isEmpty();
        assertThat(missingInEn).as("keys present in ru but missing in en").isEmpty();
    }

    @Test
    void everyKeyReferencedInCodeExists() throws IOException {
        Path mainSources = Path.of("src", "main", "java");
        Assumptions.assumeTrue(Files.isDirectory(mainSources),
                "main sources not present in working directory; skipping source scan");

        Set<String> enKeys = loadBundle(EN_BUNDLE).stringPropertyNames();
        Map<String, Set<String>> referencedKeys = scanReferencedKeys(mainSources);
        assertThat(referencedKeys).isNotEmpty();

        List<String> missing = referencedKeys.entrySet().stream()
                .filter(entry -> !enKeys.contains(entry.getKey()))
                .map(entry -> entry.getKey() + " (referenced from " + entry.getValue() + ")")
                .toList();

        assertThat(missing)
                .as("keys referenced via I18n.get/I18n.binding but absent from " + EN_BUNDLE)
                .isEmpty();
    }

    @Test
    void messageFormatPatternsAreWellFormed() {
        List<String> malformed = new ArrayList<>();
        int checked = collectMalformedPatterns("en", loadBundle(EN_BUNDLE), malformed)
                + collectMalformedPatterns("ru", loadBundle(RU_BUNDLE), malformed);

        assertThat(checked).as("bundle values containing {n} placeholders").isPositive();
        assertThat(malformed)
                .as("bundle values with {n} placeholders rejected by MessageFormat")
                .isEmpty();
    }

    /**
     * Validates every placeholder-bearing value of the bundle against MessageFormat,
     * appending a diagnostic line per invalid value; returns the number of values checked.
     */
    private static int collectMalformedPatterns(
            String bundleName, Properties bundle, List<String> malformed) {
        int checked = 0;
        for (String key : new TreeSet<>(bundle.stringPropertyNames())) {
            String value = bundle.getProperty(key);
            if (!PLACEHOLDER.matcher(value).find()) {
                continue;
            }
            checked++;
            try {
                new MessageFormat(value);
            } catch (RuntimeException e) {
                malformed.add(bundleName + ":" + key + "=" + value + " -> " + e.getMessage());
            }
        }
        return checked;
    }

    /** Maps every string-literal i18n key used in main sources to the files referencing it. */
    private static Map<String, Set<String>> scanReferencedKeys(Path root) throws IOException {
        Map<String, Set<String>> keys = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        Matcher matcher = KEY_REFERENCE.matcher(readFile(path));
                        while (matcher.find()) {
                            keys.computeIfAbsent(matcher.group(1), k -> new TreeSet<>())
                                    .add(path.getFileName().toString());
                        }
                    });
        }
        return keys;
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + path, e);
        }
    }

    /**
     * Loads a bundle as UTF-8, mirroring the PropertyResourceBundle default since Java 9;
     * the escaped-ASCII ru bundle and the raw-UTF-8 en bundle both load correctly this way.
     */
    private static Properties loadBundle(String resourcePath) {
        Properties properties = new Properties();
        try (InputStream stream =
                I18nBundleConsistencyTest.class.getResourceAsStream(resourcePath)) {
            assertThat(stream).as("classpath resource " + resourcePath).isNotNull();
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + resourcePath, e);
        }
        return properties;
    }
}
