package dev.meshpigeon.cli.i18n

actual fun environmentLanguage(): String? =
    readEnvironment("LC_ALL")
        ?: readEnvironment("LANG")
        ?.substringBefore('.')
        ?.substringBefore('@')
        ?.takeIf { it.isNotBlank() && !it.equals("C", ignoreCase = true) && !it.equals("POSIX", ignoreCase = true) }

private fun readEnvironment(name: String): String? = System.getenv(name)
