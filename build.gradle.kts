plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/**
 * `meshpigeon-cli` may use `meshpigeon-core`'s public API and nothing else
 * (docs/architecture.md §"the import gate"): no `java.`, no SQLDelight, no
 * generated protobuf type, no core `internal` package, and no chip or vendor
 * identifier — an `sx1262` in the CLI would mean hardware knowledge had leaked
 * out from under the firmware's own abstraction.
 *
 * `meshpigeon-core-testing` is the one exception, and only in test sources.
 * `scripts/check-imports.sh` is the gate; it runs as part of `check`.
 */
val checkImports by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails when the CLI reaches past meshpigeon-core's public API"
    commandLine("sh", "scripts/check-imports.sh")
    workingDir = rootDir
}

/**
 * The localization gate: no user-facing string in logic, and no message id the
 * catalog does not carry. It is what makes a second locale a translation job
 * rather than a refactor.
 */
val checkI18n by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails on a string literal in logic, or a message id with no catalog entry"
    commandLine("sh", "scripts/check-i18n.sh")
    workingDir = rootDir
}

tasks.named("check") {
    dependsOn(checkImports)
    dependsOn(checkI18n)
}
