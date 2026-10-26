package cl.dlopez.grabadoroculto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Estado compartido entre el servicio de grabación y la pantalla principal. */
object RecorderState {

    sealed interface Status {
        data object Idle : Status
        data object Starting : Status
        data class Recording(val startedAtElapsed: Long) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    /** Último mensaje para el usuario (archivo guardado o error). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    internal fun set(status: Status) {
        _status.value = status
    }

    internal fun post(message: String) {
        _message.value = message
    }
}
