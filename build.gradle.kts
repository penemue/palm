import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Calendar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jmh)
    alias(libs.plugins.license)
    `maven-publish`
}

// JitPack derives the coordinate from the repository: group is com.github.<user>, artifact is the
// repository name, and the version is the tag it builds, passed in as -PpalmVersion.
group = "com.github.penemue"
version = (findProperty("palmVersion") as String?) ?: "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// The committed corpus lives with the tests; the file list and the expected total are declared
// beside it, in com.github.penemue.palm.corpus, and every other source set reads both from there.
val corpusResources = "src/test/resources"

// The corpus benchmarks live in their own source set, so that the `test` task cannot see them and
// the IDE routes a test class to `test` rather than to the benchmark task.
val benchmark: SourceSet = sourceSets.create("benchmark") {
    compileClasspath += sourceSets.test.get().output
    runtimeClasspath += sourceSets.test.get().output
}

configurations[benchmark.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[benchmark.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

jmh {
    jmhVersion = libs.versions.jmh.core.get()
}

license {
    header = rootProject.file("copyright.ftl")
    strictCheck = true
    ext["year"] = Calendar.getInstance().get(Calendar.YEAR)
    ext["owner"] = "Vyacheslav Lukianov"
    ext["ownerURL"] = "https://github.com/penemue"
    include("**/*.kt")
    mapping("kt", "JAVADOC_STYLE")
}

// A microbenchmark measures production internals, and `internal` reaches only inside the module, so
// the jmh compilation needs the same friend-path association the test one gets by default.
kotlin.target.compilations.named("jmh") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

kotlin.target.compilations.named(benchmark.name) {
    associateWith(kotlin.target.compilations.getByName("main"))
}

// A model benchmark must run on real data, so it reads the same committed Canterbury corpus the
// round-trip tests and the size benchmarks use, through the loader declared beside them.
sourceSets.named("jmh") {
    compileClasspath += sourceSets.test.get().output
    runtimeClasspath += sourceSets.test.get().output
    resources.srcDir(corpusResources)
}

// The toolchain compiles, the target is what the classes run on. Since the toolchain is the newer
// of the two, -Xjdk-release confines Kotlin to the target's API instead of only stamping its
// class-file version.
val jdkToolchain = 25
val javaTarget = JavaVersion.VERSION_1_8

kotlin {
    jvmToolchain(jdkToolchain)
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(javaTarget.toString())
        freeCompilerArgs.add("-Xjdk-release=$javaTarget")
    }
}

java {
    sourceCompatibility = javaTarget
    targetCompatibility = javaTarget
    withSourcesJar()
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "Palm"
                description = "A toolkit for building lossless compression methods in Kotlin from interchangeable parts"
                url = "https://github.com/penemue/palm"
                packaging = "jar"
                scm {
                    url = "https://github.com/penemue/palm"
                    connection = "scm:git:https://github.com/penemue/palm.git"
                    developerConnection = "scm:git:https://github.com/penemue/palm.git"
                }
                licenses {
                    license {
                        name = "The Apache Software License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "penemue"
                        name = "Vyacheslav Lukianov"
                    }
                }
            }
        }
    }
}

// Optional JFR profiling: -PjfrProfile=<file> records a CPU profile of the test JVM.
fun Test.configureJfrProfile() {
    if (project.hasProperty("jfrProfile")) {
        val recording = project.property("jfrProfile").toString().ifBlank { "build/profile.jfr" }
        jvmArgs(
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+DebugNonSafepoints",
            "-XX:StartFlightRecording=settings=profile,filename=${file(recording).absolutePath},dumponexit=true"
        )
    }
}

tasks.test {
    useJUnitPlatform()
    configureJfrProfile()
}

// The corpus benchmarks measure size only and cost minutes; `build` must not pay for them, and the
// Silesia ones download ~68 MB, which would make a fresh clone unbuildable offline.
tasks.register<Test>("benchmark") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the Canterbury and Silesia compression-ratio benchmarks."
    testClassesDirs = benchmark.output.classesDirs
    classpath = benchmark.runtimeClasspath
    useJUnitPlatform()
    // The measurement IS the printed table, so it must reach the console rather than the XML alone.
    testLogging.showStandardStreams = true
    // A benchmark reports through stdout, which an up-to-date task would never produce.
    outputs.upToDateWhen { false }
    configureJfrProfile()
}
