// The build targets a JDK 25 toolchain, which a clean CI image (JitPack in particular) does not
// ship; the resolver downloads it instead of failing the build.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "palm"
