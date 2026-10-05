package dev.meshpigeon.cli.core

import dev.meshpigeon.core.common.Clock
import dev.meshpigeon.core.common.Ids
import dev.meshpigeon.core.transport.LinkDescriptor
import dev.meshpigeon.domain.LinkKind
import dev.meshpigeon.domain.LinkTarget as DomainLinkTarget
import dev.meshpigeon.domain.RadioRecord
import dev.meshpigeon.domain.RadioRepository
import dev.meshpigeon.domain.SavedLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One configured link, as `/link list` shows it. */
public data class LinkEntry(
    val name: String,
    val descriptor: LinkDescriptor,
    val preferred: Boolean = false,
    val lastConnectedAtMs: Long? = null,
    val lastError: String? = null,
)

/** What `/link add` decided to do. */
public enum class AddOutcome {
    /** A new radio, with its first link. */
    Created,

    /** Another way to reach a radio that is already saved. */
    Linked,

    /** That exact target was already saved under this name. */
    AlreadyThere,
}

/**
 * The links a session knows about, over the database.
 *
 * A *name* belongs to a radio, and a radio may have several links — that is what
 * the schema says (`radio_device` one row, `radio_link` many), so
 * `/link add desk usb:/dev/ttyACM0` after `/link add desk tcp://10.0.0.5:5000`
 * adds a second way to reach one desk rather than a second desk. The plan's
 * schema was landed verbatim (D55), so the model follows it instead of inventing
 * a name column the design did not have.
 *
 * A radio is recognised by its **target**: there is no device-reported identity
 * yet, so `persistentId` is the canonical target text — a USB path, a host:port,
 * a BLE endpoint. Two consequences, both deliberate:
 *
 * * `/radio connect` to an unsaved target records what the device said, and
 *   nothing else. A link is a person's decision, and a direct connect is not one;
 * *   `/link add` afterwards finds that radio by its target, gives it the name
 *   the person chose, and saves the link.
 * * Two transports for one physical radio are two rows until the identity phase
 *   (P3) has something to merge them with. That is a limitation, not a design.
 *
 * The PIN is not stored either: `pin_sealed` waits for P3's cipher (D53), so a
 * PIN remembered here lives for this session and no longer. A PIN written in the
 * clear to a database that is meant to travel safely is worse than no PIN.
 */
public class LinkBook(private val radios: RadioRepository) {
    // One writer at a time, and it is a session: two commands in one session
    // must not interleave a read-modify-write of the same rows.
    private val mutex = Mutex()
    private val pins = mutableMapOf<String, String>()

    /** Every configured link, in the order the radios were added. */
    public suspend fun all(): List<LinkEntry> = mutex.withLock {
        buildList {
            for (radio in radios.radios()) {
                for (link in radios.links(radio.id)) {
                    add(
                        LinkEntry(
                            name = radio.name,
                            descriptor = link.target.toDescriptor(),
                            preferred = link.isPreferred,
                            lastConnectedAtMs = link.lastConnectedAtMs,
                            lastError = link.lastError,
                        ),
                    )
                }
            }
        }
    }

    /** Whether a link by this name exists. */
    public suspend fun contains(name: String): Boolean = mutex.withLock {
        radios.radios().any { it.name == name }
    }

    /** The link a name refers to: the preferred one, or the first. */
    public suspend fun find(name: String): LinkEntry? = mutex.withLock { lookup(name) }

    /**
     * Saves [descriptor] under [name], creating the radio when it is new.
     *
     * What it did, rather than a boolean: "added" and "that is already how you
     * reach it" are different answers, and a person who typed the same line twice
     * deserves to be told which one happened.
     */
    public suspend fun add(name: String, descriptor: LinkDescriptor): AddOutcome = mutex.withLock {
        val target = descriptor.toDomain()
        // By target first, then by name: a radio already remembered from a direct
        // connect is the same radio, and looking it up by name instead would
        // create a second row for one device — which `persistent_id` is unique on,
        // and which `INSERT OR REPLACE` would answer by deleting the first.
        val byTarget = radios.radios().firstOrNull { it.persistentId == target.canonical }
        if (byTarget != null) {
            val existing = radios.links(byTarget.id).firstOrNull { it.target.canonical == target.canonical }
            if (byTarget.name != name) rename(byTarget, name)
            return@withLock if (existing != null) {
                AddOutcome.AlreadyThere
            } else {
                radios.addLink(SavedLink(id = Ids.newId(), radioId = byTarget.id, target = target))
                AddOutcome.Linked
            }
        }

        val byName = radios.radios().firstOrNull { it.name == name }
        if (byName != null) {
            radios.addLink(SavedLink(id = Ids.newId(), radioId = byName.id, target = target))
            return@withLock AddOutcome.Linked
        }

        val created = radios.record(
            RadioRecord(
                id = Ids.newId(),
                persistentId = target.canonical,
                name = name,
                // Nothing is known about the radio yet: it has not been reached,
                // so board, firmware and spec are what `/radio connect` fills in
                // when it next answers.
                boardName = null,
                firmwareVersion = null,
                specVersion = null,
                firstSeenAtMs = nowMs(),
            ),
        )
        radios.addLink(SavedLink(id = Ids.newId(), radioId = created.id, target = target, isPreferred = true))
        AddOutcome.Created
    }

