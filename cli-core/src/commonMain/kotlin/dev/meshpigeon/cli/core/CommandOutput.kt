package dev.meshpigeon.cli.core

/**
 * How a command finished.
 *
 * In a session nobody reads an exit status; in a piped script CI does, so the
 * number a command failed with has to mean something.
 */
public enum class ExitStatus(public val code: Int) {
    /** It worked. */
    OK(0),

    /** It did not work: a radio refused, a link dropped, a file was unreadable. */
    FAILED(1),

    /** The command was wrong: unknown command, missing or unknown argument. */
    USAGE(2),

    /** The session ended early — Ctrl-C on an in-flight command. */
    INTERRUPTED(130),
    ;

    public companion object {
        /** The worse of two statuses, for "the last non-zero status wins". */
        public fun worst(a: ExitStatus, b: ExitStatus): ExitStatus =
            if (b.code != OK.code) b else a
    }
}

/** What a command produced: a typed model a renderer can draw or serialise. */
public sealed interface CommandOutput

/** The model behind `/help`. */
public data class HelpOutput(
    val commands: List<HelpEntry>,
    val footer: String,
) : CommandOutput

/** One row of `/help`: enough to render, and to complete from. */
public data class HelpEntry(
    /** The full invocation, e.g. `/radio connect`. */
    val command: String,
    val summary: String,
    /** Argument names, in order; empty when the command takes none. */
    val arguments: List<String>,
) {
    /** `connect <target>` — what a person reads. */
    public val usage: String get() = (listOf(command) + arguments).joinToString(" ")
}

/** Something went wrong, in the words of the catalog. */
public data class ErrorOutput(
    val command: String,
    val reason: String,
) : CommandOutput

/** A plain line of text, for the commands that are just a sentence. */
public data class TextOutput(val text: String) : CommandOutput

/** Nothing to show. */
public data object EmptyOutput : CommandOutput

/**
 * The JSON envelope every `/json` response is wrapped in.
 *
 * `schema` is the version of this shape: a script can assert on it, and a
 * change to the output models has to decide whether the schema moved.
 */
@kotlinx.serialization.Serializable
public data class JsonEnvelope(
    val schema: Int = SCHEMA,
    val command: String,
    val ok: Boolean,
    val status: Int,
    val data: JsonElement? = null,
    val error: JsonMessage? = null,
) {
    @kotlinx.serialization.Serializable
    public data class JsonMessage(val reason: String)

    public companion object {
        /** The version of this envelope. */
        public const val SCHEMA: Int = 1
    }
}

/** The `data` field of an envelope: whatever the command's model serialised to. */
public typealias JsonElement = kotlinx.serialization.json.JsonElement