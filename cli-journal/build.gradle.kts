plugins {
    alias(libs.plugins.kotlin.multiplatform)
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

    sourceSets {
        commonMain.dependencies {
            api(project(":cli-core"))
            api(project(":cli-render"))
            api(project(":cli-i18n"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // The fakes come from core's own testing module; a CLI test drives
            // the real vocabulary against a radio that is not a radio.
            implementation("dev.meshpigeon:meshpigeon-core-testing:0.1.0-SNAPSHOT")
        }
    }
}