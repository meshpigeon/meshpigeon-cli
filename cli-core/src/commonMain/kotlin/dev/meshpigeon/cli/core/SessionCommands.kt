package dev.meshpigeon.cli.core

import dev.meshpigeon.core.common.Hex
import dev.meshpigeon.core.transport.DeviceSettingsPatch
import dev.meshpigeon.core.transport.LinkDescriptor
import dev.meshpigeon.core.transport.LinkPhase
import dev.meshpigeon.core.transport.PacketEvent
import dev.meshpigeon.core.transport.PacketOrigin
import dev.meshpigeon.core.transport.RadioError
import dev.meshpigeon.core.transport.RadioException
import dev.meshpigeon.core.transport.StoredPacket
import dev.meshpigeon.core.transport.Tuning
import dev.meshpigeon.core.transport.WifiState

/**
 * The session's vocabulary.
 *
 * Every visible word comes from the catalog through [CommandContext.messages]; a
 * string literal in this file is a bug the i18n gate in CI reports. Numbers are
 * the exception: they are data, and they are formatted by the renderer.
 */
public class SessionCommands(
    private val links: LinkBook,
    private val version: String,
) {
    /** The whole tree, as one declaration. */
    public fun tree(): CommandTree = CommandTree(topLevel = sessionCommands(), groups = radioAndLinkCommands())

    private fun sessionCommands(): List<CommandSpec> = listOf(
        CommandSpec(
            name = "help",
            summaryKey = "cmd.help",
            run = { context, _ -> help(context) },
        ),
        CommandSpec(
            name = "quit",
            summaryKey = "cmd.quit",
            run = { context, _ -> CommandResult(ExitStatus.OK, TextOutput(context.messages.t("session.bye"))) },
        ),
        CommandSpec(
            name = "exit",
            summaryKey = "cmd.quit",
            run = { context, _ -> CommandResult(ExitStatus.OK, TextOutput(context.messages.t("session.bye"))) },
        ),
        CommandSpec(
            name = "version",
            summaryKey = "cmd.version",
            run = { context, _ ->
                CommandResult.ok(TextOutput(context.messages.t("output.version", "version" to version)))
            },
        ),
        CommandSpec(
            name = "json",
            summaryKey = "cmd.json",
            arguments = listOf(ArgSpec("on|off")),
            affectsRendering = true,
            run = { context, args ->
                context.state.json = args.first().equals("on", ignoreCase = true)
                val key = if (context.state.json) "output.json_enabled" else "output.json_disabled"
                CommandResult.ok(TextOutput(context.messages.t(key)))
            },
        ),
        CommandSpec(
            name = "color",
            summaryKey = "cmd.color",
            arguments = listOf(ArgSpec("on|off")),
            affectsRendering = true,
            run = { context, args ->
                context.state.color = args.first().equals("on", ignoreCase = true)
                val key = if (context.state.color) "output.color_enabled" else "output.color_disabled"
                CommandResult.ok(TextOutput(context.messages.t(key)))
            },
        ),
        CommandSpec(
            name = "clear",
            summaryKey = "cmd.clear",
            run = { _, _ -> CommandResult.ok(ClearOutput) },
        ),
        CommandSpec(
            name = "doctor",
            summaryKey = "cmd.doctor",
            run = { context, _ -> doctor(context) },
        ),
    )

    private fun radioAndLinkCommands(): List<CommandGroup> = listOf(radioGroup(), linkGroup())

    private fun radioGroup() = CommandGroup(
        name = "radio",
        summaryKey = "cmd.radio",
        subcommands = listOf(
            CommandSpec(
                name = "connect",
                summaryKey = "cmd.radio.connect",
                arguments = listOf(ArgSpec("target"), ArgSpec("pin", required = false)),
                run = { context, args -> connect(context, args) },
            ),
            CommandSpec(
                name = "disconnect",
                summaryKey = "cmd.radio.disconnect",
                run = { context, _ -> disconnect(context) },
            ),
            CommandSpec(
                name = "info",
                summaryKey = "cmd.radio.info",
                run = { context, _ -> info(context) },
            ),
            CommandSpec(
                name = "status",
                summaryKey = "cmd.radio.status",
                run = { context, _ -> status(context) },
            ),
            CommandSpec(
                name = "auth",
                summaryKey = "cmd.radio.auth",
                arguments = listOf(ArgSpec("pin")),
                run = { context, args -> authenticate(context, args.first()) },
            ),
            CommandSpec(
                name = "tuning",
                summaryKey = "cmd.radio.tuning",
                run = { context, _ -> tuning(context) },
            ),
            CommandSpec(
                name = "retune",
                summaryKey = "cmd.radio.retune",
                arguments = listOf(
                    ArgSpec("frequency"),
                    ArgSpec("bandwidth", required = false),
                    ArgSpec("spreading-factor", required = false),
                    ArgSpec("coding-rate", required = false),
                    ArgSpec("power", required = false),
                ),
                run = { context, args -> retune(context, args) },
            ),
            CommandSpec(
                name = "settings",
                summaryKey = "cmd.radio.settings",
                run = { context, _ -> settings(context) },
            ),
            CommandSpec(
                name = "history",
                summaryKey = "cmd.radio.history",
                arguments = listOf(ArgSpec("limit", required = false), ArgSpec("since", required = false)),
                run = { context, args -> history(context, args) },
            ),
            CommandSpec(
                name = "purge",
                summaryKey = "cmd.radio.purge",
                run = { context, _ -> purge(context) },
            ),
            CommandSpec(
                name = "reboot",
                summaryKey = "cmd.radio.reboot",
                run = { context, _ -> power(context, reboot = true) },
            ),
            CommandSpec(
                name = "factory-reset",
                summaryKey = "cmd.radio.factory-reset",
                run = { context, _ -> power(context, reboot = false) },
            ),
        ),
        // `/radio` with nothing after it says what it can do rather than
        // "unknown command", because the group itself is not the mistake.
        run = { context, _ -> listCommands(context, tree(), "radio") },
    )

    private fun linkGroup() = CommandGroup(
        name = "link",
        summaryKey = "cmd.link",
        subcommands = listOf(
            CommandSpec(
                name = "list",
                summaryKey = "cmd.link.list",
                run = { context, _ -> listLinks(context) },
            ),
            CommandSpec(
                name = "add",
                summaryKey = "cmd.link.add",
                arguments = listOf(ArgSpec("name"), ArgSpec("target")),
                run = { context, args -> addLink(context, args[0], args[1]) },
            ),
            CommandSpec(
                name = "remove",
                summaryKey = "cmd.link.remove",
                arguments = listOf(ArgSpec("name")),
                run = { context, args -> removeLink(context, args.first()) },
            ),
            CommandSpec(
                name = "test",
                summaryKey = "cmd.link.test",
                arguments = listOf(ArgSpec("name")),
                run = { context, args -> testLink(context, args.first()) },
            ),
        ),
        run = { context, _ -> listLinks(context) },
    )

    // ── session ─────────────────────────────────────────────────────────────

    private suspend fun help(context: CommandContext): CommandResult =
        CommandResult.ok(helpOutput(context, tree().allCommands()))

    /** `/radio` on its own: the group's own commands, nothing else. */
    private suspend fun listCommands(context: CommandContext, tree: CommandTree, group: String): CommandResult =
        CommandResult.ok(
            helpOutput(
                context,
                tree.allCommands().filter { it.command.startsWith("/$group") },
            ),
        )

    private fun helpOutput(context: CommandContext, commands: List<HelpEntry>): HelpOutput = HelpOutput(
        commands = commands.map { entry ->
            HelpEntry(entry.command, context.messages.t(entry.summary), entry.arguments)
        },
        footer = context.messages.t("help.footer"),
    )

    private suspend fun doctor(context: CommandContext): CommandResult = CommandResult.ok(
        DoctorOutput(
            version = version,
            language = context.messages.language,
            profile = context.state.profile,
            home = context.state.home,
            json = context.state.json,
            color = context.state.color,
            links = links.all().map { LinkView(it.name, linkLabel(it.descriptor), it.descriptor.kindName()) },
            radio = context.radio.device.value?.let { deviceView(it) },
            phase = phaseText(context),
        ),
    )

    // ── radio ───────────────────────────────────────────────────────────────

    private suspend fun connect(context: CommandContext, args: List<String>): CommandResult {
        val (target, pin) = resolveTarget(context, args[0], args.getOrNull(1))
        val descriptor = LinkTarget.parse(target)
            ?: return usage(context, "/radio connect", "usage.unknown_argument", "target" to target)
        return try {
            val info = context.radio.connect(descriptor, pin)
            links.remember(descriptor)
            CommandResult.ok(
                TextOutput(
                    context.messages.t(
                        "radio.connected",
                        "name" to linkLabel(descriptor),
                        "board" to info.boardName,
                    ),
                ),
            )
        } catch (e: RadioException) {
            // Only this failure is worth the target a person typed; everything
            // else reads better as the radio's own words.
            val error = e.error
            val reason = if (error is RadioError.LinkUnavailable) {
                context.messages.t("radio.connect.failed", "target" to target, "reason" to error.detail)
            } else {
                describe(context, error)
            }
            failure(context, "/radio connect", reason)
        }
    }

    private suspend fun disconnect(context: CommandContext): CommandResult = reporting(context, "/radio disconnect") {
        context.radio.disconnect()
        CommandResult.ok(TextOutput(context.messages.t("radio.disconnected")))
    }

    private suspend fun info(context: CommandContext): CommandResult = reporting(context, "/radio info") {
        val info = context.radio.device.value
            ?: return@reporting failure(context, "/radio info", context.messages.t("radio.none"))
        CommandResult.ok(RadioInfoOutput(deviceView(info)))
    }

    private suspend fun status(context: CommandContext): CommandResult = reporting(context, "/radio status") {
        val status = context.radio.status()
        CommandResult.ok(
            RadioStatusOutput(
                wifiState = status.wifiState,
                wifiSsid = status.wifiSsid.ifEmpty { "—" },
                wifiIpv4 = status.wifiIpv4.ifEmpty { "—" },
                wifiPort = status.wifiPort,
                bleClients = status.bleClients,
                usbClients = status.usbClients,
                tcpClients = status.tcpClients,
            ),
        )
    }

    private suspend fun authenticate(context: CommandContext, pin: String): CommandResult =
        reporting(context, "/radio auth") {
            when (val result = context.radio.authenticate(pin)) {
                dev.meshpigeon.core.transport.AuthResult.Authorized ->
                    CommandResult.ok(TextOutput(context.messages.t("radio.auth.ok")))
                dev.meshpigeon.core.transport.AuthResult.NotRequired ->
                    CommandResult.ok(TextOutput(context.messages.t("radio.auth.not_required")))
                is dev.meshpigeon.core.transport.AuthResult.Rejected ->
                    failure(context, "/radio auth", context.messages.t("radio.auth.refused"))
            }
        }

    private suspend fun tuning(context: CommandContext): CommandResult = reporting(context, "/radio tuning") {
        val tuning = context.radio.tuning.value
            ?: return@reporting failure(context, "/radio tuning", context.messages.t("radio.none"))
        CommandResult.ok(TuningOutput(tuningView(tuning)))
    }

    private suspend fun retune(context: CommandContext, args: List<String>): CommandResult =
        reporting(context, "/radio retune") {
            val current = context.radio.tuning.value
                ?: return@reporting failure(context, "/radio retune", context.messages.t("radio.none"))
            val requested = Tuning(
                frequencyHz = args.getOrNull(0)?.toIntOrNull() ?: current.frequencyHz,
                bandwidthHz = args.getOrNull(1)?.toIntOrNull() ?: current.bandwidthHz,
                spreadingFactor = args.getOrNull(2)?.toIntOrNull() ?: current.spreadingFactor,
                codingRate = args.getOrNull(3)?.toIntOrNull() ?: current.codingRate,
                powerDbm = args.getOrNull(4)?.toIntOrNull() ?: current.powerDbm,
                configEpoch = current.configEpoch,
            )
            if (requested.sameTuningAs(current)) {
                return@reporting CommandResult.ok(
                    TextOutput(context.messages.t("radio.tuning.unchanged", "summary" to tuningSummary(context, current))),
                )
            }
            val applied = context.radio.retune(requested)
            CommandResult.ok(
                TextOutput(context.messages.t("radio.tuning.applied", "summary" to tuningSummary(context, applied))),
            )
        }

    private suspend fun settings(context: CommandContext): CommandResult = reporting(context, "/radio settings") {
        val settings = context.radio.settings()
        CommandResult.ok(
            DeviceSettingsOutput(
                name = settings.name,
                wifiEnabled = settings.wifiEnabled,
                wifiSsid = settings.wifiSsid,
                wifiPort = settings.wifiPort,
                // The passphrase is deliberately not rendered: it belongs in a
                // secret store, and `/radio settings` is a thing people paste.
                wifiPassword = if (settings.wifiPassword.isEmpty()) "—" else "••••",
            ),
        )
    }

    private suspend fun history(context: CommandContext, args: List<String>): CommandResult =
        reporting(context, "/radio history") {
            val limit = args.getOrNull(0)?.toIntOrNull() ?: 20
            val since = args.getOrNull(1)?.toIntOrNull() ?: 0
            val page = context.radio.history(since, limit)
            if (page.packets.isEmpty()) {
                CommandResult.ok(TextOutput(context.messages.t("radio.history.empty")))
            } else {
                CommandResult.ok(
                    HistoryOutput(
                        delivered = page.delivered,
                        packets = page.packets.map { packetView(it) },
                        summary = context.messages.t(
                            "radio.history",
                            "count" to page.packets.size,
                            "delivered" to page.delivered,
                        ),
                    ),
                )
            }
        }

    private suspend fun purge(context: CommandContext): CommandResult = reporting(context, "/radio purge") {
        context.radio.purge()
        CommandResult.ok(TextOutput(context.messages.t("radio.purged")))
    }

    private suspend fun power(context: CommandContext, reboot: Boolean): CommandResult =
        reporting(context, if (reboot) "/radio reboot" else "/radio factory-reset") {
            if (reboot) context.radio.reboot() else context.radio.factoryReset()
            val key = if (reboot) "radio.rebooting" else "radio.factory_reset"
            CommandResult.ok(TextOutput(context.messages.t(key)))
        }

    // ── links ───────────────────────────────────────────────────────────────

    private suspend fun addLink(context: CommandContext, name: String, target: String): CommandResult {
        val descriptor = LinkTarget.parse(target)
            ?: return usage(context, "/link add", "usage.unknown_argument", "target" to target)
        if (links.contains(name)) {
            return failure(context, "/link add", context.messages.t("link.duplicate", "name" to name))
        }
        links.add(name, descriptor)
        return CommandResult.ok(
            TextOutput(context.messages.t("link.added", "name" to name, "target" to linkLabel(descriptor))),
        )
    }

    private suspend fun removeLink(context: CommandContext, name: String): CommandResult {
        if (!links.remove(name)) {
            return failure(context, "/link remove", context.messages.t("link.unknown", "name" to name))
        }
        links.forgetPin(name)
        return CommandResult.ok(TextOutput(context.messages.t("link.removed", "name" to name)))
    }

    private suspend fun listLinks(context: CommandContext): CommandResult {
        val all = links.all()
        if (all.isEmpty()) return CommandResult.ok(TextOutput(context.messages.t("link.none")))
        return CommandResult.ok(
            LinkListOutput(all.map { LinkView(it.name, linkLabel(it.descriptor), it.descriptor.kindName()) }),
        )
    }

    private suspend fun testLink(context: CommandContext, name: String): CommandResult =
        reporting(context, "/link test") {
            val link = links.all().firstOrNull { it.name == name }
                ?: return@reporting failure(context, "/link test", context.messages.t("link.unknown", "name" to name))
            // A TCP link is tested by opening it: connect, read one status, close.
            context.radio.connect(link.descriptor, links.pinFor(name))
            val status = context.radio.status()
            context.radio.disconnect()
            CommandResult.ok(
                TextOutput(
                    context.messages.t(
                        "link.list.header",
                        "name" to link.name,
                        "kind" to link.descriptor.kindName(),
                        "target" to linkLabel(link.descriptor),
                    ) + " · ${status.wifiState}",
                ),
            )
        }

    // ── shared helpers ──────────────────────────────────────────────────────

    /**
     * Runs [block], turning a radio failure into a message from the catalog.
     *
     * Every command that touches a radio goes through here, which is why no
     * command has to know what a `RadioException` is.
     */
    private suspend fun reporting(
        context: CommandContext,
        command: String,
        block: suspend () -> CommandResult,
    ): CommandResult = try {
        block()
    } catch (e: RadioException) {
        failure(context, command, describe(context, e.error))
    } catch (e: IllegalStateException) {
        failure(context, command, context.messages.t("radio.none"))
    } catch (e: IllegalArgumentException) {
        failure(context, command, e.message ?: e::class.simpleName.orEmpty())
    }

    private fun describe(context: CommandContext, error: RadioError): String = when (error) {
        is RadioError.Remote -> error.message
        is RadioError.Timeout ->
            context.messages.t("error.timeout", "what" to error.what, "after" to "${error.afterMs}ms")
        else -> error.message
    }

    private fun failure(context: CommandContext, command: String, reason: String): CommandResult =
        CommandResult.failure(ErrorOutput(command, reason))

    private fun usage(
        context: CommandContext,
        command: String,
        key: String,
        vararg args: Pair<String, Any?>,
    ): CommandResult = CommandResult(ExitStatus.USAGE, ErrorOutput(command, context.messages.t(key, *args)))

    /** Resolves a link name to its target, so `/radio connect bench` works. */
    private fun resolveTarget(context: CommandContext, name: String, pin: String?): Pair<String, String?> =
        links.all().firstOrNull { it.name == name }?.let { linkLabel(it.descriptor) to (pin ?: links.pinFor(name)) }
            ?: (name to pin)

    private fun tuningSummary(context: CommandContext, tuning: Tuning): String = context.messages.t(
        "radio.tuning",
        "frequency" to "${tuning.frequencyHz} Hz",
        "bandwidth" to "${tuning.bandwidthHz} Hz",
        "spreadingFactor" to tuning.spreadingFactor,
        "codingRate" to tuning.codingRate,
        "power" to tuning.powerDbm,
        "epoch" to tuning.configEpoch,
    )
}

