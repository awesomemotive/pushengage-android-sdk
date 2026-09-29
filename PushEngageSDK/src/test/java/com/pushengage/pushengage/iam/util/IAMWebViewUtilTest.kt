package com.pushengage.pushengage.iam.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers [IAMWebViewUtil.prepareHtmlContent] and [IAMWebViewUtil.ensureViewportMeta].
 *
 * The PEBridge polyfill injection is a recent addition:
 * authored HTML calls PEBridge.handleAction / PEBridge.reportHeight, and the
 * polyfill maps those onto the legacy window.Android bridge. These tests pin
 * that the polyfill lands inside <head>, before any body content, on every
 * input shape.
 */
@RunWith(RobolectricTestRunner::class)
class IAMWebViewUtilTest {

    // ------------------------------------------------------------- empty input

    @Test
    fun `empty input returns the fallback document`() {
        assertEquals(
            "<!DOCTYPE html><html><body><p>No content available</p></body></html>",
            IAMWebViewUtil.prepareHtmlContent("")
        )
    }

    // ----------------------------------------------------------- full document

    @Test
    fun `full document gets PEBridge polyfill injected inside head before body`() {
        val result = IAMWebViewUtil.prepareHtmlContent(FULL_DOCUMENT)

        val polyfillIdx = result.indexOf("window.PEBridge = {")
        val headCloseIdx = result.indexOf("</head>")
        val bodyOpenIdx = result.indexOf("<body")

        assertTrue("Polyfill must be present", polyfillIdx >= 0)
        assertTrue("Polyfill must be inside <head> (before </head>)", polyfillIdx < headCloseIdx)
        assertTrue("Polyfill must precede <body>", polyfillIdx < bodyOpenIdx)
        assertTrue(result.contains("handleAction: function(id)"))
        assertTrue(result.contains("reportHeight: function(height)"))
    }

    @Test
    fun `full document keeps original content and gets only the minimal CSS`() {
        val result = IAMWebViewUtil.prepareHtmlContent(FULL_DOCUMENT)

        assertTrue("Original title preserved", result.contains("<title>Promo</title>"))
        assertTrue("Original body content preserved", result.contains("<p>Hello</p>"))
        assertTrue("Transparent background injected", result.contains("background-color: transparent"))
        assertTrue("Image width safety cap injected", result.contains("img { max-width: 100%; }"))
        assertTrue("Minimal CSS lives in a style tag", result.contains("<style>"))
        assertTrue(
            "Minimal CSS must be inside <head>",
            result.indexOf("<style>") < result.indexOf("</head>")
        )
    }

    @Test
    fun `SDK does not restyle authored HTML`() {
        // Authored HTML is rendered faithfully. The SDK must not
        // inject resets or element restyling — doing so broke valid content
        // (e.g. forcing overflow let object-fit images paint over siblings).
        val result = IAMWebViewUtil.prepareHtmlContent(FULL_DOCUMENT)

        assertTrue("No box-sizing reset", !result.contains("box-sizing: border-box"))
        assertTrue("No blanket margin/padding reset", !result.contains("* {"))
        assertTrue("No overflow overrides", !result.contains("overflow: visible"))
        assertTrue("No forced height", !result.contains("height: auto !important"))
        assertTrue("No container-class restyling", !result.contains(".container, .banner"))
        assertTrue("No button restyling", !result.contains("button {"))
        assertTrue("No text-margin restyling", !result.contains("p, h1"))
    }

    @Test
    fun `polyfill is injected before the CSS reset`() {
        val result = IAMWebViewUtil.prepareHtmlContent(FULL_DOCUMENT)

        assertTrue(result.indexOf("window.PEBridge = {") < result.indexOf("<style>"))
    }

    @Test
    fun `height measurement script is injected before the closing body tag`() {
        val result = IAMWebViewUtil.prepareHtmlContent(FULL_DOCUMENT)

        val onloadIdx = result.indexOf("window.onload = function()")
        assertTrue("Height script must be present", onloadIdx >= 0)
        assertTrue(
            "Height script must sit before </body>",
            onloadIdx < result.lastIndexOf("</body>")
        )
        assertTrue("Legacy sendAction helper injected", result.contains("function sendAction(id)"))
    }

