package dev.meshpigeon.cli.app

import dev.meshpigeon.cli.journal.InputSource
import dev.meshpigeon.cli.journal.OutputSink

internal actual fun defaultHome(): String =
    System.getenv("MP_HOME")
        ?: System.getenv("XDG_DATA_HOME")?.let { "$it/meshpigeon" }
        ?: "${System.getProperty("user.home")}/.local/share/meshpigeon"

internal actual fun profileFromEnvironment(): String = System.getenv("MP_PROFILE") ?: "default"

/** A piped stdin has no console, which is what makes a script non-interactive. */
internal actual fun stdinIsATty(): Boolean = System.console() != null

/** Standard input, one line at a time; `null` at end of input. */
internal actual fun platformInput(): InputSource = InputSource { readlnOrNull() }

internal actual fun platformOutput(): OutputSink = OutputSink { line -> println(line) }
