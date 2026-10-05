# The session

`mp` is the interface. It takes no arguments: `--help` and `--version` are the
only words the binary itself understands, and everything else happens inside, as
a slash command.

```console
$ mp

  MeshPigeon 0.1.0 · no identity · no radio

  mp ▸ /help
  mp ▸ /link add bench tcp://10.0.0.5:5000
  mp ▸ /radio connect bench
  connected to 10.0.0.5:5000 (XIAO WIO)
  mp ▸ /radio info
  board         XIAO WIO
  firmware      0.2.0
  spec          2
  uptime        3h 12m
  boots         41
  battery       3980 mV
  noise floor   — 
  radio         ok
  packets held  0 of 4096 B
  dropped       0
  capabilities   Wi-Fi, BLE, USB-CDC, battery
```

## Targets

A link is named once and then used as a target: `/radio connect bench`. A bare
`host:port` works too, as does `usb:/dev/ttyACM0` once the USB link lands.

## Commands

```
/help  /quit  /exit  /version  /json  /color  /clear  /doctor

/radio   connect <target> [pin]      open a link to a radio
         disconnect                  close the link
         info                        what the radio says about itself
         status                      connection status
         auth <pin>                  authenticate with the device PIN
         tuning                      the radio's tuning
         retune <frequency> [bw] [sf] [cr] [power]
         settings                    the device settings
         history [limit] [since]     the packets the radio holds
         purge                       clear the packet store
         reboot                      restart the radio
         factory-reset               reset it to as it shipped
         <alone>                     what /radio can do

/link    list · add <name> <target> · remove <name> · test <name>
```

`/help` is generated from the same declaration that parses the input and
completes it, so the three cannot drift.

## Scripting

The session reads its commands from stdin, so a script *is* a session:

```sh
printf '/radio connect 127.0.0.1:8801\n/radio info\n/quit\n' | mp
mp < scripts/acceptance/first-message.mp
MP_PROFILE=ci mp < scripts/acceptance/dm.mp
```

* A non-TTY stdin runs the same commands with no prompt and no colour.
* The exit status is the **last non-zero command status**: `0` when everything
  worked, `1` when a command failed, `2` when a command was wrong (unknown
  command, missing or unknown argument), `130` when interrupted.
* `MP_HOME` chooses where the session keeps its files; `MP_PROFILE` chooses a
  profile, so a test never touches your database.

## Output modes

`/json on` switches the *output mode* inside the session — there is no `--json`
flag with a second implementation. Every command then answers with one envelope:

```json
{"schema":1,"command":"/radio info","ok":true,"status":0,"data":{...},"error":null}
```

`schema` is the version of that shape, so a script can assert on it, and any
change to an output model has to decide whether the schema moved. A failing
command answers `ok: false`, the status number, and the reason.

## `/doctor`

Everything a bug report needs in one place: version, language, profile, home, the
output modes, the configured links, the phase of the connection, and what the
radio last reported. In `/json` it is machine-readable, which is the first thing
to paste into an issue.

## Where files live

```
$XDG_CONFIG_HOME/meshpigeon/          (display preferences, from P2)
$XDG_DATA_HOME/meshpigeon/
  meshpigeon.db                      the one database (from P2)
  keys/master.key                    seals secrets at rest (from P2)
  history                            session history (from P5)
  locale/<lang>.properties           extra languages (from P8)
MP_HOME=…                            overrides the XDG directories
```

## Adding a command

1. Declare it in `SessionCommands` with a summary id, its arguments, and what it
   returns — one declaration gives you parsing, `/help` and completion.
2. Every word it shows comes from `cli-i18n`: add the ids to `EnglishCatalog`
   (the i18n gate will tell you which you forgot).
3. Add a test to `cli-journal` that drives the command through the real session
   and asserts on the output.
