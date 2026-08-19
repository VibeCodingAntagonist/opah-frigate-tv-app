package app.opah.tv.data

import app.opah.tv.data.model.ServerVersionCompatibility
import app.opah.tv.data.model.ServerVersionInfo

/** Central compatibility policy for the Frigate API contract Opah consumes. */
class FrigateVersionPolicy(
    private val supportedStableVersions: Set<SemanticVersion> = DEFAULT_SUPPORTED_STABLE_VERSIONS,
    private val supportedExactBuilds: Map<String, SemanticVersion> = DEFAULT_SUPPORTED_EXACT_BUILDS,
) {
    fun evaluate(rawVersion: String): ServerVersionInfo {
        val normalizedVersion = rawVersion.trim().removePrefix("v").lowercase()
        val match = VERSION_PATTERN.find(normalizedVersion)
        val parsed = match?.destructured?.let { (major, minor, patch) ->
            SemanticVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
        val supportedLines = supportedStableVersions + supportedExactBuilds.values
        val compatibility = when {
            parsed == null -> ServerVersionCompatibility.UNKNOWN
            parsed in supportedStableVersions -> ServerVersionCompatibility.SUPPORTED
            supportedExactBuilds[normalizedVersion] == parsed -> ServerVersionCompatibility.SUPPORTED
            supportedLines.any { parsed.major == it.major && parsed.minor == it.minor } ->
                ServerVersionCompatibility.COMPATIBLE_UNVERIFIED
            else -> ServerVersionCompatibility.UNSUPPORTED
        }
        val supportedVersionNames = (
            supportedStableVersions.map(SemanticVersion::display) + supportedExactBuilds.keys
        ).sorted().joinToString(separator = " and ")
        val warning = when (compatibility) {
            ServerVersionCompatibility.SUPPORTED -> null
            ServerVersionCompatibility.COMPATIBLE_UNVERIFIED ->
                "Detected Frigate $rawVersion. Opah is validated against $supportedVersionNames; this patch version is expected to be close but is not yet verified."
            ServerVersionCompatibility.UNSUPPORTED ->
                "Detected Frigate $rawVersion. Opah is currently validated only against the Frigate $supportedVersionNames API contracts."
            ServerVersionCompatibility.UNKNOWN ->
                "Frigate returned an unrecognized version value. Opah is validated against $supportedVersionNames."
        }
        return ServerVersionInfo(
            rawVersion = rawVersion,
            major = parsed?.major,
            minor = parsed?.minor,
            patch = parsed?.patch,
            compatibility = compatibility,
            warning = warning,
        )
    }

    data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) {
        val display: String get() = "$major.$minor.$patch"
    }

    private companion object {
        val DEFAULT_SUPPORTED_STABLE_VERSIONS = setOf(SemanticVersion(0, 17, 2))
        val DEFAULT_SUPPORTED_EXACT_BUILDS = mapOf(
            "0.18.0-344efb6" to SemanticVersion(0, 18, 0),
        )
        val VERSION_PATTERN = Regex("""(?:^|[^0-9])(\d+)\.(\d+)\.(\d+)(?:$|[^0-9])""")
    }
}