    @Test
    fun `polyfill maps PEBridge onto the legacy Android bridge`() {
        val result = IAMWebViewUtil.prepareHtmlContent(FULL_DOCUMENT)

        assertTrue(result.contains("window.Android.handleAction(String(id));"))
        assertTrue(
            result.contains("window.Android.onContentHeightMeasured(Math.ceil(Number(height) || 0));")
        )
    }

    @Test
    fun `polyfill carries the double-injection guard`() {
        val result = IAMWebViewUtil.prepareHtmlContent(FULL_DOCUMENT)

        assertTrue(
            "Polyfill must bail out when PEBridge already exists",
            result.contains("if (window.PEBridge) return;")
        )
        assertEquals("Polyfill must be injected exactly once", 1, result.occurrencesOf("window.PEBridge = {"))
    }

    // ------------------------------------------------------ body without head

    @Test
    fun `body without head gets a head created around the injections`() {
        // Includes a viewport meta so ensureViewportMeta leaves the fragment as-is
        // and prepareHtmlContent's own <head>-creation branch is exercised.
        val input = "<body><meta name=\"viewport\" content=\"width=device-width\"><p>Hi</p></body>"

        val result = IAMWebViewUtil.prepareHtmlContent(input)

        val headOpenIdx = result.indexOf("<head>")
        val polyfillIdx = result.indexOf("window.PEBridge = {")
        val headCloseIdx = result.indexOf("</head>")
        val bodyOpenIdx = result.indexOf("<body")

        assertTrue("A <head> must be created", headOpenIdx >= 0)
        assertTrue("Polyfill inside the created head", polyfillIdx in (headOpenIdx + 1) until headCloseIdx)
        assertTrue("Created head must precede <body>", headCloseIdx < bodyOpenIdx)
        assertTrue("Original content preserved", result.contains("<p>Hi</p>"))
        assertTrue("DOCTYPE prepended", result.trim().startsWith("<!DOCTYPE html>", ignoreCase = true))
        assertTrue("Minimal CSS injected", result.contains("background-color: transparent"))
    }

    // ------------------------------------------------- neither head nor body

    @Test
    fun `bare fragment is wrapped into a full document with injections in head`() {
        // No <meta>/viewport, no structure at all: ensureViewportMeta wraps the
        // fragment into a full document first, then prepareHtmlContent injects
        // into the head it created.
        val result = IAMWebViewUtil.prepareHtmlContent("<p>Just text</p>")

        assertTrue(result.trim().startsWith("<!DOCTYPE html>", ignoreCase = true))
        assertTrue("Viewport meta added", result.contains("name=\"viewport\""))
        val polyfillIdx = result.indexOf("window.PEBridge = {")
        assertTrue("Polyfill inside head", polyfillIdx in 0 until result.indexOf("</head>"))
        assertTrue("Fragment content preserved", result.contains("<p>Just text</p>"))
        assertTrue(
            "Fragment must land inside body",
            result.indexOf("<p>Just text</p>") > result.indexOf("<body")
        )
        assertTrue(
            "Height script before </body>",
            result.indexOf("window.onload") < result.lastIndexOf("</body>")
        )
    }

    @Test
    fun `structureless fragment that dodges viewport wrapping gets injections prepended and doctype added`() {
        // Contains "<meta" and "viewport" so ensureViewportMeta returns it as-is;
        // with no </head> and no <body>, prepareHtmlContent prepends the head
        // injection, appends the height script, then prepends the DOCTYPE.
        val input = "<meta name=\"viewport\" content=\"width=device-width\"><p>Promo</p>"

        val result = IAMWebViewUtil.prepareHtmlContent(input)

        assertTrue(result.startsWith("<!DOCTYPE html>"))
        assertTrue(
            "Polyfill must be prepended before the original content",
            result.indexOf("window.PEBridge = {") < result.indexOf("<p>Promo</p>")
        )
        assertTrue(
            "Height script must be appended after the original content",
            result.indexOf("<p>Promo</p>") < result.indexOf("window.onload")
        )
    }

    // ------------------------------------------------------------ viewport meta

