package dev.recipebridge.mobile

/**
 * Extract the URL a social app places inside a share caption.
 * Do not modify Instagram's tracking/query parameters here; the server
 * validates and canonicalizes social URLs before extracting content.
 */
internal fun firstSharedUrl(text: String): String? =
    Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        .find(text)?.value?.trimEnd('.', ',', ')', ']', ';', '!', '?')
