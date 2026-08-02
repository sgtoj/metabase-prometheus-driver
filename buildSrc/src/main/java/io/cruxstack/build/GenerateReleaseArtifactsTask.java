package io.cruxstack.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

public abstract class GenerateReleaseArtifactsTask extends DefaultTask {
    @InputFile
    public abstract RegularFileProperty getPluginJar();

    @Input
    public abstract Property<String> getReleaseVersion();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void generate() throws IOException, NoSuchAlgorithmException {
        File output = getOutputDirectory().get().getAsFile();
        clearDirectory(output.toPath());
        Files.createDirectories(output.toPath());

        String version = getReleaseVersion().get();
        String jarName = "metabase-prometheus-driver-" + version + ".jar";
        File jar = new File(output, jarName);
        Files.copy(
                getPluginJar().get().getAsFile().toPath(),
                jar.toPath(),
                StandardCopyOption.REPLACE_EXISTING);

        String checksum = sha256(jar);
        Files.writeString(
                new File(output, jarName + ".sha256").toPath(),
                checksum + "  " + jarName + "\n",
                StandardCharsets.US_ASCII);

        Files.writeString(
                new File(output, "metabase-prometheus-driver-" + version + ".cdx.json").toPath(),
                sbom(version, checksum),
                StandardCharsets.UTF_8);
    }

    private static void clearDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        List<Path> paths;
        try (var entries = Files.walk(directory)) {
            paths = entries.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static String sbom(String version, String jarChecksum) {
        String root = "pkg:github/cruxstack/metabase-prometheus-driver@" + version;
        String kotlin = "pkg:maven/org.jetbrains.kotlin/kotlin-stdlib@2.2.0";
        return """
                {
                  "bomFormat": "CycloneDX",
                  "specVersion": "1.6",
                  "version": 1,
                  "metadata": {
                    "component": {
                      "type": "library",
                      "bom-ref": "%s",
                      "group": "io.cruxstack.metabase",
                      "name": "metabase-prometheus-driver",
                      "version": "%s",
                      "hashes": [{"alg": "SHA-256", "content": "%s"}],
                      "licenses": [{"license": {"id": "Apache-2.0"}}],
                      "purl": "%s"
                    }
                  },
                  "components": [
                    {
                      "type": "library",
                      "bom-ref": "%s",
                      "group": "org.jetbrains.kotlin",
                      "name": "kotlin-stdlib",
                      "version": "2.2.0",
                      "scope": "required",
                      "purl": "%s",
                      "properties": [{"name": "metabase:provided", "value": "true"}]
                    }
                  ],
                  "dependencies": [
                    {"ref": "%s", "dependsOn": ["%s"]},
                    {"ref": "%s", "dependsOn": []}
                  ]
                }
                """.formatted(root, version, jarChecksum, root, kotlin, kotlin, root, kotlin, kotlin);
    }
}