    @Test
    fun `existing viewport meta is not duplicated`() {
        val input = "<!DOCTYPE html><html><head>" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
            "</head><body><p>Hello</p></body></html>"

        val result = IAMWebViewUtil.prepareHtmlContent(input)

        assertEquals("Viewport meta must appear exactly once", 1, result.occurrencesOf("name=\"viewport\""))
    }

    @Test
    fun `missing viewport meta is added exactly once inside head`() {
        val result = IAMWebViewUtil.prepareHtmlContent(FULL_DOCUMENT)

        assertEquals(1, result.occurrencesOf("name=\"viewport\""))
        assertTrue(
            "Viewport meta belongs in <head>",
            result.indexOf("name=\"viewport\"") < result.indexOf("</head>")
        )
    }

    @Test
    fun `ensureViewportMeta leaves content with existing viewport untouched`() {
        val input = "<html><head><meta name=\"viewport\" content=\"width=device-width\"></head>" +
            "<body></body></html>"

        assertEquals(input, IAMWebViewUtil.ensureViewportMeta(input))
    }

    @Test
    fun `ensureViewportMeta on empty input returns empty`() {
        assertEquals("", IAMWebViewUtil.ensureViewportMeta(""))
    }

    @Test
    fun `ensureViewportMeta adds viewport when only a charset meta exists`() {
        val input = "<html><head><meta charset=\"utf-8\"></head><body></body></html>"

        val result = IAMWebViewUtil.ensureViewportMeta(input)

        assertEquals(1, result.occurrencesOf("name=\"viewport\""))
        assertTrue("Existing charset meta preserved", result.contains("<meta charset=\"utf-8\">"))
    }

    // -------------------------------------------------------- characterization

    @Test
    fun `html tag without head gets a single head without duplicating the body`() {
        // ensureViewportMeta inserts a <head> before the existing <body> instead
        // of rewriting <html> (which used to emit nested <body><body> tags).
        val input = "<html><body><p>Hi</p></body></html>"

        val result = IAMWebViewUtil.prepareHtmlContent(input)

        val polyfillIdx = result.indexOf("window.PEBridge = {")
        assertTrue(polyfillIdx >= 0)
        assertTrue("Polyfill inside head", polyfillIdx < result.indexOf("</head>"))
        assertTrue("Original content preserved", result.contains("<p>Hi</p>"))
        assertTrue(result.trim().startsWith("<!DOCTYPE html>", ignoreCase = true))
        assertEquals("no duplicate opening body tag", 1, result.occurrencesOf("<body"))
        assertEquals("no duplicate closing body tag", 1, result.occurrencesOf("</body>"))
    }

    @Test
    fun `a header element is not mistaken for the document head`() {
        // HEAD_OPEN used to be <head[^>]*> with no word boundary, so <header ...>
        // matched and the viewport meta was spliced into body content while
        // prepareHtmlContent synthesised a separate, viewport-less head.
        val input = "<html><body><header class=\"hero\">Sale</header><p>Hi</p></body></html>"

        val result = IAMWebViewUtil.prepareHtmlContent(input)

        val headEnd = result.indexOf("</head>")
        val viewportIdx = result.indexOf("name=\"viewport\"")
        assertTrue("viewport meta present", viewportIdx >= 0)
        assertTrue("viewport meta must be inside the head, not in <header>", viewportIdx < headEnd)
        assertTrue("header element preserved", result.contains("<header class=\"hero\">Sale</header>"))
        assertEquals("exactly one viewport meta", 1, result.occurrencesOf("name=\"viewport\""))
    }

    @Test
    fun `head injection is not duplicated when content has a stray closing head token`() {
        // A second </head> in body text must not cause the head injection to be
        // applied twice (replaceFirst, not replace-all).
        val input = "<!DOCTYPE html><html><head><title>T</title></head>" +
            "<body><p>docs mention &lt;/head&gt; here</p><pre></head></pre></body></html>"

        val result = IAMWebViewUtil.prepareHtmlContent(input)

        assertEquals("Minimal CSS injected exactly once", 1, result.occurrencesOf("background-color: transparent"))
        assertEquals("polyfill injected exactly once", 1, result.occurrencesOf("window.PEBridge = {"))
    }

