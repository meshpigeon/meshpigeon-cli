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

    // The product is the native binary called `mp`; the JVM jar exists for
    // scripts and IDEs, and is not what anybody installs.
    listOf(linuxX64(), linuxArm64(), macosX64(), macosArm64()).forEach { target ->
        target.binaries {
            executable("mp") {
                entryPoint = "dev.meshpigeon.cli.app.main"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":cli-journal"))
            implementation(libs.clikt)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}