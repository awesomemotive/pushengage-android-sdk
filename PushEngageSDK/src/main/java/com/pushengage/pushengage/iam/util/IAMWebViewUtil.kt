package com.pushengage.pushengage.iam.util

import com.pushengage.pushengage.helper.PELogger

/**
 * Utility class for WebView operations in In-App Messaging
 */
internal object IAMWebViewUtil {

    /**
     * Prepares HTML content for WebView display
     *
     * @param htmlContent Original HTML content
     * @return Prepared HTML content
     */
    @JvmStatic
    fun prepareHtmlContent(htmlContent: String): String {
        if (htmlContent.isEmpty()) {
            PELogger.debug("prepareHtmlContent: No content provided, returning default HTML")
            return "<!DOCTYPE html><html><body><p>No content available</p></body></html>"
        }

        PELogger.debug("prepareHtmlContent: Processing HTML content of length ${htmlContent.length}")
        var prepared = ensureViewportMeta(htmlContent)
        
        // MINIMAL injection only — the SDK renders authored HTML faithfully and
        // must not restyle it. Stylesheet-level rules placed FIRST in the head, so
        // any author style of equal or greater specificity wins (see the injection
        // site below — the position is what makes this true, not the wording):
        //  - transparent page background: required so the message draws its own
        //    card/banner surface over the app
        //  - img max-width safety cap: an image can never exceed the container
        //    width, whatever the author forgot
        // Nothing else: no resets, no container/button/text restyling, no
        // overflow or height overrides.
        val cssFixesForDisplay = """
            <style>
            html, body { background-color: transparent; }
            img { max-width: 100%; }
            </style>
        """.trimIndent()
        
        // Platform-neutral bridge: authored HTML calls
        // PEBridge.handleAction / PEBridge.reportHeight and each platform maps it
        // onto its native bridge. Injected at the START of <head> so it exists
        // before any inline content script runs, including one in the head itself.
        // The legacy Android.* global keeps working.
        val bridgePolyfill = """
            <script>
            (function() {
                if (window.PEBridge) return;
                window.PEBridge = {
                    handleAction: function(id) {
                        if (window.Android && typeof window.Android.handleAction === 'function') {
                            window.Android.handleAction(String(id));
                        }
                    },
                    reportHeight: function(height) {
                        if (window.Android && typeof window.Android.onContentHeightMeasured === 'function') {
                            window.Android.onContentHeightMeasured(Math.ceil(Number(height) || 0));
                        }
                    }
                };
            })();
            </script>
        """.trimIndent()

        val headInjection = "$bridgePolyfill\n$cssFixesForDisplay"

        // Injected directly after the opening <head>, which is load-bearing twice
        // over and not merely a placement preference.
        //
        // Correctness: the insertion point is found by plain string search, with no
        // idea of HTML structure. Anchoring on "</head>" and taking the first match
        // meant any earlier occurrence of that text won — including inside a CSS or
        // JS comment, a string literal, or body text. A campaign whose stylesheet
        // merely *mentioned* the head-closing tag in a comment had its document
        // split at the comment: the head closed early and the rest of the
        // stylesheet rendered as visible text in the message. The opening tag is a
        // far safer anchor, because content that could contain a stray copy of it
        // has not started yet.
        //
        // Cascade: at equal specificity the later rule wins, so injecting before
        // "</head>" placed these rules AFTER the author's stylesheet and quietly
        // beat it. An author writing the obvious `body { background: ... }` got a
        // transparent message with the host app showing through. First in the head
        // is what actually delivers the "any author style wins" promise above.
        val openHead = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE).find(prepared)
        prepared = when {
            openHead != null ->
                prepared.substring(0, openHead.range.last + 1) +
                        "\n" + headInjection +
                        prepared.substring(openHead.range.last + 1)
            // No head at all: synthesise one. replaceFirst so a later stray <body>
            // token in the content cannot inject a second copy.
            prepared.contains("<body", ignoreCase = true) ->
                Regex("<body", RegexOption.IGNORE_CASE)
                    .replaceFirst(prepared, "<head>$headInjection</head><body")
            else -> "$headInjection\n$prepared"
        }
        
