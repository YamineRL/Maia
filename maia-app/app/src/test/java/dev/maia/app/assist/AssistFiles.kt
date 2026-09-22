package dev.maia.app.assist

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reading `:app`'s own manifest and XML resources from a JVM test.
 *
 * There is no Robolectric here and no wish to add one: everything these tests
 * assert is a fact about text on disk, and reading the text is both the
 * cheapest and the most honest way to assert it. What it cannot see is the
 * merged manifest, so anything a library module contributes is invisible here.
 * For components that gap is narrow and stated rather than papered over:
 * `:core-audio` and `:core-actions` contribute no components, and every
 * component named in these tests is declared in the file below. For permissions
 * it was never narrow, which is why the permission set is checked elsewhere, by
 * `checkMergedManifestPermissions` in `maia-app/build.gradle.kts`.
 */
internal object AssistFiles {

    /**
     * `:app`'s `namespace`, from `app/build.gradle.kts`. The manifest carries no
     * `package` attribute any more, so a leading-dot class name in it resolves
     * against this.
     */
    const val NAMESPACE: String = "dev.maia.app"

    const val ANDROID_NS: String = "http://schemas.android.com/apk/res/android"

    val manifest: Element by lazy { parse(find("src/main/AndroidManifest.xml")) }
    val shortcuts: Element by lazy { parse(find("src/main/res/xml/shortcuts.xml")) }
    val voiceInteraction: Element by lazy { parse(find("src/main/res/xml/voice_interaction.xml")) }

    /**
     * Gradle runs unit tests with the module directory as the working
     * directory, but that is a default rather than a promise, so the repository
     * layout is tried as well. A miss fails loudly with the path it looked in,
     * because a test that silently found nothing would pass.
     */
    private fun find(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"), File("maia-app/app/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: error("cannot find $relative from ${File(".").absolutePath}")
    }

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Nothing here has a DTD, and a parser that would fetch one is a
            // test that depends on the network.
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        return factory.newDocumentBuilder().parse(file).documentElement
    }
}

/** An `android:` attribute, or null when it is absent. */
internal fun Element.android(name: String): String? =
    getAttributeNS(AssistFiles.ANDROID_NS, name).takeIf { it.isNotEmpty() }

/** Every descendant element with this tag, in document order. */
internal fun Element.tags(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

/** Direct children, for the cases where nesting is the point. */
internal fun Element.children(tag: String): List<Element> {
    val out = mutableListOf<Element>()
    var node: Node? = firstChild
    while (node != null) {
        if (node is Element && node.tagName == tag) out += node
        node = node.nextSibling
    }
    return out
}

/** A manifest class name, leading dot resolved, as the merger would. */
internal fun qualified(name: String): String =
    if (name.startsWith(".")) AssistFiles.NAMESPACE + name else name

/** Every class declared in the manifest under [tag], fully qualified. */
internal fun declared(tag: String): List<String> =
    AssistFiles.manifest.tags(tag).mapNotNull { it.android("name") }.map(::qualified)