/** `/clear` asks the terminal layer to clear; the model stays data. */
public data object ClearOutput : CommandOutput

/** The one line a session shows when it starts. */
public fun bannerText(context: CommandContext, identity: String?): String = context.messages.t(
    "session.banner",
    "version" to context.state.version,
    "identity" to (identity ?: context.messages.t("session.banner.identity.none")),
    "radio" to (context.radio.device.value?.boardName ?: context.messages.t("session.banner.radio.none")),
)

/** How a link kind reads to a person. */
public fun LinkDescriptor.kindName(): String = when (this) {
    is LinkDescriptor.Tcp -> "tcp"
    is LinkDescriptor.Usb -> "usb"
    is LinkDescriptor.Ble -> "ble"
}

/** A configured link. */
public data class LinkView(val name: String, val target: String, val kind: String)

/** Everything `/radio info` shows about a device. */
public data class DeviceView(
    val board: String,
    val firmware: String,
    val spec: Int,
    val uptime: String,
    val boots: Int,
    val battery: String,
    val noiseFloor: String,
    val radioOk: Boolean,
    val packetsHeld: Int,
    val storeCapacityBytes: Int,
    val storeDropped: Int,
    val capabilities: List<String>,
)

/** Everything `/radio tuning` shows. */
public data class TuningView(
    val frequencyHz: Int,
    val bandwidthHz: Int,
    val spreadingFactor: Int,
    val codingRate: Int,
    val powerDbm: Int,
    val epoch: Int,
)

