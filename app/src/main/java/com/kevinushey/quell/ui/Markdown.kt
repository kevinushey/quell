package com.kevinushey.quell.ui

/**
 * The little markdown the bundled guide uses: headings, paragraphs, bullet
 * and numbered lists, with bold and links inline. Anything else is text.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(val text: String, val ordered: Boolean, val number: Int) : MdBlock
}

/** A run of inline text, optionally bold or linking somewhere. */
data class MdSpan(val text: String, val bold: Boolean = false, val link: String? = null)

object Markdown {

    fun parse(text: String): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        val buffer = StringBuilder()
        var inItem: MdBlock.ListItem? = null

        fun flushParagraph() {
            if (buffer.isNotBlank())
                blocks.add(MdBlock.Paragraph(buffer.toString().trim()))
            buffer.setLength(0)
        }
        fun flushItem() {
            inItem?.let { blocks.add(it.copy(text = buffer.toString().trim())) }
            inItem = null
            buffer.setLength(0)
        }
        fun flush() {
            if (inItem != null)
                flushItem()
            else
                flushParagraph()
        }

        for (raw in text.lines()) {
            val line = raw.trimEnd()
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            val numbered = NUMBERED.matchEntire(line)
            when {
                line.isBlank() -> flush()
                heading != null -> {
                    flush()
                    blocks.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim()))
                }
                bullet != null -> {
                    flush()
                    inItem = MdBlock.ListItem("", ordered = false, number = 0)
                    buffer.append(bullet.groupValues[1])
                }
                numbered != null -> {
                    flush()
                    inItem = MdBlock.ListItem("", ordered = true, number = numbered.groupValues[1].toInt())
                    buffer.append(numbered.groupValues[2])
                }
                else -> {
                    // Continuation lines of a list item are indented; anything else joins the paragraph.
                    if (inItem != null && !line.startsWith(" "))
                        flushItem()
                    if (buffer.isNotEmpty())
                        buffer.append(' ')
                    buffer.append(line.trim())
                }
            }
        }
        flush()
        return blocks
    }

    /** Splits a block's text into bold, link and plain spans. */
    fun spans(text: String): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        var i = 0
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isNotEmpty())
                out.add(MdSpan(plain.toString()))
            plain.setLength(0)
        }
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    out.add(MdSpan(text.substring(i + 2, end), bold = true))
                    i = end + 2
                    continue
                }
            }
            if (text[i] == '[') {
                val close = text.indexOf(']', i)
                if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                    val end = text.indexOf(')', close)
                    if (end > close) {
                        flushPlain()
                        out.add(MdSpan(text.substring(i + 1, close), link = text.substring(close + 2, end)))
                        i = end + 1
                        continue
                    }
                }
            }
            plain.append(text[i])
            i++
        }
        flushPlain()
        return out
    }

    private val HEADING = Regex("^(#{1,3})\\s+(.*)$")
    private val BULLET = Regex("^- (.*)$")
    private val NUMBERED = Regex("^(\\d+)\\. (.*)$")
}
