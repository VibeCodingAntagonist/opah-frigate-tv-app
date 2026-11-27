package app.opah.tv.privacy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface PinSetupResult {
    data object Created : PinSetupResult
    data object AlreadyConfigured : PinSetupResult
    data object ConfirmationMismatch : PinSetupResult
    data object Malformed : PinSetupResult
    data object Trivial : PinSetupResult
    data object Unavailable : PinSetupResult
}

internal sealed interface PinUnlockResult {
    data class Unlocked(val proof: PinUnlockProof) : PinUnlockResult
    data class WrongPin(val retryAfterMillis: Long) : PinUnlockResult
    data class Cooldown(val retryAfterMillis: Long) : PinUnlockResult
    data object NotConfigured : PinUnlockResult
    data object CorruptRecord : PinUnlockResult
    data object Unavailable : PinUnlockResult
}

internal sealed interface PinProtectionChangeResult {
    data object Updated : PinProtectionChangeResult
    data object Removed : PinProtectionChangeResult
    data object Unchanged : PinProtectionChangeResult
    data object Locked : PinProtectionChangeResult
    data object Unavailable : PinProtectionChangeResult
}

/**
 * Serial transaction boundary for KDF work, encrypted attempt persistence, and unlock issuance.
 * A grant is issued only after every security-relevant write has succeeded.
 */
