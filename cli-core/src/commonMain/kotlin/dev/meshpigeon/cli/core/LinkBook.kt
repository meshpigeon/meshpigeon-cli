package dev.meshpigeon.cli.core

import dev.meshpigeon.core.transport.LinkDescriptor

/** One configured link, as `/link list` shows it. */
public data class LinkEntry(
    val name: String,
    val descriptor: LinkDescriptor,
)

/**
 * The links a session knows about.
 *
 * In memory for now: links become database rows with the storage layer (P2),
 * where the PIN is sealed beside them. Nothing else changes then — this is the
 * whole interface the commands use.
 */
public class LinkBook {
    private val links = mutableListOf<LinkEntry>()
    private val pins = mutableMapOf<String, String>()

    /** Every configured link, in the order they were added. */
    public fun all(): List<LinkEntry> = links.toList()

    /** Whether a link by this name exists. */
    public fun contains(name: String): Boolean = links.any { it.name == name }

    /** Adds a link; the caller has already checked the name is free. */
    public fun add(name: String, descriptor: LinkDescriptor) {
        links += LinkEntry(name, descriptor)
    }

    /** Removes a link, or returns false when there was none. */
    public fun remove(name: String): Boolean = links.removeAll { it.name == name }

    /** Forgets a link's PIN. */
    public fun forgetPin(name: String) {
        pins.remove(name)
    }

    /** The PIN remembered for a link, if any. */
    public fun pinFor(name: String): String? = pins[name]

    /** Remembers a PIN for a link. */
    public fun rememberPin(name: String, pin: String) {
        pins[name] = pin
    }

    /** Adds a link discovered from a device, keeping an existing name. */
    public fun remember(descriptor: LinkDescriptor) {
        val existing = links.firstOrNull { it.descriptor == descriptor }
        if (existing == null) links += LinkEntry(linkLabel(descriptor), descriptor)
    }
}

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
}

/** How a link reads to a person. */
public fun linkLabel(descriptor: LinkDescriptor): String = when (descriptor) {
    is LinkDescriptor.Tcp -> "${descriptor.host}:${descriptor.port}"
    is LinkDescriptor.Usb -> descriptor.devicePath
    is LinkDescriptor.Ble -> descriptor.name ?: descriptor.endpointId
}