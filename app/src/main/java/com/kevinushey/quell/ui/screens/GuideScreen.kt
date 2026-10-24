package com.kevinushey.quell.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kevinushey.quell.ui.Markdown
import com.kevinushey.quell.ui.MdBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the same guide lives online, for links and for reading on a bigger screen. */
const val GUIDE_URL = "https://github.com/kevinushey/quell/blob/main/docs/guide.md"

/** The bundled docs/guide.md, rendered offline. */
@Composable
fun GuideScreen() {
    val context = LocalContext.current
    val blocks by produceState<List<MdBlock>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("guide.md").bufferedReader().use { it.readText() } }
                .map { Markdown.parse(it) }
                .getOrElse { listOf(MdBlock.Paragraph("The guide is not bundled with this build. It is online at $GUIDE_URL.")) }
        }
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val body = MaterialTheme.typography.bodyMedium
    val list = blocks ?: run {
        Text("Loading...", modifier = Modifier.padding(20.dp))
        return
    }

    fun open(url: String) {
        // Relative links point at the repository; send them to GitHub.
        val target = if (url.startsWith("http")) url else "https://github.com/kevinushey/quell/blob/main/docs/$url"
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = { open(GUIDE_URL) }, modifier = Modifier.padding(bottom = 4.dp)) { Text("Open on GitHub") }
        }
        items(list.size) { i ->
            when (val b = list[i]) {
                is MdBlock.Heading -> Text(
                    b.text,
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    modifier = Modifier.padding(top = if (b.level == 1) 0.dp else 12.dp),
                )
                is MdBlock.Paragraph -> InlineText(b.text, body, linkColor, ::open)
                is MdBlock.ListItem -> Row(Modifier.fillMaxWidth()) {
                    Text(if (b.ordered) "${b.number}." else "\u2022", style = body, modifier = Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) { InlineText(b.text, body, linkColor, ::open) }
                }
            }
        }
    }
}

@Composable
private fun InlineText(text: String, style: TextStyle, linkColor: androidx.compose.ui.graphics.Color, onLink: (String) -> Unit) {
    val spans = Markdown.spans(text)
    val annotated = buildAnnotatedString {
        for (s in spans) {
            when {
                s.link != null -> {
                    pushStringAnnotation("url", s.link)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(s.text) }
                    pop()
                }
                s.bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.text) }
                else -> append(s.text)
            }
        }
    }
    @Suppress("DEPRECATION")
    ClickableText(
        text = annotated,
        style = style.copy(color = MaterialTheme.colorScheme.onSurface),
        onClick = { offset ->
            annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let { onLink(it.item) }
        },
    )
}
