# meshpigeon-cli — Guiding Principles

> **The CLI is a first-class client, not a test harness.** Anything hard in a
> GUI must be reachable from `mp`, because this is how it gets found and fixed.
>
> 1. **The session is the interface.** No application options. `--help` and
>    `--version` are the only words the binary understands; everything else is a
>    slash command inside the session.
> 2. **One command declaration drives four things** — the interactive parser,
>    `/help`, tab completion and the piped-script parser. They cannot drift
>    because they are the same data.
> 3. **A piped script is the same loop as a person.** No line editor, no
>    colours, same commands, same side effects. A behaviour that only works when
>    typed is a behaviour CI cannot check.
> 4. **A client holds no business logic.** Any decision belongs in
>    `meshpigeon-core`; this repository decides words and layout.
> 5. **Hide mechanics; expose outcomes.** "heard ✓", "delivered ✓✓" — never
>    ACK, flood route, payload or PSK in anything a person reads.
> 6. **No user-facing string literal in logic.** Every visible text is a message
>    id resolved through `cli-i18n`, with whole templates and positional
>    placeholders, so a second language is a translation job.
> 7. **Protocol- and user-supplied data is never translated.** Channel names,
>    contact names and packet contents pass through verbatim.
> 8. **Offline-first:** the session is useful with no radio connected, and
>    `/doctor` explains why.
> 9. **Minimal diffs; match the surrounding style; every change maps to a stated
>    need.**

Concrete consequences, enforced in CI:

- `scripts/check-imports.sh` — only `meshpigeon-core`'s public API; no `java.`,
  no generated protobuf type, no core `internal` package, no chip identifier.
- `scripts/check-i18n.sh` — no string literal in logic, every id the code asks
  for exists in the catalog, every catalog entry is used.
- `scripts/e2e-sim.sh` drives the real session against `meshpigeon-sim` in CI.
