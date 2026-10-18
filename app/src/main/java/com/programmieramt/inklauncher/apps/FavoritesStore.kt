package com.programmieramt.inklauncher.apps

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "launcher")

/** Ordered list of favorite [AppEntry.key]s. */
class FavoritesStore(private val context: Context) {
    private val favoritesKey = stringPreferencesKey("favorites")

    val favorites: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[favoritesKey]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun update(transform: (List<String>) -> List<String>) {
        context.dataStore.edit { prefs ->
            val current = prefs[favoritesKey]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            prefs[favoritesKey] = transform(current).distinct().joinToString("\n")
        }
    }
}
