package dev.meshpigeon.cli.i18n

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun environmentLanguage(): String? =
    readEnvironment("LC_ALL")
        ?: readEnvironment("LANG")
        ?.substringBefore('.')
        ?.substringBefore('@')
        ?.takeIf { it.isNotBlank() && !it.equals("C", ignoreCase = true) && !it.equals("POSIX", ignoreCase = true) }

@OptIn(ExperimentalForeignApi::class)
private fun readEnvironment(name: String): String? =
    getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }
