package dev.lumenara.tv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Survives Activity recreation with the model; late subscribers also pass immediately. */
internal class FirstDrawGate {
    private val state = MutableStateFlow(false)
    val drawn = state.asStateFlow()
    fun onDraw() { state.value = true }
    suspend fun await() { drawn.first { it } }
}
