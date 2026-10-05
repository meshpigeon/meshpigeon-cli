@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.meshpigeon.cli.app

import dev.meshpigeon.cli.journal.InputSource
import dev.meshpigeon.cli.journal.OutputSink
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.fflush
import platform.posix.fgets
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.FILE
import platform.posix.stdin
import platform.posix.stdout
import platform.posix.isatty

internal actual fun defaultHome(): String =
    env("MP_HOME")
        ?: env("XDG_DATA_HOME")?.let { "$it/meshpigeon" }
        ?: env("HOME")?.let { "$it/.local/share/meshpigeon" }
        ?: "."

internal actual fun profileFromEnvironment(): String = env("MP_PROFILE") ?: "default"

internal actual fun stdinIsATty(): Boolean = isatty(0) == 1

/**
 * Standard input, one line at a time.
 *
 * POSIX `fgets` rather than the stdlib's `readLine()`: a session reads lines
 * until end of input, and the stdlib reader hands a whole pipe back as one line
 * when stdin is not a terminal — which would turn a script into a single
 * unreadable command.
 */
internal actual fun platformInput(): InputSource {
    val stream = stdin?.let { LineStream(it) }
    return InputSource { stream?.readLine() }
}

/** Standard output, one line at a time, flushed the way a terminal expects. */
internal actual fun platformOutput(): OutputSink {
    val stream = stdout?.let { LineStream(it) }
    return OutputSink { line -> stream?.writeLine(line) ?: println(line) }
}

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }

/**
 * A C `FILE*` read and written a line at a time.
 *
 * Lines longer than the buffer are joined: `fgets` stops at the buffer, not at
 * the newline, and a pasted contact card is longer than 4 KiB.
 */
@OptIn(ExperimentalForeignApi::class)
/** A C `FILE*` read and written a line at a time. */
private class LineStream(private val file: CPointer<FILE>) {
    private val buffer = ByteArray(BUFFER_BYTES)
    private val pending = StringBuilder()

    fun readLine(): String? {
        if (pending.isNotEmpty() && pending.endsWith('\n')) return pending.takePending()
        while (true) {
            val chunk = memScoped {
                buffer.usePinned { pinned -> fgets(pinned.addressOf(0), buffer.size.convert(), file) }
            } ?: return null
            // `fgets` null-terminates, so the bytes up to the first zero are the
            // line it read.
            val end = buffer.indexOf(0).let { if (it < 0) buffer.size else it }
            pending.append(buffer.decodeToString(0, end))
            if (pending.endsWith('\n')) return pending.takePending()
        }
    }

    fun writeLine(line: String) {
        fputs("$line\n", file)
        fflush(file)
    }

    private fun StringBuilder.takePending(): String {
        val line = toString().trimEnd('\n', '\r')
        clear()
        return line
    }

    private companion object {
        const val BUFFER_BYTES = 4096
    }
}
