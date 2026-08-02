package io.cruxstack.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class VerifyPluginJarTask extends DefaultTask {
    @InputFile
    public abstract RegularFileProperty getArchiveFile();

    @Input
    public abstract Property<String> getExpectedVersion();

    @TaskAction
    public void verifyArchive() throws IOException {
        File archive = getArchiveFile().get().getAsFile();
        if (!archive.isFile()) {
            throw new GradleException("Plugin JAR was not created: " + archive);
        }

        Set<String> names = new HashSet<>();
        try (JarFile jar = new JarFile(archive)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                require(names.add(name), "Plugin JAR contains a duplicate entry: " + name);
                require(isAllowedEntry(name), "Plugin JAR contains an unexpected entry: " + name);
                require(!name.contains("..") && !name.startsWith("/"), "Plugin JAR contains an unsafe path: " + name);
            }

            String expectedVersion = getExpectedVersion().get();
            Attributes attributes = jar.getManifest().getMainAttributes();
            require(
                    expectedVersion.equals(attributes.getValue("Implementation-Version")),
                    "Manifest Implementation-Version does not match " + expectedVersion);
            require("21".equals(attributes.getValue("Build-Jdk-Spec")), "Manifest must declare Java 21 compatibility");

            ZipEntry descriptorEntry = jar.getEntry("metabase-plugin.yaml");
            require(descriptorEntry != null, "metabase-plugin.yaml must be at the JAR root");
            String descriptor = new String(jar.getInputStream(descriptorEntry).readAllBytes(), StandardCharsets.UTF_8);
            require(
                    descriptor.lines().anyMatch(line -> line.trim().equals("version: " + expectedVersion)),
                    "Plugin descriptor version does not match " + expectedVersion);
        }

        require(names.contains("metabase/driver/prometheus.clj"), "Clojure adapter source is missing");
        require(
                names.contains("io/cruxstack/metabase/prometheus/PrometheusDriver.class"),
                "Kotlin driver facade is missing");
    }

    private static boolean isAllowedEntry(String name) {
        if (ALLOWED_DIRECTORIES.contains(name)) {
            return true;
        }
        if (name.equals("META-INF/MANIFEST.MF")
                || name.equals("metabase-plugin.yaml")
                || name.equals("metabase/driver/prometheus.clj")) {
            return true;
        }
        if (name.startsWith("META-INF/") && name.endsWith(".kotlin_module")) {
            return name.indexOf('/', "META-INF/".length()) < 0;
        }
        String classPrefix = "io/cruxstack/metabase/prometheus/";
        return name.startsWith(classPrefix)
                && name.endsWith(".class")
                && name.indexOf('/', classPrefix.length()) < 0;
    }

    private static final List<String> ALLOWED_DIRECTORIES = List.of(
            "META-INF/",
            "io/",
            "io/cruxstack/",
            "io/cruxstack/metabase/",
            "io/cruxstack/metabase/prometheus/",
            "metabase/",
            "metabase/driver/");

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new GradleException(message);
        }
    }
}
