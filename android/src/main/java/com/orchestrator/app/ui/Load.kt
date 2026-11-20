package com.orchestrator.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.orchestrator.app.attempt

/** Something a page loads from the server: on its way, there, or not, with why in words. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** A page's data and a way to load it again. Leaving the page cancels the load; that's never an error. */
class Loaded<T>(val state: Load<T>, val reload: () -> Unit, val set: (T) -> Unit) {
    val value: T? get() = (state as? Load.Ready<T>)?.value
}

@Composable
fun <T> rememberLoad(vararg keys: Any?, fetch: suspend () -> T): Loaded<T> {
    var state by remember(*keys) { mutableStateOf<Load<T>>(Load.Loading) }
    var round by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(*keys, round) {
        if (state !is Load.Ready) state = Load.Loading
        state = attempt { fetch() }.fold({ Load.Ready(it) }, { Load.Failed(it.message ?: "Something went wrong") })
    }
    return Loaded(state, reload = { round++ }, set = { state = Load.Ready(it) })
}
