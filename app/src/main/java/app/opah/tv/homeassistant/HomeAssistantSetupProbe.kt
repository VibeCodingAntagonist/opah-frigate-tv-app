package app.opah.tv.homeassistant

import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class HomeAssistantSetupReceipt(val instruction: String? = null, val received: Boolean = false)

/** Delivery evidence is memory-only and can be advanced only by the authenticated receiver. */
internal class HomeAssistantSetupProbe(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val challenge: () -> String = { SecureRandom().nextInt(1_000_000).toString().padStart(6, '0') },
) {
    private val mutableState = MutableStateFlow(HomeAssistantSetupReceipt())
    val state = mutableState.asStateFlow()
    private var registration: String? = null
    private var started = 0L
    @Synchronized fun arm(registrationKey: String): String {
        val message = "Opah setup ${challenge()}"
        registration = registrationKey; started = now()
        mutableState.value = HomeAssistantSetupReceipt(message)
        return message
    }
    @Synchronized fun observe(registrationKey: String, body: String) {
        val value = current()
        if (registration == registrationKey && value.instruction != null && body == value.instruction) {
            mutableState.value = value.copy(received = true)
        }
    }
    @Synchronized fun current(): HomeAssistantSetupReceipt {
        if (registration != null && now() - started !in 0 until 300_000) clear()
        return mutableState.value
    }
    @Synchronized fun clear() { registration = null; mutableState.value = HomeAssistantSetupReceipt() }
}
