package dev.meshpigeon.cli.core

import dev.meshpigeon.core.testing.InMemoryRadioRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TokenizeTest {
    @Test
    fun splits_on_whitespace() {
        assertEquals(listOf("/radio", "connect", "127.0.0.1:5000"), tokenize("/radio connect 127.0.0.1:5000"))
    }

    @Test
    fun collapses_runs_of_whitespace() {
        assertEquals(listOf("/link", "add", "bench", "tcp://10.0.0.5:5000"), tokenize("  /link   add\tbench  tcp://10.0.0.5:5000 "))
    }

    @Test
    fun keeps_quoted_text_together() {
        assertEquals(
            listOf("/send", "--to", "#test channel", "on my way"),
            tokenize("/send --to \"#test channel\" \"on my way\""),
        )
    }

    @Test
    fun an_empty_line_is_no_words() {
        assertTrue(tokenize("").isEmpty())
        assertTrue(tokenize("   ").isEmpty())
    }

    @Test
    fun an_empty_pair_of_quotes_is_a_word() {
        assertEquals(listOf("/send", ""), tokenize("/send \"\""))
    }
}

class CommandTreeTest {
    private val tree = SessionCommands(LinkBook(InMemoryRadioRepository()), "test").tree()

    @Test
    fun resolves_a_top_level_command() {
        val resolution = tree.resolve("/help")
        assertIs<Resolution.Found>(resolution)
        assertEquals("/help", resolution.command)
    }

    @Test
    fun resolves_a_subcommand_with_its_arguments() {
        val resolution = tree.resolve("/radio connect 127.0.0.1:5000")
        assertIs<Resolution.Found>(resolution)
        assertEquals("/radio connect", resolution.command)
        assertEquals(listOf("127.0.0.1:5000"), resolution.arguments)
    }

    @Test
    fun a_group_with_no_subcommand_runs_its_own_action() {
        val resolution = tree.resolve("/link")
        assertIs<Resolution.Found>(resolution)
        assertEquals("/link", resolution.command)
    }

    @Test
    fun a_command_without_arguments_takes_none() {
        assertIs<Resolution.Found>(tree.resolve("/radio info"))
        assertIs<Resolution.UnexpectedArgument>(tree.resolve("/radio info now"))
    }

    @Test
    fun a_missing_required_argument_is_named() {
        val resolution = tree.resolve("/radio connect")
        assertIs<Resolution.MissingArgument>(resolution)
        assertEquals("target", resolution.argument)
    }

    @Test
    fun an_unknown_subcommand_says_so() {
        val resolution = tree.resolve("/radio transmute")
        assertIs<Resolution.UnknownSubcommand>(resolution)
        assertEquals("transmute", resolution.name)
    }

    @Test
    fun an_unknown_command_says_so() {
        val resolution = tree.resolve("/chat open #test")
        assertIs<Resolution.UnknownCommand>(resolution)
    }

    @Test
    fun completions_offer_commands_then_subcommands() {
        assertEquals(listOf("/clear", "/color"), tree.completions("/c"))
        assertTrue(tree.completions("/radio ").contains("connect"))
        assertEquals(listOf("disconnect"), tree.completions("/radio d"))
    }

    @Test
    fun help_lists_every_command_fully_qualified() {
        val commands = tree.allCommands().map { it.command }
        assertTrue("/help" in commands)
        assertTrue("/radio connect" in commands)
        assertTrue("/link list" in commands)
    }

    @Test
    fun every_command_has_a_summary_in_the_catalog() {
        val messages = dev.meshpigeon.cli.i18n.defaultMessages()
        val missing = tree.allCommands().filterNot { messages.has(it.summary) }
        assertTrue(missing.isEmpty(), "no catalog entry for: ${missing.map { it.command }}")
    }
}

class LinkTargetTest {
    @Test
    fun parses_a_full_tcp_url() {
        val target = LinkTarget.parse("tcp://10.0.0.5:5000")
        assertEquals(LinkDescriptorRef.tcp("10.0.0.5", 5000), target)
    }

    @Test
    fun parses_a_bare_host_and_port() {
        assertEquals(LinkDescriptorRef.tcp("radio.local", 8765), LinkTarget.parse("radio.local:8765"))
    }

    @Test
    fun parses_a_usb_path() {
        assertEquals(LinkDescriptorRef.usb("/dev/ttyACM0"), LinkTarget.parse("usb:/dev/ttyACM0"))
    }

    @Test
    fun refuses_what_it_cannot_read() {
        assertEquals(null, LinkTarget.parse("nonsense"))
        assertEquals(null, LinkTarget.parse("host:notaport"))
        assertEquals(null, LinkTarget.parse("tcp://host"))
    }

    @Test
    fun every_link_kind_survives_being_written_and_read_back() {
        // A saved link name is resolved by formatting the descriptor and parsing
        // it again. A label cannot do that: `/dev/ttyACM0` has no scheme, so it
        // reads back as nothing — which is a bug a TCP-only build never sees.
        val descriptors = listOf(
            LinkDescriptorRef.tcp("10.0.0.5", 5000),
            LinkDescriptorRef.usb("/dev/ttyACM0"),
        )
        for (descriptor in descriptors) {
            val text = LinkTarget.format(descriptor)
            assertEquals(descriptor, LinkTarget.parse(text), "$descriptor did not round-trip as $text")
        }
    }

    @Test
    fun a_label_is_not_a_target() {
        // The distinction is the point: the label reads better, and the canonical
        // form is what has to survive a round trip.
        assertEquals("/dev/ttyACM0", linkLabel(LinkDescriptorRef.usb("/dev/ttyACM0")))
        assertEquals("usb:/dev/ttyACM0", LinkTarget.format(LinkDescriptorRef.usb("/dev/ttyACM0")))
    }
}

/** Terse constructors, so the tests read as data. */
private object LinkDescriptorRef {
    fun tcp(host: String, port: Int): dev.meshpigeon.core.transport.LinkDescriptor =
        dev.meshpigeon.core.transport.LinkDescriptor.Tcp(host, port)

    fun usb(path: String): dev.meshpigeon.core.transport.LinkDescriptor =
        dev.meshpigeon.core.transport.LinkDescriptor.Usb(path)
}