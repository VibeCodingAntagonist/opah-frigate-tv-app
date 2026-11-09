package app.opah.tv.ui.views

import app.opah.tv.privacy.PinScope
import app.opah.tv.ui.PrivacyUiState

internal enum class ConnectionsAccess { UNAVAILABLE, LOCKED, ALLOWED }

/** Connection settings carry the same local protection even before Frigate is signed in. */
internal fun connectionsAccess(privacy: PrivacyUiState): ConnectionsAccess = when {
    !privacy.available || privacy.loading || privacy.pinRecordCorrupt || privacy.guestModeActive -> ConnectionsAccess.UNAVAILABLE
    privacy.pinConfigured && PinScope.SETTINGS in privacy.protectedScopes &&
        PinScope.SETTINGS !in privacy.unlockedScopes -> ConnectionsAccess.LOCKED
    else -> ConnectionsAccess.ALLOWED
}