/** One packet, as `/radio history` shows it. */
public data class PacketView(
    val seq: Int,
    val at: Long,
    val origin: String,
    val bytes: Int,
    val hex: String,
    val rssi: Int?,
    val snr: Double?,
)

/** `/doctor`'s model: everything a bug report needs, in one value. */
public data class DoctorOutput(
    val version: String,
    val language: String,
    val profile: String,
    val home: String,
    val json: Boolean,
    val color: Boolean,
    val links: List<LinkView>,
    val radio: DeviceView?,
    val phase: String,
) : CommandOutput

/** `/radio info`'s model. */
public data class RadioInfoOutput(val device: DeviceView) : CommandOutput

/** `/radio status`'s model. */
public data class RadioStatusOutput(
    val wifiState: String,
    val wifiSsid: String,
    val wifiIpv4: String,
    val wifiPort: Int,
    val bleClients: Int,
    val usbClients: Int,
    val tcpClients: Int,
) : CommandOutput

/** `/radio tuning`'s model. */
public data class TuningOutput(val tuning: TuningView) : CommandOutput

/** `/radio settings`'s model, with the passphrase withheld. */
public data class DeviceSettingsOutput(
    val name: String,
    val wifiEnabled: Boolean,
    val wifiSsid: String,
    val wifiPort: Int,
    val wifiPassword: String,
) : CommandOutput

