package dev.meshpigeon.cli.journal

import dev.meshpigeon.cli.core.CoreRadioController
import dev.meshpigeon.cli.core.CommandContext
import dev.meshpigeon.cli.core.ExitStatus
import dev.meshpigeon.cli.core.LinkBook
import dev.meshpigeon.cli.core.SessionCommands
import dev.meshpigeon.cli.core.SessionState
import dev.meshpigeon.cli.i18n.Catalog
import dev.meshpigeon.cli.i18n.EnglishCatalog
import dev.meshpigeon.cli.i18n.Messages
import dev.meshpigeon.core.testing.FakeRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The session, driven the way a person drives it — one line at a time.
 *
 * The radio here is the real session over core's fake radio: every command in
 * these tests goes through frames, the protobuf API and back. That is also why
 * the same tests cover a piped script, because a piped script *is* this loop with
 * a file for stdin: there is no second implementation that could differ.
 */
class SessionTest {
    private data class Run(val session: Session, val output: List<String>)

    private fun drive(
        script: List<String>,
        interactive: Boolean = false,
        radio: FakeRadio = FakeRadio(),
        linkFactory: ((dev.meshpigeon.core.transport.LinkDescriptor) -> dev.meshpigeon.core.transport.RadioLink)? = null,
    ): Run {
        val consumed = mutableListOf<String>()
        val output = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob())
        val messages = Messages(language = "en", catalogs = listOf(Catalog("en", EnglishCatalog.messages)))
        val state = SessionState(version = "test", home = "/tmp/mp", profile = "default")
        val session = Session(
            tree = SessionCommands(LinkBook(), "test").tree(),
            context = CommandContext(
                messages = messages,
                radio = CoreRadioController(
                    parentScope = scope,
                    messages = messages,
                    linkFactory = linkFactory ?: { radio.link },
                ),
                state = state,
            ),
            input = { script.drop(consumed.size).firstOrNull().also { if (it != null) consumed += it } },
            output = { output += it },
            interactive = interactive,
        )
        runBlocking { session.run() }
        scope.cancel()
        return Run(session, output)
    }

    @Test
    fun a_usage_error_never_shows_an_unfilled_placeholder() {
        // A message whose placeholders do not match the arguments a caller
        // passes renders as "{command} does not take {argument}" — a missing
        // translation that looks like a broken command. The catalog gate can
        // see that an id exists; only driving the commands can see this.
        val run = drive(
            listOf(
                "/radio connect nonsense",
                "/link add bench nonsense",
                "/radio history not-a-number",
                "/radio retune not-a-frequency",
                "/link remove",
                "/nonsense",
            ),
        )
        val said = run.output.joinToString("\n")
        assertFalse(
            said.contains("{"),
            "an unfilled placeholder reached the terminal:\n$said",
        )
        assertEquals(ExitStatus.USAGE, run.session.exitStatus)
    }

    @Test
    fun a_script_ends_when_the_input_does() {
        val run = drive(listOf("/version", "/help"))
        assertEquals(ExitStatus.OK, run.session.exitStatus)
        assertTrue(run.output.first().startsWith("MeshPigeon test"), "banner first: ${run.output.first()}")
    }

    @Test
    fun a_script_and_a_human_session_run_the_same_commands() {
        val script = listOf("/json on", "/doctor", "/quit")
        val piped = drive(script, interactive = false)
        val interactive = drive(script, interactive = true)

        assertEquals(piped.session.exitStatus, interactive.session.exitStatus)
        // The interactive session additionally draws a prompt per line and says
        // goodbye; a piped script wants neither.
        assertEquals(piped.output, interactive.output.filterNot { it == "mp ▸ " || it == "bye" })
    }

    @Test
    fun the_exit_status_is_the_last_failure() {
        val run = drive(listOf("/radio info", "/help", "/nonsense"))
        assertEquals(ExitStatus.USAGE, run.session.exitStatus)
    }

    @Test
    fun a_clean_script_exits_zero() {
        assertEquals(ExitStatus.OK, drive(listOf("/help", "/version", "/doctor")).session.exitStatus)
    }

    @Test
    fun an_unknown_command_is_a_usage_error_with_a_message() {
        val run = drive(listOf("/chat open #test"))
        assertEquals(ExitStatus.USAGE, run.session.exitStatus)
        assertTrue(run.output.any { it.contains("unknown command") }, "output: ${run.output}")
    }

    @Test
    fun a_missing_argument_says_which_one() {
        val run = drive(listOf("/radio connect"))
        assertEquals(ExitStatus.USAGE, run.session.exitStatus)
        assertTrue(run.output.any { it.contains("target") }, "output: ${run.output}")
    }

    @Test
    fun connecting_reads_the_radio_and_info_shows_it() {
        val run = drive(listOf("/radio connect 127.0.0.1:5000", "/radio info"))

        assertEquals(ExitStatus.OK, run.session.exitStatus)
        val info = run.output.joinToString("\n")
        assertTrue(info.contains("FAKE"), info)
        assertTrue(info.contains("Wi-Fi"), "capabilities: $info")
    }

    @Test
    fun tuning_can_be_read_and_changed() {
        val run = drive(listOf("/radio connect 127.0.0.1:5000", "/radio tuning", "/radio retune 915000000"))

        assertEquals(ExitStatus.OK, run.session.exitStatus)
        val text = run.output.joinToString("\n")
        assertTrue(text.contains("868500000"), "the radio's own tuning: $text")
        assertTrue(text.contains("915000000"), "the new tuning: $text")
    }

    @Test
    fun history_lists_the_packets_the_radio_holds() {
        val radio = FakeRadio()
        radio.receive(byteArrayOf(1, 2, 3), rssi = -91, snr = 88)
        val run = drive(listOf("/radio connect 127.0.0.1:5000", "/radio history"), radio = radio)

        assertEquals(ExitStatus.OK, run.session.exitStatus)
        val text = run.output.joinToString("\n")
        assertTrue(text.contains("010203"), "hex: $text")
        assertTrue(text.contains("-91"), "rssi: $text")
    }

    @Test
    fun a_link_that_cannot_be_opened_says_so_and_fails() {
        // What a TCP connect to a port with nothing behind it looks like.
        val run = drive(
            script = listOf("/radio connect 127.0.0.1:5000"),
            linkFactory = { error("Connection refused") },
        )

        assertEquals(ExitStatus.FAILED, run.session.exitStatus)
        assertTrue(run.output.any { it.contains("could not connect") }, "output: ${run.output}")
        assertTrue(run.output.any { it.contains("Connection refused") }, "output: ${run.output}")
    }

    @Test
    fun json_mode_prints_one_envelope_per_command() {
        val run = drive(listOf("/json on", "/doctor", "/json off", "/help"))
        val envelope = run.output
            .mapNotNull { line -> line.takeIf { it.startsWith("{") } }
            .map { Json.parseToJsonElement(it).jsonObject }
            .first { it["command"]!!.jsonPrimitive.content == "/doctor" }
        assertEquals("1", envelope["schema"]!!.jsonPrimitive.content)
        assertEquals("true", envelope["ok"]!!.jsonPrimitive.content)
        assertTrue(envelope.containsKey("data"), "envelope: $envelope")
        // After `/json off` the session is human again.
        assertTrue(run.output.any { it.contains("/radio connect") }, "${run.output}")
    }

    @Test
    fun a_failing_command_carries_its_reason_in_the_envelope() {
        val run = drive(listOf("/json on", "/radio info"))
        val envelope = Json.parseToJsonElement(run.output.last()).jsonObject
        assertEquals("false", envelope["ok"]!!.jsonPrimitive.content)
        assertEquals("1", envelope["status"]!!.jsonPrimitive.content)
        assertTrue(envelope.containsKey("error"), "envelope: $envelope")
    }

    @Test
    fun json_reports_a_radio_connection_as_machine_readable_data() {
        val run = drive(listOf("/radio connect 127.0.0.1:5000", "/json on", "/radio info"))
        val envelope = Json.parseToJsonElement(run.output.last()).jsonObject
        val data = envelope["data"]!!.jsonObject
        assertEquals("FAKE", data["board"]!!.jsonPrimitive.content)
        assertEquals("2", data["spec"]!!.jsonPrimitive.content)
    }

    @Test
    fun links_can_be_added_listed_and_removed() {
        val run = drive(
            listOf(
                "/link add bench tcp://127.0.0.1:5000",
                "/link list",
                "/link remove bench",
                "/link list",
            ),
        )
        assertEquals(ExitStatus.OK, run.session.exitStatus)
        assertTrue(run.output.any { it.contains("bench") }, "output: ${run.output}")
        assertTrue(run.output.any { it.contains("127.0.0.1:5000") })
        assertTrue(run.output.any { it.contains("no links are configured") })
    }

    @Test
    fun removing_a_link_that_is_not_there_fails() {
        val run = drive(listOf("/link remove ghost"))
        assertEquals(ExitStatus.FAILED, run.session.exitStatus)
    }

    @Test
    fun json_and_colour_are_session_switches() {
        val run = drive(listOf("/json on", "/json off", "/color off", "/doctor"))
        assertEquals(ExitStatus.OK, run.session.exitStatus)
        // The switch itself is announced in the mode it turns on, and the mode
        // it turns off reverts for everything after it.
        assertTrue(run.output.any { it.startsWith("{") && it.contains("JSON output is on") }, "${run.output}")
        assertTrue(run.output.any { it.startsWith("{") && it.contains("JSON output is off") }, "${run.output}")
        // `/color off` was typed while JSON was still on, so it answers in JSON.
        assertTrue(run.output.any { it.startsWith("{") && it.contains("colour is off") }, "${run.output}")
        assertFalse(run.output.any { it.contains('') }, "no colour codes when colour is off")
    }

    @Test
    fun help_lists_commands_with_their_summaries() {
        val text = drive(listOf("/help")).output.joinToString("\n")
        assertTrue(text.contains("/radio connect"), text)
        assertTrue(text.contains("open a link to a radio"), text)
    }

    @Test
    fun quit_ends_the_session_and_leaves_the_rest_of_the_script_unread() {
        val run = drive(listOf("/quit", "/help"))
        assertEquals(ExitStatus.OK, run.session.exitStatus)
        assertFalse(run.output.any { it.contains("/radio connect") }, "nothing after /quit runs")
    }
}