package dev.meshpigeon.cli.app

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.main

/**
 * `mp` on the JVM.
 *
 * The only options are the ones the library gives for free: `--help` and
 * `--version`. Everything else happens inside the session, which is why a piped
 * script and a person type exactly the same things.
 */
public class MpJvmCommand : CliktCommand(name = "mp") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "MeshPigeon — open the session. /help lists commands, /quit leaves.\n\n" +
            "Options:\n  --help    Show this message and exit\n  --version Show the version and exit"

    override fun run() {
        var status = 0
        runSession(onExit = { status = it })
        throw MpExit(status)
    }
}

private class MpExit(val status: Int) : RuntimeException(null, null, false, false)

public fun main(args: Array<String>) {
    try {
        MpJvmCommand().main(args)
    } catch (e: MpExit) {
        kotlin.system.exitProcess(e.status)
    } catch (e: CliktError) {
        kotlin.system.exitProcess(if (e.statusCode == 0) 0 else 2)
    }
}
