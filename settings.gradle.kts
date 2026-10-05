rootProject.name = "meshpigeon-cli"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// `meshpigeon-core` lives in its own repository. During P1–P6 the two develop
// against each other through a composite build; once core is published this
// becomes a plain version reference and the `includeBuild` goes away.
includeBuild("../meshpigeon-core") {
    dependencySubstitution {
        substitute(module("dev.meshpigeon:meshpigeon-core")).using(project(":core-aggregate"))
        substitute(module("dev.meshpigeon:meshpigeon-core-testing")).using(project(":core-testing"))
    }
}

include(":cli-i18n")
include(":cli-core")
include(":cli-render")
include(":cli-journal")
include(":cli-app")