    /**
     * Forgets a link by name.
     *
     * The whole radio goes, links included: a name is a radio's name, so
     * `/link remove desk` means "forget this desk", and `ON DELETE CASCADE`
     * carries its links away rather than leaving a radio no link can reach.
     */
    public suspend fun remove(name: String): Boolean = mutex.withLock {
        val radio = radios.radios().firstOrNull { it.name == name } ?: return@withLock false
        radios.forgetRadio(radio.id)
        pins.remove(name)
        true
    }

    /**
     * Notes that a link was used, or that it failed, for the next `/link list`.
     *
     * A failure clears the timestamp: "when it last worked" and "when it was last
     * tried" are different questions and one column answers one of them.
     */
    public suspend fun noteOutcome(name: String, connected: Boolean, error: String? = null) = mutex.withLock {
        val link = linkFor(name) ?: return@withLock
        radios.noteLinkOutcome(link.id, connected = connected, atMs = nowMs(), error = error)
    }

    /**
     * Records what the radio said about itself, against the target that reached
     * it.
     *
     * Called on every successful connect, saved or not: the facts about a radio
     * are worth having whether or not a person decided to keep the link, and
     * `persistentId` is the target, so this is the same row either way.
     */
    public suspend fun recordConnection(descriptor: LinkDescriptor, facts: RadioFacts) = mutex.withLock {
        val target = descriptor.toDomain()
        val existing = radios.radios().firstOrNull { it.persistentId == target.canonical }
        radios.record(
            RadioRecord(
                id = existing?.id ?: Ids.newId(),
                persistentId = target.canonical,
                // A radio a person has not named is named after its own address,
                // which is at least true and can be renamed by `/link add`.
                name = existing?.name ?: linkLabel(descriptor),
                boardName = facts.boardName,
                firmwareVersion = facts.firmwareVersion,
                specVersion = facts.specVersion,
                supportsWifi = facts.supportsWifi,
                supportsBle = facts.supportsBle,
                supportsUsb = facts.supportsUsb,
                hasBattery = facts.batteryMilliVolts != null,
                firstSeenAtMs = existing?.firstSeenAtMs ?: nowMs(),
                lastSeenAtMs = nowMs(),
                lastBatteryMv = facts.batteryMilliVolts,
                lastNoiseFloorDbm = facts.noiseFloorDbm,
            ),
        )
        // A saved link that was just used is a link that worked; a target with
        // no saved link has nothing to note.
        val link = radios.radios()
            .firstOrNull { it.persistentId == target.canonical }
            ?.let { radios.links(it.id).firstOrNull { saved -> saved.target.canonical == target.canonical } }
        if (link != null) radios.noteLinkOutcome(link.id, connected = true, atMs = nowMs())
    }

    /** Forgets a link's PIN. */
    public fun forgetPin(name: String) {
        pins.remove(name)
    }

    /**
     * The PIN for a link, if this session has one.
     *
     * Session-only until P3: nothing here reaches the disk, so a PIN is not
     * something `/link list` can leak or `/db` can count.
     */
    public fun pinFor(name: String): String? = pins[name]

    /** Remembers a PIN for this session. */
    public fun rememberPin(name: String, pin: String) {
        pins[name] = pin
    }

    /** A radio renamed, with its `firstSeenAt` and `persistentId` left alone. */
    private suspend fun rename(radio: RadioRecord, name: String) {
        radios.record(radio.copy(name = name))
    }

    private suspend fun lookup(name: String): LinkEntry? {
        val radio = radios.radios().firstOrNull { it.name == name } ?: return null
        val link = radios.links(radio.id).firstOrNull { it.isPreferred }
            ?: radios.links(radio.id).firstOrNull()
            ?: return null
        return LinkEntry(
            name = radio.name,
            descriptor = link.target.toDescriptor(),
            preferred = link.isPreferred,
            lastConnectedAtMs = link.lastConnectedAtMs,
            lastError = link.lastError,
        )
    }

