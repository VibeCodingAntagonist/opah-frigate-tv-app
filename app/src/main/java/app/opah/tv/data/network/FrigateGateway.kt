package app.opah.tv.data.network

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery

interface FrigateGateway {
    suspend fun login(profile: ConnectionProfile, password: String): FrigateUserProfile
    suspend fun refreshSession(profile: ConnectionProfile): FrigateUserProfile
    suspend fun logout(profile: ConnectionProfile)
    suspend fun getVersion(profile: ConnectionProfile): String
    suspend fun getConfig(profile: ConnectionProfile): String
    suspend fun getStats(profile: ConnectionProfile): String
    suspend fun getRecordingsStorage(profile: ConnectionProfile): String
    suspend fun getGo2RtcStreams(profile: ConnectionProfile): String
    suspend fun getGo2RtcStream(profile: ConnectionProfile, streamName: String): String
    suspend fun getReview(profile: ConnectionProfile, query: ReviewSearchQuery): String
    suspend fun setReviewsViewed(
        profile: ConnectionProfile,
        reviewIds: Set<String>,
        reviewed: Boolean = true,
    )
    suspend fun getRecordings(
        profile: ConnectionProfile,
        camera: String,
        after: Double,
        before: Double,
    ): String
    fun reviewPlaybackUrl(profile: ConnectionProfile, item: ReviewItem): String
}
