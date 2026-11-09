package app.opah.tv.ui.views

import android.view.View
import app.opah.tv.setup.SetupProgress
import app.opah.tv.setup.SetupStatus

/** The view owns drafts and navigation; this boundary owns each explicit external operation. */
internal interface GuidedSetupActions {
    fun status(): SetupStatus
    fun frigateForm(): NativeSetupForm
    fun homeAssistantForm(): NativeSetupForm
    suspend fun checkFrigate(): Boolean
    suspend fun checkHomeAssistant(): Boolean
    suspend fun applyNotifications(progress: SetupProgress): String?
    suspend fun textOnlyPrivacy()
    suspend fun messageDetails(show: Boolean)
    fun configurePin()
    fun managePrivacy()
    fun requestNotifications(): String?
    fun requestPopups()
    fun inlinePreview(): View
    suspend fun popupPreview(): Boolean
    fun beginDeliveryCheck(): String?
    fun finish(progress: SetupProgress, completed: Boolean)
    fun close()
}
