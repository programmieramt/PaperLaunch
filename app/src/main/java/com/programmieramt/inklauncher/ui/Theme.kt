package com.programmieramt.inklauncher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Pure black on white: no greys for text, they dither on e-ink. */
val Paper = Color.White
val Ink = Color.Black

object InkText {
    val clock = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
    val date = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
    val dateHint = TextStyle(fontSize = 14.sp, color = Ink)
    val arrow = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
    val tab = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val dock = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
    val drawerItem = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Ink)
    val search = TextStyle(fontSize = 24.sp, color = Ink)
    val hint = TextStyle(fontSize = 15.sp, color = Ink)
    val status = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
}

@Composable
fun InkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink, onPrimary = Paper,
            background = Paper, onBackground = Ink,
            surface = Paper, onSurface = Ink,
        ),
        content = content,
    )
}
