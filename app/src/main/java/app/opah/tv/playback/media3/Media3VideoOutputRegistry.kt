@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.opah.tv.playback.media3

import androidx.media3.common.Player
import app.opah.tv.playback.compatibility.PlaybackAttemptId
import java.util.LinkedHashSet

/** Non-blocking main-looper adapter implemented by the UI surface owner. */
internal interface Media3VideoOutputTarget {
    fun attach(
        player: Player,
        outputGeneration: Long,
    )

    /**
     * Detach the exact generation and synchronously replace any retained frame with a non-sensitive
     * surface. Return true only after that concealment is authoritative for this generation.
     */
    fun detachAndHide(
        player: Player,
        outputGeneration: Long,
    ): Boolean
}

/** Bounded process seam shared by runner/UI composition and the Media3 backend. */
internal interface Media3VideoOutputRegistry {
    /** Register the intended process output before executing any runner start command. */
    fun register(target: Media3VideoOutputTarget): Media3VideoOutputRegistrationResult

    fun unregister(registration: Media3VideoOutputRegistration): Boolean

    fun claim(attemptId: PlaybackAttemptId): Media3VideoOutputClaimResult

    fun release(attachment: Media3VideoOutputAttachment): Boolean

    /** Atomically reserve an empty slot; never replace a pending RTSP registration. */
    fun acquireExclusive(target: Media3VideoOutputTarget, attemptId: PlaybackAttemptId): ExclusiveVideoOutputLease?
}

/**
 * Constant-space process output slot. The UI registers its intended target before runner command
 * execution; [claim] then binds that target to the exact attempt. Replacements are allowed only
 * when no attempt is attached, and released attachment identities are retained in a bounded LRU
 * so late callbacks cannot affect a later attempt.
 */
internal class BoundedMedia3VideoOutputRegistry(
    private val maximumReleasedAttachments: Int = DEFAULT_MAX_RELEASED_ATTACHMENTS,
) : Media3VideoOutputRegistry {
    init {
        require(maximumReleasedAttachments > 0) { "Video output history must be positive" }
    }

    private val lock = Any()
    private var registration: RegistrationRecord? = null
    private var activeAttachment: Media3VideoOutputAttachment? = null
    private val releasedAttachments = LinkedHashSet<AttachmentIdentity>()
    private var nextGeneration = 1L
    private var generationExhausted = false

    override fun acquireExclusive(
        target: Media3VideoOutputTarget,
        attemptId: PlaybackAttemptId,
    ): ExclusiveVideoOutputLease? = synchronized(lock) {
        if (registration != null || activeAttachment != null) return@synchronized null
        val registrationGeneration = allocateGenerationLocked() ?: return@synchronized null
        val attachmentGeneration = allocateGenerationLocked() ?: return@synchronized null
        val record = RegistrationRecord(registrationGeneration, target)
        val attachment = Media3VideoOutputAttachment(attemptId, attachmentGeneration, target)
        registration = record
        activeAttachment = attachment
        ExclusiveVideoOutputLease(attachment) {
            synchronized(lock) {
                if (registration !== record || activeAttachment !== attachment) false
                else {
                    release(attachment)
                    registration = null
                    true
                }
            }
        }
    }

    override fun register(target: Media3VideoOutputTarget): Media3VideoOutputRegistrationResult =
        synchronized(lock) {
        if (activeAttachment != null) {
            return@synchronized Media3VideoOutputRegistrationResult.RejectedClaimed
        }
        val generation = allocateGenerationLocked()
            ?: return@synchronized Media3VideoOutputRegistrationResult.RejectedGenerationExhausted
        registration = RegistrationRecord(
            registrationGeneration = generation,
            target = target,
        )
        Media3VideoOutputRegistrationResult.Registered(
            Media3VideoOutputRegistration(generation),
        )
    }

    override fun unregister(registration: Media3VideoOutputRegistration): Boolean =
        synchronized(lock) {
            val record = this.registration ?: return@synchronized true
            if (
                record.registrationGeneration != registration.generation ||
                activeAttachment != null
            ) {
                return@synchronized false
            }
            this.registration = null
            true
        }

    override fun claim(attemptId: PlaybackAttemptId): Media3VideoOutputClaimResult =
        synchronized(lock) {
            val record = registration
                ?: return@synchronized Media3VideoOutputClaimResult.Missing
            if (activeAttachment != null) {
                return@synchronized Media3VideoOutputClaimResult.AlreadyClaimed
            }
            val generation = allocateGenerationLocked()
                ?: return@synchronized Media3VideoOutputClaimResult.GenerationExhausted
            val attachment = Media3VideoOutputAttachment(
                attemptId = attemptId,
                generation = generation,
                target = record.target,
            )
            activeAttachment = attachment
            Media3VideoOutputClaimResult.Claimed(attachment)
        }

    override fun release(attachment: Media3VideoOutputAttachment): Boolean = synchronized(lock) {
        val identity = attachment.identity()
        if (identity in releasedAttachments) return@synchronized true
        val active = activeAttachment ?: return@synchronized false
        if (
            active.attemptId != attachment.attemptId ||
            active.generation != attachment.generation ||
            active.target !== attachment.target
        ) {
            return@synchronized false
        }
        activeAttachment = null
        releasedAttachments += identity
        while (releasedAttachments.size > maximumReleasedAttachments) {
            releasedAttachments.remove(releasedAttachments.iterator().next())
        }
        true
    }

    internal fun snapshotForTest(): Media3VideoOutputRegistrySnapshot = synchronized(lock) {
        Media3VideoOutputRegistrySnapshot(
            registrationGeneration = registration?.registrationGeneration,
            claimedAttempt = activeAttachment?.attemptId,
            attachmentGeneration = activeAttachment?.generation,
            releasedAttachmentCount = releasedAttachments.size,
            generationExhausted = generationExhausted,
        )
    }

    private fun allocateGenerationLocked(): Long? {
        if (generationExhausted || nextGeneration == Long.MAX_VALUE) {
            generationExhausted = true
            return null
        }
        return nextGeneration++
    }

    private class RegistrationRecord(
        val registrationGeneration: Long,
        val target: Media3VideoOutputTarget,
    )
}