/** `/radio history`'s model. */
public data class HistoryOutput(
    val delivered: Int,
    val summary: String,
    val packets: List<PacketView>,
) : CommandOutput

/** `/link list`'s model. */
public data class LinkListOutput(val links: List<LinkView>) : CommandOutput

private fun deviceView(info: dev.meshpigeon.core.transport.DeviceInfo): DeviceView = DeviceView(
    board = info.boardName,
    firmware = info.firmwareVersion,
    spec = info.specVersion,
    uptime = formatDuration(info.uptimeMs),
    boots = info.bootCount,
    battery = info.batteryMilliVolts?.let { "$it mV" } ?: "—",
    noiseFloor = info.noiseFloorDbm?.let { "$it dBm" } ?: "—",
    radioOk = info.radioOk,
    packetsHeld = info.store.count,
    storeCapacityBytes = info.store.capacityBytes,
    storeDropped = info.store.dropped,
    capabilities = info.capabilities.map { it.label() },
)

private fun tuningView(tuning: Tuning): TuningView = TuningView(
    frequencyHz = tuning.frequencyHz,
    bandwidthHz = tuning.bandwidthHz,
    spreadingFactor = tuning.spreadingFactor,
    codingRate = tuning.codingRate,
    powerDbm = tuning.powerDbm,
    epoch = tuning.configEpoch,
)

