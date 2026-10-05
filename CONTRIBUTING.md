# Contributing to meshpigeon-cli

## Pull requests

1. **Keep changes minimal and mapped to a stated need.** No drive-by refactors,
   no reformatting of lines you did not have to touch, no dependency upgrades
   smuggled in with a feature.
2. **Every behaviour change adds or extends a test named for the requirement it
   protects** — in `cli-journal` for the session, `cli-core` for the vocabulary,
   `cli-i18n` for the words.
3. **Run the checks before pushing:**

   ```sh
   ./gradlew build          # every target, every test, both gates
   sh scripts/check-imports.sh
   sh scripts/check-i18n.sh
   ```

4. **Every word a person reads goes in `cli-i18n`.** A new command means a new
   `cmd.*` entry and its message ids; the gate fails otherwise, which is the
   point.

## Layer rules (review gates)

- `cli-core` decides *what* a command means and which words express it.
  `cli-render` decides only layout; `cli-journal` decides only the session loop;
  `cli-app` only wires the platform. Nothing below decides business rules.
- No client code may reach past `meshpigeon-core`'s public API, and no module
  below may know what transport a radio is on.
- A command produces a typed output model; the renderers draw it. Two renderers
  of one model cannot drift the way two command paths can.
- No user-facing string literal in a command, a renderer or the session.

## Conventional commits

`feat:`, `fix:`, `docs:`, `test:`, `chore:`, `refactor:` — one logical change
per commit, on a short-lived branch off `main`.

## Licence

GPL-3.0.
