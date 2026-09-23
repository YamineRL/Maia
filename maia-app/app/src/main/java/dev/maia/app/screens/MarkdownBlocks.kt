package dev.maia.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.maia.app.answer.domainLike
import dev.maia.app.markdown.Markdown
import dev.maia.app.ui.Maia
import dev.maia.app.ui.MaiaColours
import dev.maia.app.ui.MaiaFonts

/**
 * One prose stretch of a reply, rendered as the Markdown it almost always is.
 *
 * The parser is [Markdown]; this file only decides what each block looks like
 * in Maia's own type and colour scale. Fences reuse the same sunken mono
 * surface as a standalone code piece, and a table is mono too: the padding
 * that keeps its columns straight only counts characters correctly when every
 * glyph is the same width.
 */
@Composable
fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { Markdown.blocks(text) }
    val colours = Maia.colours
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
        blocks.forEach { block ->
            when (block) {
                is Markdown.Block.Paragraph ->
                    Text(annotate(block.runs, colours), style = Maia.type.body, color = colours.inkStrong)
                is Markdown.Block.Heading -> Heading(block, colours)
                is Markdown.Block.Fence -> FenceSurface(block.text)
                is Markdown.Block.Table -> TableSurface(block)
                is Markdown.Block.Listing -> Listing(block, colours)
                is Markdown.Block.Quote -> Quote(block, colours)
                Markdown.Block.Rule ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colours.lineHair))
            }
        }
    }
}

@Composable
private fun Heading(block: Markdown.Block.Heading, colours: MaiaColours) {
    val style = when (block.level) {
        1 -> Maia.type.title
        2 -> Maia.type.heading
        3 -> Maia.type.action
        else -> Maia.type.body.copy(fontWeight = FontWeight.Medium)
    }
    Text(annotate(block.runs, colours), style = style, color = colours.inkStrong)
}

/** What the Code piece uses: sunken card, mono lines, sideways scroll. */
@Composable
private fun FenceSurface(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Maia.radius.card))
            .background(Maia.colours.surfaceSunken)
            .padding(Maia.space.md),
    ) {
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            text.lines().forEach { line ->
                Text(
                    if (line.isEmpty()) " " else line,
                    style = Maia.type.dataSmall,
                    color = Maia.colours.inkStrong,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * A pipe table as aligned mono columns on the same sunken surface a fence
 * gets. Cells are unpiped and padded to their column's widest cell; the
 * separator row that declared the table becomes a drawn line under the head.
 */
@Composable
private fun TableSurface(block: Markdown.Block.Table) {
    val widths = columnWidths(block)
    fun rowLine(cells: List<String>): String =
        widths.indices.joinToString("  ") { c ->
            (cells.getOrNull(c) ?: "").padEnd(widths[c])
        }.trimEnd()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Maia.radius.card))
            .background(Maia.colours.surfaceSunken)
            .padding(Maia.space.md),
    ) {
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(
                rowLine(block.header),
                style = Maia.type.dataSmall.copy(fontWeight = FontWeight.Medium),
                color = Maia.colours.inkStrong,
                softWrap = false,
            )
            Text(
                "─".repeat(widths.sum() + 2 * (widths.size - 1).coerceAtLeast(0)),
                style = Maia.type.dataSmall,
                color = Maia.colours.inkFaint,
                softWrap = false,
            )
            block.rows.forEach { row ->
                Text(
                    rowLine(row),
                    style = Maia.type.dataSmall,
                    color = Maia.colours.inkMid,
                    softWrap = false,
                )
            }
        }
    }
}

private fun columnWidths(table: Markdown.Block.Table): List<Int> {
    val cols = table.header.size
    val widths = IntArray(cols) { c -> table.header[c].length }
    for (row in table.rows) {
        for (c in row.indices) {
            if (c < cols && row[c].length > widths[c]) widths[c] = row[c].length
        }
    }
    return widths.toList()
}

@Composable
private fun Listing(block: Markdown.Block.Listing, colours: MaiaColours) {
    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.xs)) {
        block.items.forEachIndexed { i, runs ->
            Row {
                Text(
                    if (block.ordered) "${i + 1}." else "•",
                    style = Maia.type.dataSmall,
                    color = colours.inkMid,
                    modifier = Modifier.width(28.dp),
                )
                Text(annotate(runs, colours), style = Maia.type.body, color = colours.inkStrong)
            }
        }
    }
}

@Composable
private fun Quote(block: Markdown.Block.Quote, colours: MaiaColours) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(colours.lineStrong))
        Text(
            annotate(block.runs, colours),
            style = Maia.type.body,
            color = colours.inkMid,
            modifier = Modifier.padding(start = Maia.space.sm),
        )
    }
}

/**
 * Runs to an [AnnotatedString]. A link whose target resolves to a real URI
 * gets the accent and underline of one and a [LinkAnnotation.Url], so a tap
 * hands it to the browser: `ACTION_VIEW`, the same intent the web handoff
 * fires, and the tap is its own confirmation. A target that is not a URI at
 * all, `[x](docs/file.md)` say, is dressed as text rather than as a link
 * that would not open.
 */
private fun annotate(runs: List<Markdown.Run>, colours: MaiaColours): AnnotatedString =
    buildAnnotatedString {
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val target = run.link?.let(::linkTarget)
            val span = SpanStyle(
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                fontFamily = if (run.code) MaiaFonts.mono else null,
                color = when {
                    target != null -> colours.accentCore
                    run.code -> colours.inkHigh
                    else -> Color.Unspecified
                },
                background = if (run.code) colours.surfaceSunken else Color.Unspecified,
                textDecoration = when {
                    run.strike && target != null ->
                        TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline))
                    run.strike -> TextDecoration.LineThrough
                    target != null -> TextDecoration.Underline
                    else -> TextDecoration.None
                },
            )
            if (target != null) {
                withLink(LinkAnnotation.Url(target)) {
                    withStyle(span) { append(run.text) }
                }
            } else {
                withStyle(span) { append(run.text) }
            }
        }
    }

/**
 * What a link target opens as, or null when it is not a URI the browser can
 * take: the same rules `webUri` applies to a spoken target, minus the search
 * fallback, because a written relative path has a meaning a guessed search
 * would only distort.
 */
private fun linkTarget(raw: String): String? {
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        domainLike(trimmed) -> "https://$trimmed"
        else -> null
    }
}
