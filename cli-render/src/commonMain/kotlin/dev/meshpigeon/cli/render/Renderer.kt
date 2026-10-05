package dev.meshpigeon.cli.render

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import dev.meshpigeon.cli.core.ClearOutput
import dev.meshpigeon.cli.core.CommandOutput
import dev.meshpigeon.cli.core.DeviceSettingsOutput
import dev.meshpigeon.cli.core.CommandResult
import dev.meshpigeon.cli.core.DoctorOutput
import dev.meshpigeon.cli.core.EmptyOutput
import dev.meshpigeon.cli.core.ErrorOutput
import dev.meshpigeon.cli.core.ExitStatus
import dev.meshpigeon.cli.core.HelpEntry
import dev.meshpigeon.cli.core.HelpOutput
import dev.meshpigeon.cli.core.HistoryOutput
import dev.meshpigeon.cli.core.JsonEnvelope
import dev.meshpigeon.cli.core.LinkListOutput
import dev.meshpigeon.cli.core.PacketView
import dev.meshpigeon.cli.core.RadioInfoOutput
import dev.meshpigeon.cli.core.RadioStatusOutput
import dev.meshpigeon.cli.core.TextOutput
import dev.meshpigeon.cli.core.TuningOutput
import dev.meshpigeon.cli.i18n.Messages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The two renderers every command output has.
 *
 * A command produces a typed model; this draws it for a person or serialises it
 * for a script. Two renderers of one model cannot drift the way two command
 * paths cannot — which is why `/json on` is a session command rather than a
 * flag with its own implementation.
 *
 * Colour and terminal width come from Mordant. The chat screen's live display
 * and the styled transcript arrive with P5; what is here already renders width
 * correctly and honours `AnsiLevel`, so `/color off` is a real switch.
 */
