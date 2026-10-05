# meshpigeon-cli

`mp` is the MeshPigeon session. It is a first-class client, not a test harness:
anything hard in a GUI has to be reachable from here, because this is how it gets
found and fixed.

```
$ mp

  MeshPigeon 0.1.0 · no identity · no radio

  mp ▸ /link add bench tcp://10.0.0.5:5000
  mp ▸ /radio connect bench
  connected to 10.0.0.5:5000 (XIAO WIO)
  mp ▸ /radio info
  board         XIAO WIO
  firmware      0.2.0
  ...
```

## The session *is* the interface

`mp` has no application options. `--help` and `--version` are the only words the
binary itself understands; identity, radios, links, output mode and everything
else happen inside, as slash commands. A script is a command file:

```sh
printf '/radio connect 127.0.0.1:8801\n/radio info\n/quit\n' | mp
mp < scripts/acceptance/first-message.mp
MP_PROFILE=ci mp < scripts/acceptance/dm.mp     # an isolated profile
```

A piped session is the same loop with a file for stdin and no line editor, so a
test and a person type the same things — and the exit status is the last non-zero
command status, so CI can assert on it. `/json on` switches the *output mode*
inside the session; there is no `--json` flag with its own implementation.

## Commands today

```
/help  /quit  /exit  /version  /json  /color  /clear  /doctor

/radio   connect · disconnect · info · status · auth · tuning · retune
         settings · history · purge · reboot · factory-reset
/link    list · add · remove · test
```

The rest of the vocabulary (identity, chats, contacts, channels, packets, sync)
arrives with the service layer; `/help` is generated from the same declaration
that parses and completes, so the two cannot drift.

## Building

```sh
./gradlew build                  # every target, every test
./gradlew :cli-app:linkMpDebugExecutableLinuxX64
./cli-app/build/bin/linuxX64/mpDebugExecutable/mp.kexe
```

Kotlin 2.2.21 · Clikt 5.0.3 · Mordant 3.0.2 · Linux, macOS and the JVM today.
`meshpigeon-core` is consumed as a composite build until it is published
(P8), then as a plain version.

## Two gates, both in `check`

* **`scripts/check-imports.sh`** — the client may use only `meshpigeon-core`'s
  public API: no `java.`, no SQLDelight, no generated protobuf type, no core
  `internal` package, and no chip or vendor identifier. An `sx1262` in the CLI
  would mean hardware knowledge had leaked out from under the firmware's own
  abstraction.
* **`scripts/check-i18n.sh`** — no user-facing string literal in logic, every
  message id the code asks for exists in the catalog, and every catalog entry is
  used. That is what makes a second locale a translation job rather than a
  refactor.

## Licence

GPL-3.0.
