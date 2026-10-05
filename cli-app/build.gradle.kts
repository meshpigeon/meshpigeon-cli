plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    // `expect`/`actual` classes are used deliberately here: a socket, a clock and
    // a terminal stream are genuine platform boundaries. The Beta warning is
    // noise.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()

    // `mp` is the native binary. A target only *links* where the host can: the
    // macOS links are already skipped by Kotlin/Native on a Linux host, and a
    // Linux link needs that arch's `libsqlite3` — so an x86 host builds
    // `linuxX64` and an aarch64 host builds `linuxArm64`, rather than each
    // failing to cross-link an arch-specific system library (D57). The klib for
    // every target still builds; only the executable is host-scoped. A bundled
    // SQLite that Kotlin/Native compiles per target would let one host link every
    // arch, which is the permanent fix D52/D57 point at.
    val hostArch = System.getProperty("os.arch")
    val linuxExe = if (hostArch == "aarch64" || hostArch == "arm64") linuxArm64() else linuxX64()
    listOf(linuxExe, macosX64(), macosArm64()).forEach { target ->
        target.binaries {
            executable("mp") {
                entryPoint = "dev.meshpigeon.cli.app.main"
            }
        }
    }

    // The native SQLDelight driver links the *system* `libsqlite3`, and
    // Kotlin/Native does not carry SQLDelight's `-lsqlite3` across to this final
    // executable link — it reaches `core-storage`'s own link but not ours — so the
    // binary names it too. `--allow-shlib-undefined` because that system library
    // has libc references the link has not resolved yet (see `core-storage`).
    // Linux only: macOS links its system SQLite from libSystem with none of this.
    linuxX64 { binaries.all { linkerOpts("-lsqlite3", "-Wl,--allow-shlib-undefined") } }
    linuxArm64 { binaries.all { linkerOpts("-lsqlite3", "-Wl,--allow-shlib-undefined") } }

    sourceSets {
        commonMain.dependencies {
            api(project(":cli-journal"))
            // The app owns the database file — it opens, locks and closes it —
            // so it names `MeshPigeonStore` and the repository directly. What it
            // never names is the driver: that stays inside `core-storage`.
            implementation(libs.meshpigeon.core)
            implementation(libs.clikt)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}