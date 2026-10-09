package com.assetlib.sdk

import java.util.Collections
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Content descriptions for one artwork version. The app still owns decorative and action semantics. */
class AssetAccessibility(val defaultLocale: String, descriptions: Map<String, String>) {
    val descriptions: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(descriptions))

    init {
        fun validLocale(value: String) = value.length <= 63 && localePattern.matches(value)
        require(validLocale(defaultLocale) && this.descriptions.size in 1..32) { "Invalid accessibility locales." }
        val seen = mutableSetOf<String>()
        require(this.descriptions.all { (locale, description) ->
            validLocale(locale) && seen.add(locale.lowercase(Locale.ROOT)) && !blankDescriptionPattern.matches(description) && description.length <= 1000
        }) { "Invalid accessibility description." }
        require(defaultLocale.lowercase(Locale.ROOT) in seen) { "The default accessibility locale needs a description." }
    }

    /** Exact locale, successively less-specific subtags, then the declared default; no device state is read. */
    fun localizedDescription(locale: String? = null): String {
        var candidate = locale
        while (!candidate.isNullOrEmpty()) {
            descriptions.entries.firstOrNull { it.key.equals(candidate, ignoreCase = true) }?.let { return it.value }
            candidate = candidate.substringBeforeLast('-', "")
        }
        return descriptions.entries.first { it.key.equals(defaultLocale, ignoreCase = true) }.value
    }
}

private val localePattern = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
// Match JSON/JavaScript trim whitespace, including NBSP and BOM, across SDKs.
private val blankDescriptionPattern = Regex("[\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]*")

internal fun parseAccessibility(value: JsonElement): AssetAccessibility {
    val metadata = value as? JsonObject ?: error("Accessibility must be an object.")
    val descriptions = metadata["descriptions"] as? JsonObject ?: error("Accessibility descriptions must be an object.")
    require(descriptions.size in 1..32) { "Accessibility requires 1 through 32 locales." }
    return AssetAccessibility(metadata.string("defaultLocale"), descriptions.keys.associateWith(descriptions::string))
}
