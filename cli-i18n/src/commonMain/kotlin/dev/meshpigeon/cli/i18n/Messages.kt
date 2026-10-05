package dev.meshpigeon.cli.i18n

/**
 * One language's messages: message id to template.
 *
 * A template is a whole string with named placeholders (`"{channel} · {count}
 * online"`) — never a fragment to be concatenated, because word order is part of
 * the translation and concatenation freezes English's.
 */
public class Catalog(
    /** The language tag this catalog carries, e.g. `en` or `de`. */
    public val language: String,
    private val templates: Map<String, String>,
) {
    /** Whether this catalog carries [id]. */
    public fun has(id: String): Boolean = templates.containsKey(id)

    /** Every id this catalog defines, for the CI gate. */
    public val ids: Set<String> get() = templates.keys

    /** The template for [id], or `null` when this catalog does not carry it. */
    public fun template(id: String): String? = templates[id]
}

/**
 * The catalogs a session renders with, and the rules for finding one.
 *
 * Every user-facing string in `mp` is a message id resolved here. That is a
 * deliberate constraint (the "no literals in logic" gate in CI), not a
 * preference: adding a language later must not be a refactor.
 */
public class Messages(
    /** The language the session will render in. */
    public val language: String,
    /** Every catalog available, most specific first. */
    catalogs: List<Catalog>,
    /** The language used for anything [language]'s catalog is missing. */
    public val fallbackLanguage: String = DEFAULT_LANGUAGE,
) {
    private val byLanguage: Map<String, Catalog> = catalogs.associateBy { it.language.lowercase() }
    private val chosen: Catalog? = byLanguage[language.lowercase()] ?: catalogs.firstOrNull()
    private val fallback: Catalog? = byLanguage[fallbackLanguage.lowercase()]

    /** Whether [id] resolves — in the chosen catalog or the fallback. */
    public fun has(id: String): Boolean = template(id) != null

    /** The raw template for [id], or `null` when nothing carries it. */
    public fun template(id: String): String? =
        chosen?.template(id)
            ?: fallback?.template(id)
            ?: byLanguage.values.firstNotNullOfOrNull { it.template(id) }

    /**
     * Resolves [id] and substitutes [args].
     *
     * An unknown id resolves to the id itself: a missing translation must be
     * visible as a missing translation, never as an empty string or a crash.
     */
    public fun t(id: String, vararg args: Pair<String, Any?>): String {
        val template = template(id) ?: return id
        return Interpolator.render(template, args.toMap())
    }

    /**
     * Resolves [id] with positional arguments, matched to the placeholders in the
     * order they appear: `tp("radio.purged")`-style templates take none, and a
     * template with `{0} {1}` takes its arguments in that order.
     */
    public fun tp(id: String, vararg args: Any?): String {
        val template = template(id) ?: return id
        val names = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.distinct()
        val named = names.mapIndexed { index, name -> name to args.getOrNull(index) }.toMap()
        return Interpolator.render(template, named)
    }

    /**
     * Resolves one of two ids by count.
     *
     * Plural forms are separate message ids, never an `if` in logic, so a
     * language with more than two forms can add keys rather than change code.
     */
    public fun plural(id: String, count: Int, vararg args: Pair<String, Any?>): String =
        t(if (count == 1) "$id.one" else "$id.many", "count" to count, *args)

    public companion object {
        public const val DEFAULT_LANGUAGE: String = "en"
    }
}

/** Matches `{name}` and `{0}` placeholders. */
private val PLACEHOLDER = Regex("""\{([a-zA-Z0-9_]+)}""")

/** Substitutes `{name}` placeholders, leaving unknown ones in place. */
internal object Interpolator {

    fun render(template: String, args: Map<String, Any?>): String =
        PLACEHOLDER.replace(template) { match ->
            val key = match.groupValues[1]
            when (val value = args[key]) {
                null -> match.value // an absent argument shows as its own name
                else -> value.toString()
            }
        }
}

/**
 * Picks the catalog to use from the environment, falling back to English.
 *
 * `LANG=de_DE.UTF-8` and `LC_ALL=de` both mean German; a tag nobody ships a
 * catalog for falls back rather than failing, because a missing translation is
 * never a reason to refuse to start.
 */
public fun selectCatalog(
    available: List<Catalog>,
    languageTag: String?,
    fallbackLanguage: String = Messages.DEFAULT_LANGUAGE,
): Messages {
    val requested = languageTag?.lowercase()
    val exact = available.firstOrNull { it.language.lowercase() == requested }
    val base = available.firstOrNull {
        it.language.substringBefore('-').lowercase() == requested?.substringBefore('-')?.lowercase()
    }
    val chosen = exact ?: base
        ?: available.firstOrNull { it.language.equals(fallbackLanguage, ignoreCase = true) }
        ?: available.firstOrNull()
    return Messages(
        language = chosen?.language ?: fallbackLanguage,
        catalogs = listOfNotNull(chosen) + available,
        fallbackLanguage = fallbackLanguage,
    )
}