private fun packetView(packet: StoredPacket): PacketView = PacketView(
    seq = packet.seq,
    at = packet.observedAtMs,
    origin = if (packet.origin == PacketOrigin.SENT) "sent" else "received",
    bytes = packet.raw.size,
    hex = Hex.encode(packet.raw),
    rssi = packet.rssiDbm,
    snr = packet.snr,
)

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

/** The connection lifecycle in the words of the catalog, not the enum's. */
private fun phaseText(context: CommandContext): String {
    val messages = context.messages
    return when (val phase = context.radio.phase.value) {
        is LinkPhase.Disconnected -> messages.t("radio.phase.disconnected")
        is LinkPhase.Connecting -> messages.t("radio.phase.connecting")
        is LinkPhase.Handshaking -> messages.t("radio.phase.handshaking")
        is LinkPhase.Ready -> messages.t("radio.phase.ready")
        is LinkPhase.Reconnecting -> messages.t(
            "radio.phase.reconnecting",
            "attempt" to phase.attempt,
            "delay" to "${phase.delayMs / 1000}s",
        )
        is LinkPhase.Failed -> messages.t("radio.phase.failed", "reason" to phase.error.message)
    }
}

/** Turns a `PacketEvent` into one line, or `null` when there is nothing to say. */
public fun describeEvent(messages: dev.meshpigeon.cli.i18n.Messages, event: PacketEvent): String? = when (event) {
    is PacketEvent.PacketReceived -> messages.tp(
        "radio.observed",
        packetView(event.packet).hex,
        event.packet.origin.name.lowercase(),
        event.packet.rssiDbm ?: "—",
        event.packet.snr ?: "—",
    )
    is PacketEvent.RadioRetuned -> messages.tp("radio.event.retuned", event.tuning.frequencyHz.toString())
    is PacketEvent.DeviceSettingsChanged -> messages.t("radio.event.settings")
    is PacketEvent.Reported -> messages.t("radio.event.reported", "reason" to event.error.message)
    else -> null
}

/** Wi-Fi state as a person reads it; the enum is core's vocabulary, not the CLI's. */
public fun WifiState.label(): String = name.lowercase()