internal class Media3VideoOutputRegistration(
    val generation: Long,
) {
    init {
        require(generation > 0)
    }

    override fun toString(): String = "Media3VideoOutputRegistration([redacted])"
}

internal class Media3VideoOutputAttachment(
    val attemptId: PlaybackAttemptId,
    val generation: Long,
    internal val target: Media3VideoOutputTarget,
) {
    init {
        require(generation > 0)
    }

    override fun toString(): String = "Media3VideoOutputAttachment([redacted])"
}

internal sealed interface Media3VideoOutputRegistrationResult {
    data class Registered(
        val registration: Media3VideoOutputRegistration,
    ) : Media3VideoOutputRegistrationResult

    data object RejectedClaimed : Media3VideoOutputRegistrationResult

    data object RejectedGenerationExhausted : Media3VideoOutputRegistrationResult
}

internal sealed interface Media3VideoOutputClaimResult {
    data class Claimed(
        val attachment: Media3VideoOutputAttachment,
    ) : Media3VideoOutputClaimResult

    data object Missing : Media3VideoOutputClaimResult

    data object AlreadyClaimed : Media3VideoOutputClaimResult

    data object GenerationExhausted : Media3VideoOutputClaimResult
}

internal data class Media3VideoOutputRegistrySnapshot(
    val registrationGeneration: Long?,
    val claimedAttempt: PlaybackAttemptId?,
    val attachmentGeneration: Long?,
    val releasedAttachmentCount: Int,
    val generationExhausted: Boolean,
)

/** Default process instance; composition may inject another bounded instance for deterministic tests. */
internal object ProcessMedia3VideoOutputRegistry {
    val instance: Media3VideoOutputRegistry = BoundedMedia3VideoOutputRegistry()
}

private data class AttachmentIdentity(
    val attemptId: PlaybackAttemptId,
    val generation: Long,
)

private fun Media3VideoOutputAttachment.identity() = AttachmentIdentity(attemptId, generation)

private const val DEFAULT_MAX_RELEASED_ATTACHMENTS = 64
