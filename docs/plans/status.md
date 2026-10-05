# Plan status

The plan this repository implements:
[`2026-10-04-meshpigeon-core-and-cli-plan.md`](2026-10-04-meshpigeon-core-and-cli-plan.md)
(v6). The core's own status — decisions, deviations, phase table — is in
[meshpigeon-core/docs/plans/status.md](https://github.com/meshpigeon/meshpigeon-core/blob/main/docs/plans/status.md).

## Where the plan stands, from the CLI's side

| Phase | Status |
|---|---|
| P0 scaffolding | done — module layout (`cli-core`, `cli-render`, `cli-i18n`, `cli-journal`, `cli-app`), Clikt + Mordant, the session loop, `/json`, exit statuses, the i18n catalog and its gate, import gate |
| P1 radio API + TCP | done — `/radio` and `/link` against a real `meshpigeon-sim`, in CI as `scripts/e2e-sim.sh` |
| P2 USB-CDC + storage | next — `/db`, the stored links, the sealed PIN |
| P5 the session, polished | not started — the loop, the prompt and the JSON mode are P0/P1; the raw-mode line editor, history, completion and the streaming chat screen are P5 |

## Decisions taken while building

| # | Question | Decision | Why |
|---|---|---|---|
| **D9′** | Clikt and Mordant versions | **5.0.3 / 3.0.2**, not 5.1.0 / 3.1.0 | Both publish Kotlin/Native klibs built with Kotlin 2.3/2.4, which the 2.2.21 toolchain cannot read; these are the newest releases built with an older ABI. Revisit when Kotlin moves |
| **D38** | How does `meshpigeon-core` get here? | A Gradle **composite build** (`includeBuild("../meshpigeon-core")`) with dependency substitution | During P1–P6 the two repositories develop against each other; once core is published (P8) this becomes a plain version reference and the `includeBuild` goes away |
| **D39** | Where does the output vocabulary live? | `cli-core` chooses the words (through `cli-i18n`); `cli-render` only aligns and draws | The renderer's job is layout. Field labels are message ids like everything else, which is what lets the i18n gate mean something |
| **D40** | Links before there is a database? | In memory, behind `LinkBook` | The plan puts links in the database (P2, `radio_link`); the interface the commands use is the same one, so P2 changes where they live and nothing else |
| **D41** | Terminal abstraction | `InputSource` / `OutputSink` | A test drives the real vocabulary without a TTY, and a piped script is the same loop with a file for stdin |

## Deviations from the plan

* **No `config.toml` yet.** Links and the PIN live in the database from P2,
  which is the plan's own design; a config file would be a second source of
  truth for display preferences that `/json` and `/color` already set.
* **TOML is not used at all**: `kotlinx-serialization-toml` is not published on
  Maven Central (checked), so there was nothing to depend on.
* **Mordant is the renderer, not the widget set.** Tables and live displays
  arrive with the chat screen (P5); what is here honours terminal width and
  `AnsiLevel` today, which is what `/color off` needs.