    @Test
    fun `a head-closing token inside a stylesheet comment does not split the document`() {
        // The insertion point is found by plain string search. Anchored on the
        // closing tag and taking the first match, this input injected inside the
        // CSS comment: the head closed there, and the remainder of the stylesheet
        // rendered as visible text in the message. Seen for real on a device.
        val input = "<!DOCTYPE html><html><head><style>\n" +
            "/* the SDK injects its rules just before </head> */\n" +
            "body{margin:0}\n.card{background:#0D47A1}\n" +
            "</style></head><body><div class=\"card\">hi</div></body></html>"

        val result = IAMWebViewUtil.prepareHtmlContent(input)

        val injection = result.indexOf("window.PEBridge = {")
        val authorComment = result.indexOf("/* the SDK injects")
        val authorRule = result.indexOf(".card{background:#0D47A1}")
        assertTrue("all three present", injection >= 0 && authorComment >= 0 && authorRule >= 0)

        // The injection goes in ahead of the author's stylesheet, not inside it.
        assertTrue("injection must precede the author comment", injection < authorComment)

        // The symptom of the split: injected content landing between the author's
        // comment and the rest of their stylesheet. That is what closed the head
        // early and pushed the remaining CSS into the body to render as text.
        //
        // Note the assertion cannot look for a head-closing tag here — the comment
        // deliberately contains that text, which is the whole reason this input
        // used to break.
        val betweenCommentAndRule = result.substring(authorComment, authorRule)
        assertTrue(
            "nothing may be injected inside the author's stylesheet",
            !betweenCommentAndRule.contains("window.PEBridge")
        )
        assertTrue(
            "the author's style element must not be closed mid-way",
            !betweenCommentAndRule.contains("</style>")
        )
        assertEquals("polyfill injected exactly once", 1, result.occurrencesOf("window.PEBridge = {"))
    }

    @Test
    fun `injected CSS comes before author styles so the author wins`() {
        // Equal specificity means the later rule wins, so these have to be FIRST
        // in the head. Injected last, `html, body { background-color: transparent }`
        // beat an author's `body { background: ... }` and the message rendered
        // see-through with the app behind it — the opposite of what the comment on
        // the injection promises.
        val input = "<!DOCTYPE html><html><head><style>body{background:#0D47A1}</style>" +
            "</head><body>hi</body></html>"

        val result = IAMWebViewUtil.prepareHtmlContent(input)

        val injected = result.indexOf("background-color: transparent")
        val authored = result.indexOf("body{background:#0D47A1}")
        assertTrue("both rules present", injected >= 0 && authored >= 0)
        assertTrue(
            "SDK rules must precede the author's so the author's win at equal specificity",
            injected < authored
        )
    }

    @Test
    fun `the bridge polyfill precedes an inline script in the head`() {
        // The polyfill's whole purpose is to exist before content scripts run.
        // Injected before the closing tag it landed after an inline head script,
        // so a campaign calling PEBridge at parse time found nothing there.
        val input = "<!DOCTYPE html><html><head><script>window.__early = typeof PEBridge;</script>" +
            "</head><body>hi</body></html>"

        val result = IAMWebViewUtil.prepareHtmlContent(input)

        assertTrue(
            "polyfill must come before the author's inline head script",
            result.indexOf("window.PEBridge = {") < result.indexOf("window.__early")
        )
    }

    @Test
    fun `output is safe to feed through prepareHtmlContent conventions - guard string survives`() {
        // The runtime guard ("if (window.PEBridge) return;") is what keeps a
        // second injection harmless in the WebView; make sure nothing strips it
        // on the least-structured input either.
        val result = IAMWebViewUtil.prepareHtmlContent("plain text banner")

        assertTrue(result.contains("if (window.PEBridge) return;"))
    }

    // ----------------------------------------------------------------- helpers

    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var idx = indexOf(needle)
        while (idx >= 0) {
            count++
            idx = indexOf(needle, idx + needle.length)
        }
        return count
    }

    private companion object {
        const val FULL_DOCUMENT =
            "<!DOCTYPE html><html><head><title>Promo</title></head>" +
                "<body><p>Hello</p></body></html>"
    }

}
