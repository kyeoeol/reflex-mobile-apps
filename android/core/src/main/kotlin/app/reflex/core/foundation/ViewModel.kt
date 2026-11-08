package app.reflex.core.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The strict, Elm-style contract every screen follows: actions in, state out, one place where state changes.
 *
 * - `S` State: an immutable data class; everything the screen shows. The View reads it and nothing else.
 * - `A` Action: a sealed interface; everything a View (or the CLI) can ask for. Only via [send].
 * - `M` Message: a sealed interface; actions arrive as `ViewAction(action)`, service results come back as messages.
 * - [update]: the reducer, and **the only place state ever changes** (returns the new state).
 *
 * Threading: every `send` must run on one thread (Dispatchers.Main in the app, the host's core thread in the CLI),
 * because [update] may launch side effects and `MutableStateFlow.update` must never retry.
 */
abstract class ViewModel<S : Any, A : Any, M : Any>(initial: S, protected val scope: CoroutineScope) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    /** Action → `Message.ViewAction(action)`. */
    protected abstract fun wrap(action: A): M

    /** The single writer. */
    protected abstract fun update(state: S, message: M): S

    fun send(action: A) = send(wrap(action))

    @JvmName("sendMessage")
    protected fun send(message: M) {
        _state.update { update(it, message) }
    }
}

/** Like `runCatching`, but never swallows coroutine cancellation. Use for service calls inside `scope.launch`. */
suspend inline fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
