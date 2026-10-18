package com.programmieramt.inklauncher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.programmieramt.inklauncher.apps.AppEntry
import java.text.Normalizer

@Composable
fun DrawerScreen(
    apps: List<AppEntry>,
    favoriteKeys: Set<String>,
    onLaunch: (AppEntry) -> Unit,
    onToggleFavorite: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(apps, query) { filterApps(apps, query) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Typing until exactly one app matches launches it, like Olauncher / Niagara.
    LaunchedEffect(results, query) {
        if (query.isNotBlank() && results.size == 1) onLaunch(results.first())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = InkText.search,
                cursorBrush = SolidColor(Ink),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let(onLaunch) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 16.dp, end = 64.dp)
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Suchen", style = InkText.search.copy(color = Ink.copy(alpha = 0.5f)))
                        inner()
                    }
                },
            )
            Text(
                "✕",
                style = InkText.search,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.CenterEnd)
                    .plainClickable(onClose)
                    .padding(12.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(results, key = { it.key }) { app ->
                DrawerItem(
                    app = app,
                    isFavorite = app.key in favoriteKeys,
                    onLaunch = onLaunch,
                    onToggleFavorite = onToggleFavorite,
                    onAppInfo = onAppInfo,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerItem(
    app: AppEntry,
    isFavorite: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onToggleFavorite: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Text(
            if (isFavorite) "${app.label}  ★" else app.label,
            style = InkText.drawerItem,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onLaunch(app) },
                    onLongClick = { menuOpen = true },
                )
                .padding(vertical = 12.dp),
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = Paper,
            shape = RectangleShape,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(2.dp, Ink),
        ) {
            DropdownMenuItem(
                text = { Text(if (isFavorite) "Aus Favoriten entfernen" else "Zu Favoriten", style = InkText.hint) },
                onClick = {
                    menuOpen = false
                    onToggleFavorite(app)
                },
            )
            DropdownMenuItem(
                text = { Text("App-Info", style = InkText.hint) },
                onClick = {
                    menuOpen = false
                    onAppInfo(app)
                },
            )
        }
    }
}

private fun normalize(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()

/** Prefix matches first, then word-start matches, then substring matches. */
private fun filterApps(apps: List<AppEntry>, query: String): List<AppEntry> {
    val q = normalize(query.trim())
    if (q.isEmpty()) return apps
    return apps
        .mapNotNull { app ->
            val label = normalize(app.label)
            val rank = when {
                label.startsWith(q) -> 0
                label.split(' ', '-', '.').any { it.startsWith(q) } -> 1
                q in label -> 2
                else -> return@mapNotNull null
            }
            rank to app
        }
        .sortedBy { it.first }
        .map { it.second }
}
