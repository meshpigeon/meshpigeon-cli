package dev.meshpigeon.cli.core

/**
 * One declared argument of a command.
 *
 * Arguments are declared, not free text, because one declaration has to drive
 * the interactive parser, `/help`, tab completion and the piped-script parser —
 * if they were separate, they would drift.
 */
public data class ArgSpec(
    /** The name a person reads, e.g. `target`. */
    public val name: String,
    /** Whether the command cannot run without it. */
    public val required: Boolean = true,
    /** Catalog id describing what it takes. */
    public val help: String? = null,
)

/** What a command produced, and how it finished. */
public data class CommandResult(
    public val status: ExitStatus,
    public val output: CommandOutput,
) {
    public companion object {
        public fun ok(output: CommandOutput): CommandResult = CommandResult(ExitStatus.OK, output)
        public fun failure(output: CommandOutput): CommandResult =
            CommandResult(ExitStatus.FAILED, output)
    }
}

/**
 * One command in the session's vocabulary.
 *
 * [run] receives the words after the command name, already validated against
 * [arguments]; it never sees a raw line, so a command cannot parse differently
 * from how `/help` describes it.
 */
public class CommandSpec(
    /** The name as typed, without the leading slash: `connect`. */
    public val name: String,
    /** Catalog id for the one-line summary. */
    public val summaryKey: String,
    public val arguments: List<ArgSpec> = emptyList(),
    /**
     * Commands that change how the session renders — `/json on`, `/color off` —
     * so their own output follows the switch, not the mode before it.
     */
    public val affectsRendering: Boolean = false,
    public val run: suspend (CommandContext, List<String>) -> CommandResult,
)

/**
 * A command with subcommands: `/radio connect tcp://…`.
 *
 * A group may also do something itself (`/link` lists the links), which is why
 * [run] is optional.
 */
public class CommandGroup(
    public val name: String,
    public val summaryKey: String,
    public val subcommands: List<CommandSpec>,
    public val run: (suspend (CommandContext, List<String>) -> CommandResult)? = null,
) {
    /**
     * The group as a command in its own right, so `/link` and `/radio` resolve
     * through the same path as everything else instead of a special case.
     */
    public val self: CommandSpec? = run?.let { action ->
        CommandSpec(name, summaryKey) { context, _ -> action(context, emptyList()) }
    }
}

/** What a line of input asked for. */
public sealed interface Resolution {
    /** A command to run, with its arguments. */
    public data class Found(
        val command: String,
        val spec: CommandSpec,
        val arguments: List<String>,
    ) : Resolution

    /** The group exists; the subcommand does not. */
    public data class UnknownSubcommand(val group: String, val name: String) : Resolution

    /** Nothing at all matches. */
    public data class UnknownCommand(val path: String) : Resolution

    /** The command needs an argument that was not given. */
    public data class MissingArgument(val command: String, val argument: String) : Resolution

    /** The command was given something it does not take. */
    public data class UnexpectedArgument(val command: String, val argument: String) : Resolution
}

/**
 * The whole session vocabulary.
 *
 * One declaration drives four things — the interactive parser, `/help`, tab
 * completion and the piped-script parser — which is why they cannot drift: they
 * are the same data.
 */
public class CommandTree(
    /** Commands that stand alone, like `/help` and `/quit`. */
    public val topLevel: List<CommandSpec>,
    /** Commands with subcommands, like `/radio`. */
    public val groups: List<CommandGroup>,
) {
    private val topLevelByName: Map<String, CommandSpec> = topLevel.associateBy { it.name }
    private val groupByName: Map<String, CommandGroup> = groups.associateBy { it.name }

    /** Resolves one line of input into something to run. */
    public fun resolve(line: String): Resolution {
        val words = tokenize(line)
        if (words.isEmpty()) return Resolution.UnknownCommand("")
        val head = words.first().removePrefix("/")
        val rest = words.drop(1)

        val top = topLevelByName[head]
        if (top != null) {
            val required = top.arguments.firstOrNull { it.required }
            if (required != null && rest.isEmpty()) {
                return Resolution.MissingArgument("/$head", required.name)
            }
            if (rest.size > top.arguments.size) {
                return Resolution.UnexpectedArgument("/$head", rest[top.arguments.size])
            }
            return Resolution.Found("/$head", top, rest)
        }

        val group = groupByName[head]
        if (group != null) {
            if (rest.isEmpty()) {
                // A group with no subcommand runs its own action, if it has one.
                val self = group.self
                return if (self != null) {
                    Resolution.Found("/$head", self, emptyList())
                } else {
                    Resolution.UnknownSubcommand("/$head", "")
                }
            }
            val sub = group.subcommands.firstOrNull { it.name == rest.first() }
                ?: return Resolution.UnknownSubcommand("/$head", rest.first())
            val args = rest.drop(1)
            val required = sub.arguments.firstOrNull { it.required }
            if (required != null && args.isEmpty()) return Resolution.MissingArgument("/$head ${sub.name}", required.name)
            if (args.size > sub.arguments.size) {
                return Resolution.UnexpectedArgument("/$head ${sub.name}", args[sub.arguments.size])
            }
            return Resolution.Found("/$head ${sub.name}", sub, args)
        }

        return Resolution.UnknownCommand(words.joinToString(" "))
    }

    /**
     * Completion candidates for a partially typed line.
     *
     * Only what can be completed here is offered: commands, subcommands and the
     * target of a command that already has enough words.
     */
    public fun completions(line: String): List<String> {
        val words = tokenize(line)
        val head = words.firstOrNull()?.removePrefix("/")
        val group = head?.let { groupByName[it] }
        if (group != null) {
            val typed = words.getOrNull(1)
            // Once a subcommand is complete there is nothing to offer yet:
            // argument completion arrives with contacts and channels.
            if (typed != null && group.subcommands.any { it.name == typed }) return emptyList()
            return group.subcommands.map { it.name }
                .filter { it.startsWith(typed.orEmpty()) }
                .sorted()
        }
        val prefix = head.orEmpty()
        return (topLevel.map { "/${it.name}" } + groups.map { "/${it.name}" })
            .filter { it.removePrefix("/").startsWith(prefix) }
            .sorted()
    }

    /** Every command, flattened and fully qualified, for `/help`. */
    public fun allCommands(): List<HelpEntry> = buildList {
        topLevel.forEach { add(it.toHelpEntry()) }
        groups.forEach { group ->
            group.self?.let { add(it.toHelpEntry()) }
            group.subcommands.forEach { add(it.toHelpEntry(group.name)) }
        }
    }

    private fun CommandSpec.toHelpEntry(group: String? = null): HelpEntry = HelpEntry(
        command = if (group == null) "/$name" else "/$group $name",
        summary = summaryKey,
        arguments = arguments.map { it.name },
    )

}

/**
 * Splits a line into words, honouring quotes.
 *
 * `send --to "#channel with spaces" "hello there"` has four words, not six —
 * quoting is the only escaping a session needs, and a command that wants raw
 * text can join the rest back together itself.
 */
public fun tokenize(line: String): List<String> {
    val words = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var started = false
    for (char in line) {
        when {
            quote != null && char == quote -> quote = null
            quote == null && (char == '"' || char == '\'') -> {
                quote = char
                started = true
            }
            quote == null && char.isWhitespace() -> {
                if (started || current.isNotEmpty()) {
                    words += current.toString()
                    current.clear()
                    started = false
                }
            }
            else -> current.append(char)
        }
    }
    if (started || current.isNotEmpty()) words += current.toString()
    return words
}