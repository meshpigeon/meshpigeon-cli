package dev.meshpigeon.cli.journal

import dev.meshpigeon.cli.core.ClearOutput
import dev.meshpigeon.cli.core.CommandContext
import dev.meshpigeon.cli.core.CommandResult
import dev.meshpigeon.cli.core.CommandSpec
import dev.meshpigeon.cli.core.CommandTree
import dev.meshpigeon.cli.core.EmptyOutput
import dev.meshpigeon.cli.core.ExitStatus
import dev.meshpigeon.cli.core.usageError
import dev.meshpigeon.cli.core.Resolution
import dev.meshpigeon.cli.core.SessionState
import dev.meshpigeon.cli.i18n.Messages
import dev.meshpigeon.cli.render.Renderer

/** Where a session's output goes. The terminal in production, a list in tests. */
public fun interface OutputSink {
    /** Writes one line (no trailing newline is added for the prompt itself). */
    public fun write(line: String)
}

/** What a session reads. stdin in production, a script in tests. */
public fun interface InputSource {
    /** The next line, or `null` at end of input (Ctrl-D, or the end of a file). */
    public suspend fun readLine(): String?
}

/**
 * The session: one loop that reads commands, runs them and prints the result.
 *
 * There is no second path. A piped script is this loop with a file for stdin and
 * no line editor, which is why "it works by hand but not in CI" cannot happen —
 * the same code runs in both, and the difference is only whether a prompt is
 * drawn.
 */
public class Session(
    private val tree: CommandTree,
    private val context: CommandContext,
    private val input: InputSource,
    private val output: OutputSink,
    /** Whether a prompt is drawn. Off for a piped script, on for a person. */
    private val interactive: Boolean = false,
) {
    private var renderer = Renderer(context.messages, color = context.state.color)

    /** The exit status a piped session ends with: the last non-zero one. */
    public var exitStatus: ExitStatus = ExitStatus.OK
        private set

    /** Runs until the input ends or a quit command arrives. */
    public suspend fun run() {
        banner()
        loop()
    }

    private suspend fun loop() {
        while (true) {
            if (interactive) output.write(prompt())
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val quit = execute(line)
            if (quit) break
        }
        if (interactive) output.write(context.messages.t("session.bye"))
    }

    /**
     * Runs one line. Returns true when the session should end.
     *
     * Public because a test drives the session the same way a person does: one
     * line at a time, no shortcuts.
     */
    public suspend fun execute(line: String): Boolean {
        val (command, spec, result) = resolveAndRun(line)
        exitStatus = ExitStatus.worst(exitStatus, result.status)
        context.state.lastStatus = result.status
        print(command, result, jsonAfter = spec.affectsRendering)
        return command == "/quit" || command == "/exit"
    }

    private suspend fun resolveAndRun(line: String): Triple<String, CommandSpec, CommandResult> =
        when (val resolution = tree.resolve(line)) {
            is Resolution.Found -> Triple(
                resolution.command,
                resolution.spec,
                resolution.spec.run(context, resolution.arguments),
            )
            is Resolution.MissingArgument -> Triple(
                resolution.command,
                NoSpec,
                usageError(
                    context.messages,
                    resolution.command,
                    "usage.missing_argument",
                    "argument" to resolution.argument,
                ),
            )

            is Resolution.UnexpectedArgument -> Triple(
                resolution.command,
                NoSpec,
                usageError(
                    context.messages,
                    resolution.command,
                    "usage.unknown_argument",
                    "argument" to resolution.argument,
                ),
            )

            is Resolution.UnknownSubcommand -> Triple(
                resolution.group,
                NoSpec,
                CommandResult(
                    ExitStatus.USAGE,
                    dev.meshpigeon.cli.core.ErrorOutput(
                        resolution.group,
                        context.messages.t("usage.unknown_command", "command" to resolution.name),
                    ),
                ),
            )

            is Resolution.UnknownCommand -> Triple(
                line.trim().ifEmpty { "/" },
                NoSpec,
                CommandResult(
                    ExitStatus.USAGE,
                    dev.meshpigeon.cli.core.ErrorOutput(
                        line.trim().ifEmpty { "/" },
                        context.messages.t("session.unknown_command", "command" to line.trim()),
                    ),
                ),
            )
        }

    private fun print(command: String, result: CommandResult, jsonAfter: Boolean = false) {
        if (result.output is ClearOutput) {
            output.write(ANSI_CLEAR)
            return
        }
        if (result.output == EmptyOutput) return
        // `/color off` takes effect from the next line, including this one.
        if (renderer.color != context.state.color) {
            renderer = Renderer(context.messages, color = context.state.color)
        }

        val jsonMode = context.state.json || jsonAfter
        val text = if (jsonMode) {
            renderer.machine(command, result)
        } else {
            renderer.human(result.output)
        }
        if (text.isNotEmpty()) output.write(text)
    }

    private fun prompt(): String = context.messages.t("session.prompt")

    private fun banner() {
        val line = dev.meshpigeon.cli.core.bannerText(context, identity = null)
        output.write(line)
    }

    private companion object {
        /**
         * A stand-in spec for a resolution that never reaches a command — an
         * unknown command has no arguments to validate and changes no rendering.
         */
        val NoSpec: CommandSpec = CommandSpec("", "") { _, _ ->
            CommandResult(ExitStatus.OK, EmptyOutput)
        }

        /** Enough to clear a screen that drew itself; harmless when it did not. */
        const val ANSI_CLEAR = "[2J[H"
    }
}

/** Builds a session from the pieces an entry point has. */
public fun sessionOf(
    tree: CommandTree,
    messages: Messages,
    state: SessionState,
    radio: dev.meshpigeon.cli.core.RadioController,
    db: dev.meshpigeon.cli.core.DatabaseStatus,
    input: InputSource,
    output: OutputSink,
    interactive: Boolean,
): Session = Session(
    tree = tree,
    context = CommandContext(messages, radio, state, db),
    input = input,
    output = output,
    interactive = interactive,
)