public class Renderer(
    /** The catalog: every word this renderer draws comes from it. */
    private val messages: Messages,
    /** Whether the session wants colour; the session rebuilds when it changes. */
    public val color: Boolean = true,
    /** Fixed width for tests and for piped output; `null` uses the terminal's. */
    width: Int? = null,
) {
    private val terminal: Terminal = Terminal(
        ansiLevel = if (color) null else AnsiLevel.NONE,
        width = width,
        interactive = false,
    )

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    /** The human view: what a person reads. */
    public fun human(output: CommandOutput): String = when (output) {
        is TextOutput -> output.text
        EmptyOutput -> ""
        is ErrorOutput -> "✗ ${output.reason}"
        is HelpOutput -> helpLines(output)
        is RadioInfoOutput -> infoLines(output)
        is RadioStatusOutput -> statusLines(output)
        is TuningOutput -> tuningLines(output)
        is DeviceSettingsOutput -> settingsLines(output)
        is HistoryOutput -> historyLines(output)
        is LinkListOutput -> linkLines(output)
        is DoctorOutput -> doctorLines(output)
        is ClearOutput -> ""
    }

    /**
     * The machine view: the JSON envelope, one object per command.
     *
     * `schema` moves when the shape does, so a script can assert on it, and
     * `status` is the number a piped session exits with.
     */
    public fun machine(command: String, result: CommandResult): String {
        val envelope = JsonEnvelope(
            command = command,
            ok = result.status == ExitStatus.OK,
            status = result.status.code,
            data = when (val output = result.output) {
                is TextOutput -> buildJsonObject { put("text", output.text) }
                is ErrorOutput -> buildJsonObject {
                    put("command", output.command)
                    put("reason", output.reason)
                }
                EmptyOutput, is ClearOutput -> null
                else -> encode(output)
            },
            error = (result.output as? ErrorOutput)?.let { JsonEnvelope.JsonMessage(it.reason) },
        )
        return json.encodeToString(JsonEnvelope.serializer(), envelope)
    }

    /** Wraps text the way the terminal would, so width and colour are handled once. */
    public fun wrap(line: String): String = if (line.isEmpty()) line else terminal.render(line)

    private fun encode(output: CommandOutput): JsonObject = when (output) {
        is HelpOutput -> buildJsonObject {
            put("footer", output.footer)
            put("commands", buildJsonArray {
                output.commands.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("command", entry.command)
                            put("summary", entry.summary)
                            put("arguments", buildJsonArray { entry.arguments.forEach { add(JsonPrimitive(it)) } })
                        },
                    )
                }
            })
        }

        is RadioInfoOutput -> buildJsonObject {
            put("board", output.device.board)
            put("firmware", output.device.firmware)
            put("spec", output.device.spec)
            put("uptime", output.device.uptime)
            put("boots", output.device.boots)
            put("battery", output.device.battery)
            put("noiseFloor", output.device.noiseFloor)
            put("radioOk", output.device.radioOk)
            put("packetsHeld", output.device.packetsHeld)
            put("storeCapacityBytes", output.device.storeCapacityBytes)
            put("storeDropped", output.device.storeDropped)
            put("capabilities", buildJsonArray { output.device.capabilities.forEach { add(JsonPrimitive(it)) } })
        }

        is RadioStatusOutput -> buildJsonObject {
            put("wifiState", output.wifiState)
            put("wifiSsid", output.wifiSsid)
            put("wifiIpv4", output.wifiIpv4)
            put("wifiPort", output.wifiPort)
            put("bleClients", output.bleClients)
            put("usbClients", output.usbClients)
            put("tcpClients", output.tcpClients)
        }

        is TuningOutput -> buildJsonObject {
            put("frequencyHz", output.tuning.frequencyHz)
            put("bandwidthHz", output.tuning.bandwidthHz)
            put("spreadingFactor", output.tuning.spreadingFactor)
            put("codingRate", output.tuning.codingRate)
            put("powerDbm", output.tuning.powerDbm)
            put("epoch", output.tuning.epoch)
        }

        is HistoryOutput -> buildJsonObject {
            put("delivered", output.delivered)
            put("summary", output.summary)
            put("packets", buildJsonArray { output.packets.forEach { add(encode(it)) } })
        }

        is DeviceSettingsOutput -> buildJsonObject {
            put("name", output.name)
            put("wifiEnabled", output.wifiEnabled)
            put("wifiSsid", output.wifiSsid)
            put("wifiPort", output.wifiPort)
            put("wifiPassword", output.wifiPassword)
        }

        is LinkListOutput -> buildJsonObject {
            put("links", buildJsonArray {
                output.links.forEach {
                    add(buildJsonObject {
                        put("name", it.name)
                        put("target", it.target)
                        put("kind", it.kind)
                    })
                }
            })
        }

        is DoctorOutput -> buildJsonObject {
            put("version", output.version)
            put("language", output.language)
            put("profile", output.profile)
            put("home", output.home)
            put("json", output.json)
            put("color", output.color)
            put("phase", output.phase)
            put("links", buildJsonArray {
                output.links.forEach {
                    add(buildJsonObject {
                        put("name", it.name)
                        put("target", it.target)
                        put("kind", it.kind)
                    })
                }
            })
            output.radio?.let { radio ->
                put("radio", buildJsonObject {
                    put("board", radio.board)
                    put("firmware", radio.firmware)
                    put("spec", radio.spec)
                    put("uptime", radio.uptime)
                    put("radioOk", radio.radioOk)
                })
            }
        }

        is TextOutput -> buildJsonObject { put("text", output.text) }
        is ErrorOutput -> buildJsonObject {
            put("command", output.command)
            put("reason", output.reason)
        }
        EmptyOutput, is ClearOutput -> JsonObject(emptyMap())
    }

    private fun encode(packet: PacketView): JsonObject = buildJsonObject {
        put("seq", packet.seq)
        put("at", packet.at)
        put("origin", packet.origin)
        put("bytes", packet.bytes)
        put("hex", packet.hex)
        packet.rssi?.let { put("rssi", it) }
        packet.snr?.let { put("snr", it) }
    }

    private fun helpLines(output: HelpOutput): String = buildString {
        val width = output.commands.maxOfOrNull { it.command.length + it.arguments.size * 8 } ?: 0
        output.commands.forEach { entry ->
            appendLine(entry.helpLine(width))
        }
        appendLine()
        append(output.footer)
    }

    private fun HelpEntry.helpLine(padTo: Int): String {
        val usage = usage
        return "  $usage${" ".repeat((padTo - usage.length).coerceAtLeast(1))}  $summary"
    }

    private fun infoLines(output: RadioInfoOutput): String {
        val device = output.device
        return pairs(
            messages.t("label.board") to device.board,
            messages.t("label.firmware") to device.firmware,
            messages.t("label.spec") to device.spec.toString(),
            messages.t("label.uptime") to device.uptime,
            messages.t("label.boots") to device.boots.toString(),
            messages.t("label.battery") to device.battery,
            messages.t("label.noise-floor") to device.noiseFloor,
            messages.t("label.radio") to if (device.radioOk) "ok" else "failed",
            messages.t("label.packets-held") to "${device.packetsHeld} of ${device.storeCapacityBytes} B",
            messages.t("label.dropped") to device.storeDropped.toString(),
            messages.t("label.capabilities") to device.capabilities.joinToString(", ").ifEmpty { "—" },
        )
    }

    private fun statusLines(output: RadioStatusOutput): String = pairs(
        messages.t("label.wifi") to "${output.wifiState} ${output.wifiSsid} ${output.wifiIpv4}:${output.wifiPort}".trim(),
        messages.t("label.ble-clients") to output.bleClients.toString(),
        messages.t("label.usb-clients") to output.usbClients.toString(),
        messages.t("label.tcp-clients") to output.tcpClients.toString(),
    )

    private fun tuningLines(output: TuningOutput): String {
        val tuning = output.tuning
        return pairs(
            messages.t("label.frequency") to "${tuning.frequencyHz} Hz",
            messages.t("label.bandwidth") to "${tuning.bandwidthHz} Hz",
            messages.t("label.spreading-factor") to tuning.spreadingFactor.toString(),
            messages.t("label.coding-rate") to "4/${tuning.codingRate}",
            messages.t("label.power") to "${tuning.powerDbm} dBm",
            messages.t("label.epoch") to tuning.epoch.toString(),
        )
    }

    private fun settingsLines(output: DeviceSettingsOutput): String = pairs(
        messages.t("label.name") to output.name,
        messages.t("label.wifi") to if (output.wifiEnabled) "enabled ${output.wifiSsid}:${output.wifiPort}" else "off",
        messages.t("label.passphrase") to output.wifiPassword,
    )

    private fun historyLines(output: HistoryOutput): String = buildString {
        appendLine(output.summary)
        output.packets.forEach { packet ->
            val signal = listOfNotNull(
                packet.rssi?.let { "$it dBm" },
                packet.snr?.let { "snr $it" },
            ).joinToString(" ")
            val suffix = if (signal.isEmpty()) "" else "  ($signal)"
            appendLine("  #${packet.seq} ${packet.origin} ${packet.hex}$suffix")
        }
    }.trimEnd()

    private fun linkLines(output: LinkListOutput): String =
        output.links.joinToString("\n") { "${it.name} · ${it.kind} · ${it.target}" }

    private fun doctorLines(output: DoctorOutput): String = pairs(
        messages.t("label.version") to output.version,
        messages.t("label.language") to output.language,
        messages.t("label.profile") to output.profile,
        messages.t("label.home") to output.home,
        messages.t("label.json") to output.json.toString(),
        messages.t("label.color") to output.color.toString(),
        messages.t("label.phase") to output.phase,
        messages.t("label.links") to output.links.joinToString(", ") { it.name }.ifEmpty { "—" },
        messages.t("label.radio") to (output.radio?.let { "${it.board} ${it.firmware}" } ?: "—"),
    )

    private fun pairs(vararg entries: Pair<String, String>): String {
        val padTo = entries.maxOfOrNull { it.first.length } ?: 0
        return entries.joinToString("\n") { (key, value) -> "  $key${" ".repeat((padTo - key.length).coerceAtLeast(1))}  $value" }
    }
}
