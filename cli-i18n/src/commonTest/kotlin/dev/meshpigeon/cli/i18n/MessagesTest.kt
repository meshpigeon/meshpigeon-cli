package dev.meshpigeon.cli.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagesTest {
    private val messages = defaultMessages()

    @Test
    fun a_message_with_no_arguments_resolves_to_its_template() {
        assertEquals("the packet store was cleared", messages.t("radio.purged"))
    }

    @Test
    fun named_placeholders_are_substituted() {
        assertEquals(
            "connected to 10.0.0.5:5000 (XIAO WIO)",
            messages.t("radio.connected", "name" to "10.0.0.5:5000", "board" to "XIAO WIO"),
        )
    }

    @Test
    fun positional_arguments_follow_the_order_the_placeholders_appear() {
        assertEquals(
            "saw 0102ff (received, rssi -90, snr 7.5)",
            messages.tp("radio.observed", "0102ff", "received", -90, 7.5),
        )
    }

    @Test
    fun an_empty_argument_renders_as_empty() {
        assertEquals("no link called ", messages.t("link.unknown", "name" to ""))
    }

    @Test
    fun an_absent_argument_leaves_the_placeholder_visible() {
        // Visible, not blank: a missing argument is a bug worth seeing.
        assertEquals("no link called {name}", messages.t("link.unknown"))
    }

    @Test
    fun an_unknown_id_resolves_to_itself_rather_than_to_nothing() {
        assertEquals("no.such.message", messages.t("no.such.message"))
        assertFalse(messages.has("no.such.message"))
    }

    @Test
    fun plurals_are_two_keys_not_a_branch_in_logic() {
        assertEquals("1 message", messages.plural("chat.message", 1))
        assertEquals("4 messages", messages.plural("chat.message", 4))
    }

    @Test
    fun every_catalog_id_has_a_template() {
        val missing = EnglishCatalog.messages.filterValues { it.isBlank() }.keys
        assertTrue(missing.isEmpty(), "blank templates: $missing")
    }

    @Test
    fun every_template_balances_its_placeholders() {
        val unbalanced = EnglishCatalog.messages.filter { (id, template) ->
            Regex("""\{([a-zA-Z0-9_]*)}""").findAll(template).any { it.groupValues[1].isEmpty() }
        }
        assertTrue(unbalanced.isEmpty(), "unbalanced placeholders: ${unbalanced.keys}")
    }

    @Test
    fun the_fallback_language_is_used_for_anything_a_catalog_is_missing() {
        val german = Messages(
            language = "de",
            catalogs = listOf(Catalog("de", mapOf("radio.purged" to "Paketspeicher geleert")), Catalog("en", EnglishCatalog.messages)),
        )
        assertEquals("Paketspeicher geleert", german.t("radio.purged"))
        // An id German does not carry falls back rather than failing.
        assertEquals("no radio is connected", german.t("radio.none"))
        assertEquals("no.such.message", german.t("no.such.message"))
    }
}

class CatalogSelectionTest {
    private val english = Catalog("en", EnglishCatalog.messages)
    private val german = Catalog("de", mapOf("radio.purged" to "Paketspeicher geleert"))
    private val regionalGerman = Catalog("de-AT", mapOf("radio.purged" to "Paketspeicher geleert (AT)"))

    @Test
    fun an_exact_language_tag_wins() {
        assertEquals("de-AT", selectCatalog(listOf(english, german, regionalGerman), "de-AT").language)
    }

    @Test
    fun a_base_language_still_selects_a_catalog() {
        // `LANG=de_DE.UTF-8` arrives reduced to `de` by the platform layer; a
        // catalog tagged `de-AT` would not match it, and a base match does.
        assertEquals("de", selectCatalog(listOf(english, german), "de").language)
    }

    @Test
    fun an_unknown_language_falls_back_to_english() {
        val selected = selectCatalog(listOf(english, german), "fr")
        assertEquals("en", selected.language)
        assertEquals("the packet store was cleared", selected.t("radio.purged"))
    }

    @Test
    fun no_language_at_all_falls_back_to_english() {
        assertEquals("en", selectCatalog(listOf(english, german), null).language)
    }
}

class CatalogTest {
    @Test
    fun a_catalog_reports_whether_it_carries_an_id() {
        val catalog = Catalog("en", EnglishCatalog.messages)
        assertTrue(catalog.has("radio.purged"))
        assertNull(catalog.template("nope"))
        assertTrue(catalog.ids.contains("radio.purged"))
    }
}