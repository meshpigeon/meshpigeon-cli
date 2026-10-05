package dev.meshpigeon.cli.core

import dev.meshpigeon.core.transport.AuthResult
import dev.meshpigeon.core.transport.Capability
import dev.meshpigeon.core.transport.DeviceInfo
import dev.meshpigeon.core.transport.DeviceSettings
import dev.meshpigeon.core.transport.HistoryPage
import dev.meshpigeon.core.transport.LinkCandidate
import dev.meshpigeon.core.transport.LinkDescriptor
import dev.meshpigeon.core.transport.LinkPhase
import dev.meshpigeon.core.transport.LinkState
import dev.meshpigeon.core.transport.PacketEvent
import dev.meshpigeon.core.transport.RadioLink
import dev.meshpigeon.core.transport.RadioSession
import dev.meshpigeon.core.transport.Secret
import dev.meshpigeon.core.transport.SessionConfig
import dev.meshpigeon.core.transport.StoredPacket
import dev.meshpigeon.core.transport.Tuning
import dev.meshpigeon.core.link.tcp.TcpRadioLink
import dev.meshpigeon.core.link.usb.UsbRadioLink
import dev.meshpigeon.cli.i18n.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The radio, as the session's commands see it.
 *
 * The commands depend on this, not on [RadioSession], for two reasons: a test
 * can drive the whole session vocabulary without a radio, and the session never
 * reaches past it for anything a client should not be choosing (which radio to
 * open, how long to wait).
 */
public interface RadioController {
    /** The connection lifecycle, as one value. */
    public val phase: StateFlow<LinkPhase>

    /** What the radio says about itself, or `null` before the first handshake. */
    public val device: StateFlow<DeviceInfo?>

    /** The tuning the radio last reported. */
    public val tuning: StateFlow<Tuning?>

    /** Opens a link and brings the radio up. */
    public suspend fun connect(descriptor: LinkDescriptor, pin: String?): DeviceInfo

    /** Closes the link. */
    public suspend fun disconnect()

    public suspend fun status(): LinkStatusView

    public suspend fun settings(): DeviceSettings

    public suspend fun retune(tuning: Tuning): Tuning

    /** Stored packets after [since]. */
    public suspend fun history(since: Int, limit: Int): HistoryPage

    public suspend fun purge()

    public suspend fun reboot()

    public suspend fun factoryReset()

    /** Re-authenticates after someone changed the PIN on the device. */
    public suspend fun authenticate(pin: String): AuthResult

    /** Endspoints this machine can reach. */
    public suspend fun discover(): List<LinkCandidate>

    /** Packets, pushes and out-of-band changes. */
    public fun events(): Flow<PacketEvent>
}

/** Connection status, in the fields a person reads. */
public data class LinkStatusView(
    val wifiState: String,
    val wifiSsid: String,
    val wifiIpv4: String,
    val wifiPort: Int,
    val bleClients: Int,
    val usbClients: Int,
    val tcpClients: Int,
)

/**
 * The real radio, over a TCP link.
 *
 * This is the only place in `mp` that decides *which* transport to open, and it
 * only offers what the device reported in its capabilities — a board without
 * Wi-Fi never appears as a TCP target, because the firmware is what says so.
 */
