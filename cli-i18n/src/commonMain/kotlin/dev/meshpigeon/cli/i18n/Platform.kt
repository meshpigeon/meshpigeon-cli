package dev.meshpigeon.cli.i18n

/**
 * The language the environment asks for.
 *
 * Extra catalogs (`.properties` files under `$XDG_DATA_HOME/meshpigeon/locale`)
 * arrive with the second locale at P8; until then the environment selects among
 * the catalogs the binary carries, which today is English only.
 */
public expect fun environmentLanguage(): String?