    private suspend fun linkFor(name: String): SavedLink? {
        val radio = radios.radios().firstOrNull { it.name == name } ?: return null
        val links = radios.links(radio.id)
        return links.firstOrNull { it.isPreferred } ?: links.firstOrNull()
    }
}

/** What a successful connect learned about the device, for the radio's row. */
public data class RadioFacts(
    val boardName: String,
    val firmwareVersion: String,
    val specVersion: Int,
    val supportsWifi: Boolean,
    val supportsBle: Boolean,
    val supportsUsb: Boolean,
    val batteryMilliVolts: Int?,
    val noiseFloorDbm: Int?,
)

/**
 * Parses a link target as a person types it: `tcp://10.0.0.5:5000`,
 * `10.0.0.5:5000`, `usb:/dev/ttyACM0`.
 *
 * A transport that is not compiled into this build is refused here, with the
 * reason, rather than at the point where bytes fail to move.
 */
public object LinkTarget {
    public fun parse(text: String): LinkDescriptor? {
        val trimmed = text.trim()
        if (trimmed.startsWith("tcp://")) {
            val rest = trimmed.removePrefix("tcp://")
            val host = rest.substringBefore(':')
            val port = rest.substringAfter(':', "").toIntOrNull() ?: return null
            if (host.isBlank() || port !in 1..65_535) return null
            return LinkDescriptor.Tcp(host, port)
        }
        if (trimmed.startsWith("usb:")) {
            val path = trimmed.removePrefix("usb:").trim()
            return if (path.isBlank()) null else LinkDescriptor.Usb(path)
        }
        // Bare `host:port` is TCP, because that is what a radio on a network is.
        val separator = trimmed.lastIndexOf(':')
        if (separator <= 0) return null
        val host = trimmed.substring(0, separator)
        val port = trimmed.substring(separator + 1).toIntOrNull() ?: return null
        if (port !in 1..65_535) return null
        return LinkDescriptor.Tcp(host, port)
    }

    /**
     * The canonical text for [descriptor] — the one [parse] reads back.
     *
     * This is deliberately not [linkLabel]. A label is for a person (`10.0.0.5`
     * is friendlier than `tcp://10.0.0.5:5000`), and a device path has no scheme
     * at all, so a label that is handed back to [parse] is a bare path that
     * parses as nothing. The scheme is what makes each kind round-trip — and it
     * is also what the database stores as a radio's `persistentId`.
     */
    public fun format(descriptor: LinkDescriptor): String = when (descriptor) {
        is LinkDescriptor.Tcp -> "tcp://${descriptor.host}:${descriptor.port}"
        is LinkDescriptor.Usb -> "usb:${descriptor.devicePath}"
        is LinkDescriptor.Ble -> "ble:${descriptor.endpointId}"
    }
}

/**
 * How a link reads to a person.
 *
 * This is display: `10.0.0.5:5000`, `/dev/ttyACM0`. For something that has to be
 * read *back* — a saved name, a script, another command — use
 * [LinkTarget.format], which round-trips through [LinkTarget.parse].
 */
public fun linkLabel(descriptor: LinkDescriptor): String = when (descriptor) {
    is LinkDescriptor.Tcp -> "${descriptor.host}:${descriptor.port}"
    is LinkDescriptor.Usb -> descriptor.devicePath
    is LinkDescriptor.Ble -> descriptor.name ?: descriptor.endpointId
}

/** The descriptor the transports speak, as the domain stores it. */
public fun LinkDescriptor.toDomain(): DomainLinkTarget = when (this) {
    is LinkDescriptor.Tcp -> DomainLinkTarget(kind = LinkKind.Tcp, host = host, port = port)
    is LinkDescriptor.Usb -> DomainLinkTarget(kind = LinkKind.Usb, devicePath = devicePath)
    is LinkDescriptor.Ble -> DomainLinkTarget(kind = LinkKind.Ble, endpointId = endpointId, name = name)
}

/** The transport's descriptor, from what the domain stored. */
public fun DomainLinkTarget.toDescriptor(): LinkDescriptor = when (kind) {
    LinkKind.Tcp -> LinkDescriptor.Tcp(host = host.orEmpty(), port = port ?: 0)
    LinkKind.Usb -> LinkDescriptor.Usb(devicePath = devicePath.orEmpty())
    LinkKind.Ble -> LinkDescriptor.Ble(endpointId = endpointId.orEmpty(), name = name)
}

/** Wall-clock milliseconds, the unit every timestamp in the schema uses. */
private fun nowMs(): Long = Clock.System.nowMs()