public class CoreRadioController(
    private val sessionConfig: SessionConfig = SessionConfig(),
    private val parentScope: CoroutineScope,
    /**
     * The catalog this session renders with.
     *
     * It is here because refusing a transport the build does not carry is a
     * sentence a person reads ("no BLE link in this build yet"), and a client
     * holds no user-facing strings — so the sentence is asked for, not written.
     */
    private val messages: Messages,
    /**
     * How a target becomes a link.
     *
     * A seam, not a plugin point: production passes [linkFor] and gets exactly
     * the transports this build has, and a test passes a fake radio so the whole
     * command path — parse, session, frames, render — runs headlessly.
     */
    private val linkFactory: (LinkDescriptor) -> RadioLink = { descriptor -> linkFor(descriptor, messages) },
) : RadioController {
    private var session: RadioSession? = null

    private val disconnected = MutableStateFlow<LinkPhase>(LinkPhase.Disconnected)
    private val noDevice = MutableStateFlow<DeviceInfo?>(null)
    private val noTuning = MutableStateFlow<Tuning?>(null)

    override val phase: StateFlow<LinkPhase>
        get() = session?.phase ?: disconnected

    override val device: StateFlow<DeviceInfo?>
        get() = session?.device ?: noDevice

    override val tuning: StateFlow<Tuning?>
        get() = session?.tuning ?: noTuning

    override suspend fun connect(descriptor: LinkDescriptor, pin: String?): DeviceInfo {
        val link = try {
            linkFactory(descriptor)
        } catch (e: Throwable) {
            // Opening a link is where "nothing is listening" or "this build has
            // no such transport" shows up; it is a link error, not a radio one.
            throw dev.meshpigeon.core.transport.RadioException(
                dev.meshpigeon.core.transport.RadioError.LinkUnavailable(
                    "${linkLabel(descriptor)}: ${e.message ?: e::class.simpleName}",
                    e,
                ),
            )
        }
        val opened = RadioSession(link, sessionConfig, parentScope = parentScope)
        try {
            val info = opened.connect(pin?.let { Secret(it) })
            session = opened
            return info
        } catch (e: Throwable) {
            // A session that never came up is not a session: later commands say
            // "no radio is connected" rather than failing against a dead link.
            runCatching { opened.disconnect() }
            throw e
        }
    }

    override suspend fun disconnect() {
        session?.disconnect()
        session = null
    }

    override suspend fun status(): LinkStatusView = requireSession().status().let {
        LinkStatusView(
            wifiState = it.wifiState.name.lowercase(),
            wifiSsid = it.wifiSsid,
            wifiIpv4 = it.wifiIpv4?.joinToString(".") { byte -> (byte.toInt() and 0xFF).toString() }.orEmpty(),
            wifiPort = it.wifiPort,
            bleClients = it.bleClients,
            usbClients = it.usbCdcClients,
            tcpClients = it.tcpClients,
        )
    }

    override suspend fun settings(): DeviceSettings = requireSession().deviceSettings()

    override suspend fun retune(tuning: Tuning): Tuning = requireSession().writeTuning(tuning)

    override suspend fun history(since: Int, limit: Int): HistoryPage =
        requireSession().fetchHistory(dev.meshpigeon.core.transport.Cursor(since), limit)

    override suspend fun purge() {
        requireSession().purgeHistory()
    }

    override suspend fun reboot() {
        requireSession().reboot()
    }

    override suspend fun factoryReset() {
        requireSession().factoryReset()
    }

    override suspend fun authenticate(pin: String): AuthResult =
        requireSession().authenticate(Secret(pin))

    override suspend fun discover(): List<LinkCandidate> = emptyList()

    override fun events(): Flow<PacketEvent> = session?.incoming() ?: emptyFlow()

    private fun requireSession(): RadioSession =
        session ?: throw IllegalStateException("no radio is connected")
}

/** What a person reads for a capability; the CLI never sees a chip name. */
public fun Capability.label(): String = when (this) {
    Capability.WIFI -> "Wi-Fi"
    Capability.BATTERY -> "battery"
    Capability.BLE -> "BLE"
    Capability.USB_CDC -> "USB-CDC"
}

/** Whether a link is currently usable, as far as the link itself can tell. */
public val LinkState.isOpen: Boolean get() = this is LinkState.Open

/** The bytes of a packet as a person reads them: hex. */
public fun StoredPacket.hex(): String =
    dev.meshpigeon.core.common.Hex.encode(raw)

/**
 * The database, as a session's commands see it.
 *
 * A command reports on the file; it never opens it, never names its driver and
 * never writes SQL. Opening, locking and querying are the app's and
 * `core-storage`'s jobs. What crosses into the session is a plain [DbReport],
 * so the commands, the renderer and the tests never learn there is SQLite
 * underneath — which is the same line `DeviceView` draws around a `DeviceInfo`.
 */
public interface DatabaseStatus {
    /** What the file looks like right now. */
    public fun report(): DbReport
}

/** The messages and flags a session's commands render with. */
public class CommandContext(
    public val messages: Messages,
    public val radio: RadioController,
    public val state: SessionState,
    public val db: DatabaseStatus,
)

/**
 * What the session itself remembers between commands.
 *
 * Everything here is session-scoped by design: identity, chats and the database
 * arrive with the service layer, and until then the session holds only what a
 * person changed with a command.
 */
public class SessionState(
    public val version: String,
    /** Where this session keeps its files (`MP_HOME`, or the XDG data directory). */
    public val home: String,
    public val profile: String,
) {
    /** Whether `/json` is on. */
    public var json: Boolean = false

    /** Whether colour is on. Piped output turns it off unless asked. */
    public var color: Boolean = true

    /** The exit status of the last command that failed, for a piped session. */
    public var lastStatus: dev.meshpigeon.cli.core.ExitStatus = dev.meshpigeon.cli.core.ExitStatus.OK

    /** The profile name, as `/profile work` sets it. */
    public fun withProfile(name: String): SessionState = SessionState(version, home, name)
}

/**
 * The links this build can open.
 *
 * TCP over a network and USB CDC over a serial port, in the plan's order. BLE
 * has no link module yet, so a BLE target is refused with the reason rather
 * than attempted: a link that cannot open should say why, not time out.
 *
 * [messages] is here because the refusal is a sentence a person reads, and a
 * client holds no user-facing strings — [LinkBook.kindName] is what reports
 * which kind a descriptor is, so the message says "BLE" rather than "a link".
 */
public fun linkFor(
    descriptor: LinkDescriptor,
    messages: dev.meshpigeon.cli.i18n.Messages,
): RadioLink = when (descriptor) {
    is LinkDescriptor.Tcp -> TcpRadioLink(descriptor)
    is LinkDescriptor.Usb -> UsbRadioLink(descriptor)
    is LinkDescriptor.Ble -> throw IllegalArgumentException(
        messages.t("link.transport_missing", "transport" to descriptor.kindName().uppercase()),
    )
}
