package dev.meshpigeon.cli.app

import kotlinx.cinterop.ExperimentalForeignApi

/**
 * `mp`, the native executable.
 *
 * No application options: the session *is* the interface, and `--help` /
 * `--version` are the only words the entry point itself understands. Everything
 * else — radios, links, output mode — happens inside, as a slash command.
 */
@OptIn(ExperimentalForeignApi::class)
public fun main(args: Array<String>) {
    val arguments = args.toList()
    when {
        arguments.isEmpty() -> Unit
        arguments == listOf("--help") || arguments == listOf("-h") -> {
            println(
                """
                Usage: mp

                MeshPigeon opens the session. Type /help for commands and /quit to leave.
                Options:
                  --help      Show this message and exit
                  --version   Show the version and exit
                """.trimIndent(),
            )
            return
        }

        arguments == listOf("--version") -> {
            println("meshpigeon-cli $CLI_VERSION")
            return
        }

        else -> {
            println("mp takes no arguments; everything happens in the session. Try `mp --help`.")
            return
        }
    }

    var status = 0
    runSession(onExit = { status = it })
    platform.posix.exit(status)
}
