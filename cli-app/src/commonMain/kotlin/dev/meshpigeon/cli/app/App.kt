package dev.meshpigeon.cli.app

import dev.meshpigeon.cli.core.CoreRadioController
import dev.meshpigeon.cli.core.LinkBook
import dev.meshpigeon.cli.core.SessionCommands
import dev.meshpigeon.cli.core.SessionState
import dev.meshpigeon.cli.i18n.environmentLanguage
import dev.meshpigeon.cli.i18n.selectCatalog
import dev.meshpigeon.cli.i18n.Catalog
import dev.meshpigeon.cli.i18n.EnglishCatalog
import dev.meshpigeon.cli.journal.InputSource
import dev.meshpigeon.cli.journal.OutputSink
import dev.meshpigeon.cli.journal.Session
import dev.meshpigeon.cli.journal.sessionOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The version this binary reports, from the build. */
public const val CLI_VERSION: String = "0.1.0"

/**
 * Builds the session, from the parts an entry point supplies.
 *
 * Everything the session needs is a parameter, which is what makes a test able
 * to drive the real vocabulary: a scripted [input], a collecting [output], and a
 * radio controller that is not a radio.
 */
public fun buildSession(
    input: InputSource,
    output: OutputSink,
    interactive: Boolean,
    radio: dev.meshpigeon.cli.core.RadioController,
    home: String,
    profile: String,
    scope: CoroutineScope,
    messages: dev.meshpigeon.cli.i18n.Messages = selectCatalog(listOf(Catalog(LanguageTag.EN, EnglishCatalog.messages)), environmentLanguage()),
): Session {
    val state = SessionState(version = CLI_VERSION, home = home, profile = profile).apply {
        color = interactive
    }
    val links = LinkBook()
    val tree = SessionCommands(links, CLI_VERSION).tree()
    return sessionOf(tree, messages, state, radio, input, output, interactive)
}

/** The real radio, the real terminal, and the real stdin. */
public fun runSession(
    input: InputSource = platformInput(),
    output: OutputSink = platformOutput(),
    interactive: Boolean = stdinIsATty(),
    home: String = defaultHome(),
    profile: String = profileFromEnvironment(),
    onExit: (Int) -> Unit = {},
) {
    val scope = CoroutineScope(SupervisorJob())
    // One catalog for the whole session: the renderer resolves the same words
    // the controller refuses a transport with, so they cannot disagree.
    val messages = selectCatalog(listOf(Catalog(LanguageTag.EN, EnglishCatalog.messages)), environmentLanguage())
    val session = buildSession(
        input = input,
        output = output,
        interactive = interactive,
        radio = CoreRadioController(parentScope = scope, messages = messages),
        home = home,
        profile = profile,
        scope = scope,
        messages = messages,
    )
    kotlinx.coroutines.runBlocking { session.run() }
    scope.cancel()
    onExit(session.exitStatus.code)
}

/** The language tag every catalog here carries. */
private object LanguageTag {
    const val EN: String = "en"
}

/** Where `MP_HOME`, or the XDG data directory, points. */
internal expect fun defaultHome(): String

/** The profile `MP_PROFILE` asks for, or `default`. */
internal expect fun profileFromEnvironment(): String

/** Whether stdin is a terminal, which is what makes the session interactive. */
internal expect fun stdinIsATty(): Boolean

/** Standard input, one line at a time. */
internal expect fun platformInput(): InputSource

/** Standard output, one line at a time. */
internal expect fun platformOutput(): OutputSink