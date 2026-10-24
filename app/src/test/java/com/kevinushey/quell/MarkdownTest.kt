package com.kevinushey.quell

import com.kevinushey.quell.ui.Markdown
import com.kevinushey.quell.ui.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MarkdownTest {

    @Test
    fun parsesHeadingsParagraphsAndLists() {
        val text = """
            # Title

            A paragraph that
            wraps onto two lines.

            - first item
              continued
            - second item

            1. one
            2. two
            ## Next
        """.trimIndent()
        val blocks = Markdown.parse(text)
        assertEquals(MdBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MdBlock.Paragraph("A paragraph that wraps onto two lines."), blocks[1])
        assertEquals(MdBlock.ListItem("first item continued", ordered = false, number = 0), blocks[2])
        assertEquals(MdBlock.ListItem("second item", ordered = false, number = 0), blocks[3])
        assertEquals(MdBlock.ListItem("one", ordered = true, number = 1), blocks[4])
        assertEquals(MdBlock.ListItem("two", ordered = true, number = 2), blocks[5])
        assertEquals(MdBlock.Heading(2, "Next"), blocks[6])
        assertEquals(7, blocks.size)
    }

    @Test
    fun splitsBoldAndLinks() {
        val spans = Markdown.spans("See **Drive** runs in the [README](../README.md) now.")
        assertEquals(listOf("See ", "Drive", " runs in the ", "README", " now."), spans.map { it.text })
        assertTrue(spans[1].bold)
        assertEquals("../README.md", spans[3].link)
        assertNull(spans[0].link)
        // Unbalanced markers stay literal.
        assertEquals(listOf("a ** b [c"), Markdown.spans("a ** b [c").map { it.text })
    }

    @Test
    fun theBundledGuideParsesWithoutLosingText() {
        // The guide ships as an asset; every non-blank line must land in some block.
        val file = File("../docs/guide.md")
        assertTrue("docs/guide.md missing", file.exists())
        val text = file.readText()
        val blocks = Markdown.parse(text)
        assertTrue(blocks.first() is MdBlock.Heading)
        val words = text.lines().filter { it.isNotBlank() }.sumOf { it.trim().removePrefix("#").removePrefix("- ").split(' ').size }
        val kept = blocks.sumOf {
            when (it) {
                is MdBlock.Heading -> it.text.split(' ').size
                is MdBlock.Paragraph -> it.text.split(' ').size
                is MdBlock.ListItem -> it.text.split(' ').size
            }
        }
        assertTrue("kept $kept of $words words", kept >= words - blocks.size)
    }
}