internal class PinCredentialService(
    private val store: PinCredentialStore,
    private val privacyRepository: PrivacyRepository,
    private val unlockSessions: PinUnlockSessionOwner,
    private val kdf: PinCredentialKdf = PinCredentialKdf(),
    private val rateLimiter: PinAttemptRateLimiter = PinAttemptRateLimiter(),
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val monotonicClockMillis: () -> Long,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val kdfDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val transactionMutex = Mutex()
    private var restoredCredentialRevision: Long? = null
    private var storageFailed = false

    suspend fun setup(
        pin: CharArray,
        confirmation: CharArray,
        protectedScopes: Set<PinScope>,
    ): PinSetupResult = withContext(storageDispatcher) {
        privacyRepository.initialize()
        transactionMutex.withLock {
            if (storageFailed) {
                pin.fill('\u0000')
                confirmation.fill('\u0000')
                return@withLock PinSetupResult.Unavailable
            }
            when (store.read()) {
                PinCredentialStoreRead.Missing -> Unit
                is PinCredentialStoreRead.Available -> {
                    pin.fill('\u0000')
                    confirmation.fill('\u0000')
                    return@withLock PinSetupResult.AlreadyConfigured
                }
                PinCredentialStoreRead.Corrupt -> {
                    pin.fill('\u0000')
                    confirmation.fill('\u0000')
                    return@withLock PinSetupResult.Unavailable
                }
            }
            val nowWall = wallClockMillis()
            val setup = withContext(kdfDispatcher) {
                kdf.setup(pin, confirmation, protectedScopes, nowWall)
            }
            val created = when (setup) {
                is PinSetupOutcome.Created -> setup.record
                PinSetupOutcome.ConfirmationMismatch -> return@withLock PinSetupResult.ConfirmationMismatch
                PinSetupOutcome.Malformed -> return@withLock PinSetupResult.Malformed
                PinSetupOutcome.Trivial -> return@withLock PinSetupResult.Trivial
                PinSetupOutcome.KdfUnavailable -> return@withLock PinSetupResult.Unavailable
            }
            val snapshot = PinCredentialSnapshot(created)
            if (store.write(snapshot) != PinCredentialStoreWrite.Written) {
                storageFailed = true
                return@withLock PinSetupResult.Unavailable
            }
            restoredCredentialRevision = snapshot.credentialRevision
            rateLimiter.recordSuccess()
            if (
                privacyRepository.refreshPinProtection(
                    unlockScopes = created.protectedScopes,
                    nowMonotonicMillis = monotonicClockMillis(),
                ) !is PrivacyPolicyMutationResult.Updated
            ) {
                return@withLock PinSetupResult.Unavailable
            }
            PinSetupResult.Created
        }
    }

    suspend fun verify(pin: CharArray): PinUnlockResult = verify(pin, unlockSession = true)

    /** Validate a PIN for one request without unlocking other protected areas. */
    suspend fun verifyForSingleAction(pin: CharArray): Long? =
        (verify(pin, unlockSession = false) as? PinUnlockResult.Unlocked)?.proof?.privacyEpoch

    private suspend fun verify(pin: CharArray, unlockSession: Boolean): PinUnlockResult = withContext(storageDispatcher) {
        privacyRepository.initialize()
        transactionMutex.withLock {
            try {
                if (storageFailed) return@withLock PinUnlockResult.Unavailable
                val snapshot = when (val read = store.read()) {
                    PinCredentialStoreRead.Missing -> return@withLock PinUnlockResult.NotConfigured
                    is PinCredentialStoreRead.Available -> read.snapshot
                    PinCredentialStoreRead.Corrupt -> return@withLock PinUnlockResult.CorruptRecord
                }
                val nowWall = wallClockMillis()
                val nowMonotonic = monotonicClockMillis()
                val attempts = if (restoredCredentialRevision != snapshot.credentialRevision) {
                    rateLimiter.restore(snapshot.attemptState, nowWall, nowMonotonic).also {
                        restoredCredentialRevision = snapshot.credentialRevision
                    }
                } else {
                    snapshot.attemptState
                }
                when (val admission = rateLimiter.admission(attempts, nowWall, nowMonotonic)) {
                    PinAttemptAdmission.Allowed -> Unit
                    is PinAttemptAdmission.Cooldown -> {
                        return@withLock PinUnlockResult.Cooldown(admission.remainingMillis)
                    }
                    PinAttemptAdmission.CorruptState -> return@withLock PinUnlockResult.CorruptRecord
                }
                when (val verification = withContext(kdfDispatcher) {
                    kdf.verify(pin, snapshot.verifierRecord)
                }) {
                    PinVerificationOutcome.NotMatched -> {
                        val failedAttempts = rateLimiter.recordFailure(attempts, nowWall, nowMonotonic)
                        val failedSnapshot = snapshot.copy(attemptState = failedAttempts)
                        if (store.write(failedSnapshot) != PinCredentialStoreWrite.Written) {
                            storageFailed = true
                            return@withLock PinUnlockResult.Unavailable
                        }
                        val retry = when (
                            val next = rateLimiter.admission(failedAttempts, nowWall, nowMonotonic)
                        ) {
                            is PinAttemptAdmission.Cooldown -> next.remainingMillis
                            PinAttemptAdmission.Allowed,
                            PinAttemptAdmission.CorruptState,
                            -> 0L
                        }
                        PinUnlockResult.WrongPin(retry)
                    }
                    is PinVerificationOutcome.Matched -> completeSuccessfulVerification(
                        snapshot = snapshot,
                        upgradedRecord = verification.upgradedRecord,
                        nowWallClockMillis = nowWall,
                        nowMonotonicMillis = nowMonotonic,
                        unlockSession = unlockSession,
                    )
                    PinVerificationOutcome.CorruptRecord -> PinUnlockResult.CorruptRecord
                    PinVerificationOutcome.KdfUnavailable -> PinUnlockResult.Unavailable
                }
            } finally {
                pin.fill('\u0000')
            }
        }
    }

    suspend fun updateProtectedScopes(
        protectedScopes: Set<PinScope>,
    ): PinProtectionChangeResult = withContext(storageDispatcher) {
        privacyRepository.initialize()
        transactionMutex.withLock {
            if (storageFailed) return@withLock PinProtectionChangeResult.Unavailable
            val repositoryState = privacyRepository.state.value as? PrivacyRepositoryState.Ready
                ?: return@withLock PinProtectionChangeResult.Unavailable
            val nowMonotonic = monotonicClockMillis()
            if (unlockSessions.currentProof(repositoryState.policy.epoch, nowMonotonic) == null) {
                return@withLock PinProtectionChangeResult.Locked
            }
            val snapshot = when (val read = store.read()) {
                is PinCredentialStoreRead.Available -> read.snapshot
                PinCredentialStoreRead.Missing,
                PinCredentialStoreRead.Corrupt,
                -> return@withLock PinProtectionChangeResult.Unavailable
            }
            val updatedScopes = protectedScopes - PinScope.EXIT_GUEST_MODE
            if (updatedScopes == snapshot.verifierRecord.protectedScopes) {
                return@withLock PinProtectionChangeResult.Unchanged
            }
            val nextRevision = snapshot.credentialRevision.takeIf { it < MAX_CREDENTIAL_REVISION }
                ?.plus(1L) ?: return@withLock PinProtectionChangeResult.Unavailable
            val updated = snapshot.copy(
                verifierRecord = snapshot.verifierRecord.withProtectedScopes(updatedScopes),
                credentialRevision = nextRevision,
            )
            if (store.write(updated) != PinCredentialStoreWrite.Written) {
                storageFailed = true
                return@withLock PinProtectionChangeResult.Unavailable
            }
            restoredCredentialRevision = nextRevision
            val refreshed = privacyRepository.refreshPinProtection(
                unlockScopes = updatedScopes,
                nowMonotonicMillis = nowMonotonic,
            )
            if (refreshed !is PrivacyPolicyMutationResult.Updated) {
                storageFailed = true
                return@withLock PinProtectionChangeResult.Unavailable
            }
            PinProtectionChangeResult.Updated
        }
    }

    suspend fun removeVerified(): PinProtectionChangeResult = withContext(storageDispatcher) {
        privacyRepository.initialize()
        transactionMutex.withLock {
            if (storageFailed) return@withLock PinProtectionChangeResult.Unavailable
            val repositoryState = privacyRepository.state.value as? PrivacyRepositoryState.Ready
                ?: return@withLock PinProtectionChangeResult.Unavailable
            if (
                unlockSessions.currentProof(
                    repositoryState.policy.epoch,
                    monotonicClockMillis(),
                ) == null
            ) {
                return@withLock PinProtectionChangeResult.Locked
            }
            if (privacyRepository.removePin() !is PrivacyPolicyMutationResult.Updated) {
                storageFailed = true
                return@withLock PinProtectionChangeResult.Unavailable
            }
            restoredCredentialRevision = null
            rateLimiter.recordSuccess()
            PinProtectionChangeResult.Removed
        }
    }

    private suspend fun completeSuccessfulVerification(
        snapshot: PinCredentialSnapshot,
        upgradedRecord: PinVerifierRecord?,
        nowWallClockMillis: Long,
        nowMonotonicMillis: Long,
        unlockSession: Boolean,
    ): PinUnlockResult {
        val clearedAttempts = rateLimiter.recordSuccess()
        var recordForGrant = snapshot.verifierRecord
        if (snapshot.attemptState != clearedAttempts || upgradedRecord != null) {
            val nextRevision = if (upgradedRecord != null) {
                snapshot.credentialRevision.takeIf { it < MAX_CREDENTIAL_REVISION }?.plus(1L)
                    ?: return PinUnlockResult.CorruptRecord
            } else {
                snapshot.credentialRevision
            }
            val updated = snapshot.copy(
                verifierRecord = upgradedRecord ?: snapshot.verifierRecord,
                attemptState = clearedAttempts,
                credentialRevision = nextRevision,
                upgradedAtWallClockMillis = upgradedRecord?.let { nowWallClockMillis }
                    ?: snapshot.upgradedAtWallClockMillis,
            )
            if (store.write(updated) != PinCredentialStoreWrite.Written) {
                storageFailed = true
                return PinUnlockResult.Unavailable
            }
            restoredCredentialRevision = nextRevision
            if (upgradedRecord != null) {
                recordForGrant = upgradedRecord
                if (privacyRepository.refreshPinProtection() !is PrivacyPolicyMutationResult.Updated) {
                    return PinUnlockResult.Unavailable
                }
            }
        }
        val repositoryState = privacyRepository.state.value as? PrivacyRepositoryState.Ready
            ?: return PinUnlockResult.Unavailable
        if (!unlockSession) return PinUnlockResult.Unlocked(PinUnlockProof(repositoryState.policy.epoch, emptySet()))
        val proof = unlockSessions.unlock(
            privacyEpoch = repositoryState.policy.epoch,
            scopes = recordForGrant.protectedScopes,
            nowMonotonicMillis = nowMonotonicMillis,
        ) ?: return PinUnlockResult.Unavailable
        return PinUnlockResult.Unlocked(proof)
    }

    private companion object {
        const val MAX_CREDENTIAL_REVISION = 1_000_000_000L
    }
}

private fun PinVerifierRecord.withProtectedScopes(scopes: Set<PinScope>): PinVerifierRecord {
    val copiedSalt = salt
    val copiedVerifier = verifier
    return try {
        PinVerifierRecord(
            formatVersion = formatVersion,
            algorithm = algorithm,
            salt = copiedSalt,
            iterations = iterations,
            verifier = copiedVerifier,
            protectedScopes = scopes,
            createdAtWallClockMillis = createdAtWallClockMillis,
        )
    } finally {
        copiedSalt.fill(0)
        copiedVerifier.fill(0)
    }
}
