package dev.maia.app.markdown

import dev.maia.app.markdown.Markdown.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Markdown], the parser behind what a reply piece shows.
 *
 * The pins that matter are the two input realities from its own header: a
 * delta can leave any construct half-written, and model prose is full of
 * identifiers that must not become marks.
 */
class MarkdownTest {

    @Test
    fun `plain text is one paragraph`() {
        assertEquals(
            listOf(Block.Paragraph(listOf(Markdown.Run("hello there")))),
            Markdown.blocks("hello there"),
        )
    }

    @Test
    fun `a pipe table needs its separator to be a table`() {
        val blocks = Markdown.blocks("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |")
        assertEquals(
            listOf(
                Block.Table(
                    header = listOf("a", "b"),
                    rows = listOf(listOf("1", "2"), listOf("3", "4")),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `a table row without a separator yet is still text`() {
        val blocks = Markdown.blocks("| a | b |\n| 1 | 2 |")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is Block.Paragraph)
    }

    @Test
    fun `a fence never opened twice is code to the end`() {
        val blocks = Markdown.blocks("run this:\n```\npwd\nls\n")
        assertEquals(
            listOf(
                Block.Paragraph(listOf(Markdown.Run("run this:"))),
                Block.Fence("pwd\nls"),
            ),
            blocks,
        )
    }

    @Test
    fun `a closed fence yields its body only`() {
        val blocks = Markdown.blocks("```bash\npwd\n```\nafter")
        assertEquals(
            listOf(
                Block.Fence("pwd"),
                Block.Paragraph(listOf(Markdown.Run("after"))),
            ),
            blocks,
        )
    }

    @Test
    fun `bold, italic, code and strike inline`() {
        val runs = Markdown.inline("a **b** c *d* `e` ~~f~~")
        assertEquals(
            listOf(
                Markdown.Run("a "),
                Markdown.Run("b", bold = true),
                Markdown.Run(" c "),
                Markdown.Run("d", italic = true),
                Markdown.Run(" "),
                Markdown.Run("e", code = true),
                Markdown.Run(" "),
                Markdown.Run("f", strike = true),
            ),
            runs,
        )
    }

    @Test
    fun `snake_case stays literal`() {
        assertEquals(
            listOf(Markdown.Run("a snake_case name")),
            Markdown.inline("a snake_case name"),
        )
    }

    @Test
    fun `a link carries its target and not the syntax`() {
        assertEquals(
            listOf(Markdown.Run("the docs", link = "https://example.com")),
            Markdown.inline("[the docs](https://example.com)"),
        )
    }

    @Test
    fun `nested marks flatten into flags`() {
        assertEquals(
            listOf(Markdown.Run("deep", bold = true, italic = true)),
            Markdown.inline("**and *deep* rest**").let { it.subList(1, 2) },
        )
    }

    @Test
    fun `heading level and text`() {
        assertEquals(
            listOf(Block.Heading(2, listOf(Markdown.Run("plan")))),
            Markdown.blocks("## plan"),
        )
    }

    @Test
    fun `unordered and ordered lists`() {
        assertEquals(
            listOf(
                Block.Listing(false, listOf(listOf(Markdown.Run("one")), listOf(Markdown.Run("two")))),
                Block.Listing(true, listOf(listOf(Markdown.Run("first")), listOf(Markdown.Run("second")))),
            ),
            Markdown.blocks("- one\n- two\n\n1. first\n2. second"),
        )
    }

    @Test
    fun `a rule and a quote`() {
        assertEquals(
            listOf(
                Block.Rule,
                Block.Quote(listOf(Markdown.Run("noted"))),
            ),
            Markdown.blocks("---\n> noted"),
        )
    }
}