        // Add a simple script to measure height
        val script = """
            <script>
            window.onload = function() {
                if (window.Android && typeof window.Android.onContentHeightMeasured === 'function') {
                    var body = document.body;
                    var height = Math.max(
                        body.scrollHeight,
                        body.offsetHeight,
                        document.documentElement.clientHeight,
                        document.documentElement.scrollHeight,
                        document.documentElement.offsetHeight
                    );
                    window.Android.onContentHeightMeasured(height);
                }
            };
            
            function sendAction(id) {
                if (window.Android && typeof window.Android.handleAction === 'function') {
                    window.Android.handleAction(id);
                }
            }
            </script>
        """.trimIndent()
        
        // Insert the script (replaceFirst — see note above)
        prepared = if (prepared.contains("</body>")) {
            prepared.replaceFirst("</body>", "$script\n</body>")
        } else {
            "$prepared\n$script"
        }
        
        // Ensure we have a DOCTYPE and basic HTML structure
        if (!prepared.trim().startsWith("<!DOCTYPE", ignoreCase = true)) {
            PELogger.debug("prepareHtmlContent: Adding DOCTYPE declaration")
            prepared = "<!DOCTYPE html>\n$prepared"
        }
        
        PELogger.debug("prepareHtmlContent: Prepared HTML content of length ${prepared.length}")
        return prepared
    }

    /**
     * Adds meta viewport tag to HTML content if missing
     *
     * @param htmlContent Original HTML content
     * @return HTML content with viewport meta tag
     */
    @JvmStatic
    fun ensureViewportMeta(htmlContent: String): String {
        if (htmlContent.isEmpty()) {
            return htmlContent
        }

        try {
            // Already has a viewport meta tag? Match a real <meta name="viewport">
            // tag rather than the words "<meta" and "viewport" appearing anywhere
            // independently (which false-matched a bare <meta charset> plus the
            // literal word "viewport" in body text).
            if (VIEWPORT_META.containsMatchIn(htmlContent)) {
                return htmlContent
            }

            val metaTag =
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">"

            return when {
                // Existing <head ...> — insert the meta as its first child.
                HEAD_OPEN.find(htmlContent) != null ->
                    insertAfter(htmlContent, HEAD_OPEN, "\n    $metaTag")
                // No head but a <body ...> — wrap a head just before it (no
                // duplicate <body>, unlike the previous <html>-rewrite which
                // produced nested body tags).
                BODY_OPEN.find(htmlContent) != null ->
                    insertBefore(htmlContent, BODY_OPEN, "<head>\n    $metaTag\n</head>\n")
                // Opening <html ...> only — insert a head right after it.
                HTML_OPEN.find(htmlContent) != null ->
                    insertAfter(htmlContent, HTML_OPEN, "\n<head>\n    $metaTag\n</head>")
                // Bare fragment — wrap into a full document.
                else ->
                    "<!DOCTYPE html>\n<html>\n<head>\n    $metaTag\n</head>\n<body>\n$htmlContent\n</body>\n</html>"
            }
        } catch (e: Exception) {
            PELogger.error("Error ensuring viewport meta: ${e.message}", e)
            return htmlContent
        }
    }

    private val VIEWPORT_META =
        Regex("""<meta[^>]*name\s*=\s*["']viewport["']""", RegexOption.IGNORE_CASE)
    // \b, so <header ...> is not mistaken for the document head — matching the
    // anchor used by the head injection in prepareHtmlContent. Without it a
    // campaign with no <head> but a <header> element had the viewport meta
    // spliced into its body content.
    private val HEAD_OPEN = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val BODY_OPEN = Regex("<body", RegexOption.IGNORE_CASE)
    private val HTML_OPEN = Regex("<html[^>]*>", RegexOption.IGNORE_CASE)

    /** Splices [text] in immediately after the first match of [pattern]. */
    private fun insertAfter(html: String, pattern: Regex, text: String): String {
        val match = pattern.find(html) ?: return html
        val at = match.range.last + 1
        return html.substring(0, at) + text + html.substring(at)
    }

    /** Splices [text] in immediately before the first match of [pattern]. */
    private fun insertBefore(html: String, pattern: Regex, text: String): String {
        val match = pattern.find(html) ?: return html
        val at = match.range.first
        return html.substring(0, at) + text + html.substring(at)
    }

}