import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.JavaVersion
import io.cruxstack.build.GenerateReleaseArtifactsTask
import io.cruxstack.build.VerifyPluginJarTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.4.10"
}

group = "io.cruxstack.metabase"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()
val pluginVersion = version.toString()
require(pluginVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?"))) {
    "releaseVersion must be a semantic version without a leading v"
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

sourceSets {
    main {
        resources.srcDirs("resources", "src/main/clojure")
    }
    test {
        resources.srcDir("src/test/resources")
    }
}

tasks.processResources {
    val resourcePluginVersion = pluginVersion
    inputs.property("pluginVersion", resourcePluginVersion)
    filesMatching("metabase-plugin.yaml") {
        expand("version" to resourcePluginVersion)
    }
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = maxOf(1, Runtime.getRuntime().availableProcessors() / 2)
    systemProperty("user.timezone", "UTC")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register<Test>("mimirIntegrationTest") {
    group = "verification"
    description = "Run the contract suite against the local Mimir container."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    dependsOn(tasks.jar)
    classpath = files(
        sourceSets.test.get().output,
        tasks.jar.flatMap { it.archiveFile },
        configurations.testRuntimeClasspath,
    )
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    filter {
        includeTestsMatching("*MimirIntegrationTest")
    }
    systemProperty("user.timezone", "UTC")
}

tasks.register<Test>("prometheusIntegrationTest") {
    group = "verification"
    description = "Run the contract suite against the local Prometheus container."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    dependsOn(tasks.jar)
    classpath = files(
        sourceSets.test.get().output,
        tasks.jar.flatMap { it.archiveFile },
        configurations.testRuntimeClasspath,
    )
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    filter {
        includeTestsMatching("*PrometheusIntegrationTest")
    }
    systemProperty("user.timezone", "UTC")
}

tasks.register<JavaExec>("metabaseSmoke") {
    group = "verification"
    description = "Bootstrap Metabase and execute the packaged driver through its public API."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.cruxstack.metabase.prometheus.MetabaseSmokeMain")
}

tasks.register<JavaExec>("metabaseSyntheticContract") {
    group = "verification"
    description = "Execute the packaged driver through Metabase against the synthetic Prometheus contract."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.cruxstack.metabase.prometheus.SyntheticMetabaseContractMain")
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    filePermissions {
        unix("0644")
    }
    dirPermissions {
        unix("0755")
    }
}

tasks.jar {
    archiveFileName.set("prometheus.metabase-driver.jar")
    destinationDirectory.set(layout.buildDirectory.dir("plugin"))
    manifest {
        attributes(
            "Implementation-Title" to "Metabase Prometheus Driver",
            "Implementation-Version" to pluginVersion,
            "Build-Jdk-Spec" to "21",
        )
    }
}

val pluginJar by tasks.registering {
    group = "build"
    description = "Build the Metabase plugin JAR."
    dependsOn(tasks.jar)
}

val verifyPluginJar by tasks.registering(VerifyPluginJarTask::class) {
    group = "verification"
    description = "Verify the packaged community driver has only expected runtime content."
    dependsOn(tasks.jar)
    archiveFile.set(tasks.jar.flatMap { it.archiveFile })
    expectedVersion.set(pluginVersion)
}

val releaseArtifacts by tasks.registering(GenerateReleaseArtifactsTask::class) {
    group = "distribution"
    description = "Create the release JAR, SHA-256 checksum, and CycloneDX SBOM."
    dependsOn(verifyPluginJar)
    pluginJar.set(tasks.jar.flatMap { it.archiveFile })
    releaseVersion.set(pluginVersion)
    outputDirectory.set(layout.buildDirectory.dir("release"))
}

tasks.register("lint") {
    group = "verification"
    description = "Compile production and test source with warnings treated as errors."
    dependsOn(tasks.compileKotlin, tasks.compileTestKotlin)
}

tasks.check {
    dependsOn(verifyPluginJar)
}
