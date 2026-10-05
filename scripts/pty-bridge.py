#!/usr/bin/env python3
"""A serial device in front of a TCP endpoint.

`meshpigeon-sim` speaks TCP. `socat` would put a pty in between, but a pty is
the only serial device a build machine has, and depending on `socat` to test the
USB link is a dependency for no reason: this is the same twenty lines.

    pty-bridge.py --port 8801        # prints /dev/pts/N on stdout

Then a client opens that path as a serial port — which is exactly what `mp`
does with `usb:/dev/pts/N` — and the bytes arrive at the simulator unchanged.
The pty is opened in raw mode on this side too, because the line discipline
belongs to the pair and would otherwise mangle binary frames.

Usage:
    pty-bridge.py [--port N] [--host H] [--path FILE]

Prints the device path first, then forwards until killed.
"""

import argparse
import os
import pty
import select
import socket
import sys
import termios
import tty


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8801, help="the TCP port to bridge to")
    parser.add_argument("--host", default="127.0.0.1", help="the host to bridge to")
    parser.add_argument("--path", help="write the device path here instead of stdout")
    args = parser.parse_args()

    master, slave = pty.openpty()
    device = os.ttyname(slave)

    # Raw on the slave side: no line buffering, no echo, no CR/LF translation.
    # A frame is binary and often has neither a newline nor printable bytes.
    tty.setraw(slave)

    if args.path:
        with open(args.path, "w", encoding="utf-8") as handle:
            handle.write(device)
    else:
        print(device, flush=True)

    try:
        upstream = socket.create_connection((args.host, args.port), timeout=5)
    except OSError as error:
        print(f"pty-bridge: {args.host}:{args.port} refused the connection: {error}", file=sys.stderr)
        return 1

    # Keep the slave open for the life of the bridge: a client that closes the
    # port would otherwise see end of stream on its own reads, and an unplug is
    # the only thing that should mean that.
    sources = [master, upstream.fileno()]
    try:
        while True:
            readable, _, _ = select.select(sources, [], [], 1.0)
            for source in readable:
                data = os.read(source, 4096)
                if not data:
                    return 0
                if source == master:
                    upstream.sendall(data)
                else:
                    os.write(master, data)
    except KeyboardInterrupt:
        return 0
    except OSError as error:
        print(f"pty-bridge: {error}", file=sys.stderr)
        return 1
    finally:
        upstream.close()
        os.close(master)
        os.close(slave)


if __name__ == "__main__":
    sys.exit(main())