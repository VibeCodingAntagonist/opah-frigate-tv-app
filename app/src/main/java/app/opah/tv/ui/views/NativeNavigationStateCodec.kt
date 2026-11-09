package app.opah.tv.ui.views

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import androidx.recyclerview.widget.LinearLayoutManager
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.ui.ActivitySearchTimeRange
import app.opah.tv.ui.NativeBrowseSelections
import app.opah.tv.ui.NativeHistorySelection
import app.opah.tv.ui.NativeMotionSelection
import app.opah.tv.ui.NativeReviewSelection
import app.opah.tv.ui.NativeSearchSelection
import app.opah.tv.ui.ReviewStatusFilter
import app.opah.tv.ui.ReviewTimeRange
import app.opah.tv.ui.SettingsPage
import app.opah.tv.ui.bounded

/** Only presentation identities, safe filter selections, and scroll positions survive recreation. */
internal data class NativeSavedNavigationState(
    val location: NativeLocation = NativeLocation(),
    val history: List<NativeLocation> = emptyList(),
    val perDestinationLocations: Map<NativeDestination, NativeLocation> = emptyMap(),
    val home: NativeHomeBookmark? = null,
    val browsers: Map<String, NativeMediaBrowserBookmark> = emptyMap(),
    val motion: NativeMotionSearchBookmark? = null,
    val settingsPosition: NativeSettingsPosition? = null,
    val appearancePosition: NativeAppearancePosition? = null,
    val alertSection: NativeAlertSection = NativeAlertSection.ACTIVITY,
    val focus: Map<String, String> = emptyMap(),
    val listScroll: Map<String, Parcelable> = emptyMap(),
    val browse: NativeBrowseSelections? = null,
)

/**
 * A bounded, versioned allowlist. Restored identities are still resolved by surfaces against
 * their current privacy-projected state. Content, text drafts, grants and pending actions never enter it.
 */
internal object NativeNavigationStateCodec {
    private const val VERSION = 1
    private const val MAX_HISTORY = 24
    private const val MAX_BOOKMARKS = 32
    private const val MAX_FOCUS = 64
    private const val MAX_ID_LENGTH = 512
    private const val MAX_SCROLL = 1_000_000

    fun encode(state: NativeSavedNavigationState, context: Context): Bundle = Bundle().apply {
        val scrollStateClass = scrollStateClass(context)
        putInt("version", VERSION)
        putBundle("location", writeLocation(state.location))
        putBundle("history", Bundle().apply {
            val history = state.history.takeLast(MAX_HISTORY)
            putInt("count", history.size)
            history.forEachIndexed { index, location -> putBundle(index.toString(), writeLocation(location)) }
        })
        putBundle("destinations", Bundle().apply {
            state.perDestinationLocations.forEach { (destination, location) ->
                if (location.route.destination == destination) putBundle(destination.name, writeLocation(location))
            }
        })
        state.home?.let { putBundle("home", writeHome(it)) }
        state.browse?.bounded()?.let { putBundle("browse", writeBrowse(it)) }
        putBundle("browsers", Bundle().apply {
            state.browsers.entries.take(MAX_BOOKMARKS).forEach { (key, bookmark) ->
                validId(key)?.let { putBundle(it, writeBrowser(bookmark)) }
            }
        })
        state.motion?.let { motion ->
            putBundle("motion", Bundle().apply {
                putId("focus", motion.focusedKey)
                putInt("position", position(motion.focusedResultPosition))
                putBundle("anchor", writeAnchor(motion.resultAnchor))
                putInt("cameraScroll", position(motion.cameraScrollX))
            })
        }
        state.settingsPosition?.let { settings ->
            putBundle("settingsPosition", Bundle().apply {
                putId("category", settings.categoryKey)
                putId("focus", settings.focusedKey)
                putBoolean("details", settings.focusInDetails)
                putScroll("categories", settings.categoryScroll, scrollStateClass)
                putScroll("detail", settings.detailScroll, scrollStateClass)
            })
        }
        state.appearancePosition?.let { appearance ->
            putBundle("appearancePosition", Bundle().apply {
                putId("focus", appearance.focusedKey)
                putInt("scroll", position(appearance.scrollY))
            })
        }
        putString("alertSection", state.alertSection.name)
        putBundle("focus", Bundle().apply {
            state.focus.entries.take(MAX_FOCUS).forEach { (key, value) ->
                validId(key)?.let { putId(it, value) }
            }
        })
        putBundle("listScroll", Bundle().apply {
            state.listScroll.entries.take(MAX_BOOKMARKS).forEach { (key, value) ->
                validId(key)?.let { putScroll(it, value, scrollStateClass) }
            }
        })
    }

    fun decode(saved: Bundle?, context: Context): NativeSavedNavigationState {
        if (saved == null) return NativeSavedNavigationState()
        saved.classLoader = NativeNavigationStateCodec::class.java.classLoader
        val version = saved.int("version", 0)
        if (version != 0 && version != VERSION) return NativeSavedNavigationState()
        if (version == 0) return decodeLegacy(saved)
        val scrollStateClass = scrollStateClass(context)
        return NativeSavedNavigationState(
            location = saved.bundle("location")?.let(::readLocation) ?: NativeLocation(),
            history = saved.bundle("history")?.let { history ->
                (0 until history.int("count").coerceIn(0, MAX_HISTORY)).mapNotNull { index ->
                    history.bundle(index.toString())?.let(::readLocation)
                }
            }.orEmpty(),
            perDestinationLocations = saved.bundle("destinations")?.let { destinations ->
                NativeDestination.entries.mapNotNull { destination ->
                    destinations.bundle(destination.name)?.let(::readLocation)
                        ?.takeIf { it.route.destination == destination }
                        ?.let { destination to it }
                }.toMap()
            }.orEmpty(),
            home = saved.bundle("home")?.let(::readHome),
            browse = saved.bundle("browse")?.let(::readBrowse)?.bounded(),
            browsers = saved.bundle("browsers")?.let { browsers ->
                browsers.safeKeys(MAX_BOOKMARKS).mapNotNull { key ->
                    browsers.bundle(key)?.let { key to readBrowser(it) }
                }.toMap()
            }.orEmpty(),
            motion = saved.bundle("motion")?.let { motion ->
                NativeMotionSearchBookmark(
                    focusedKey = motion.id("focus"),
                    focusedResultPosition = position(motion.int("position")),
                    resultAnchor = motion.bundle("anchor")?.let(::readAnchor) ?: NativeBrowserScrollAnchor(),
                    cameraScrollX = position(motion.int("cameraScroll")),
                )
            },
            settingsPosition = saved.bundle("settingsPosition")?.let { settings ->
                NativeSettingsPosition(
                    categoryKey = settings.id("category"),
                    focusedKey = settings.id("focus"),
                    focusInDetails = settings.boolean("details"),
                    categoryScroll = settings.scroll("categories", scrollStateClass),
                    detailScroll = settings.scroll("detail", scrollStateClass),
                )
            },
            appearancePosition = saved.bundle("appearancePosition")?.let { appearance ->
                NativeAppearancePosition(appearance.id("focus"), position(appearance.int("scroll")))
            },
            alertSection = enumValue<NativeAlertSection>(saved.id("alertSection")) ?: NativeAlertSection.ACTIVITY,
            focus = readFocus(saved.bundle("focus")),
            listScroll = saved.bundle("listScroll")?.let { scroll ->
                scroll.safeKeys(MAX_BOOKMARKS).mapNotNull { key -> scroll.scroll(key, scrollStateClass)?.let { key to it } }.toMap()
            }.orEmpty(),
        )
    }

    private fun writeBrowse(value: NativeBrowseSelections): Bundle = Bundle().apply {
        putId("profile", value.profileKey)
        value.review?.let { review ->
            putBundle("review", Bundle().apply {
                putString("severity", review.severity?.name ?: "ALL")
                putId("camera", review.cameraId)
                putId("object", review.objectId)
                putId("zone", review.zoneId)
                putString("time", review.timeRange.name)
                putString("status", review.reviewStatus.name)
            })
        }
        value.history?.let { history ->
            putBundle("history", Bundle().apply {
                putId("camera", history.cameraId)
                history.hourStartSeconds?.let { putDouble("hour", it) }
            })
        }
        value.motion?.let { motion ->
            putBundle("motion", Bundle().apply {
                putId("camera", motion.cameraId)
                putInt("region", motion.regionIndex)
            })
        }
        value.search?.let { putBundle("search", writeSearchSelection(it)) }
        value.searchDraft?.let { putBundle("searchDraft", writeSearchSelection(it)) }
    }

    private fun readBrowse(value: Bundle) = NativeBrowseSelections(
        profileKey = value.id("profile"),
        review = value.bundle("review")?.let { review ->
            NativeReviewSelection(
                severity = if (review.id("severity") == "ALL") null
                    else enumValue<ReviewSeverity>(review.id("severity")) ?: ReviewSeverity.ALERT,
                cameraId = review.id("camera"),
                objectId = review.id("object"),
                zoneId = review.id("zone"),
                timeRange = enumValue<ReviewTimeRange>(review.id("time")) ?: ReviewTimeRange.LAST_DAY,
                reviewStatus = enumValue<ReviewStatusFilter>(review.id("status")) ?: ReviewStatusFilter.ALL,
            )
        },
        history = value.bundle("history")?.let { history ->
            NativeHistorySelection(history.id("camera"), history.double("hour"))
        },
        motion = value.bundle("motion")?.let { motion ->
            NativeMotionSelection(motion.id("camera"), motion.int("region", 4))
        },
        search = value.bundle("search")?.let(::readSearchSelection),
        searchDraft = value.bundle("searchDraft")?.let(::readSearchSelection),
    )

    private fun writeSearchSelection(value: NativeSearchSelection) = Bundle().apply {
        putId("camera", value.cameraId)
        putId("object", value.objectId)
        putId("zone", value.zoneId)
        putString("time", value.timeRange.name)
    }

    private fun readSearchSelection(value: Bundle) = NativeSearchSelection(
        cameraId = value.id("camera"),
        objectId = value.id("object"),
        zoneId = value.id("zone"),
        timeRange = enumValue<ActivitySearchTimeRange>(value.id("time")) ?: ActivitySearchTimeRange.ALL,
    )

    private fun writeLocation(location: NativeLocation): Bundle = Bundle().apply {
        val value = location.canonical()
        putString("destination", value.route.destination.name)
        putString("settings", value.route.settingsPage.name)
        putString("connection", value.route.connectionPage.name)
        putString("activity", value.activityPage.name)
        putString("clips", value.clipsPage.name)
        putString("server", value.serverPage.name)
    }

    private fun readLocation(value: Bundle): NativeLocation? {
        val destination = when (val name = value.id("destination")) {
            "CAMERAS" -> NativeDestination.HOME
            "REVIEW" -> NativeDestination.ACTIVITY
            "SAVED" -> NativeDestination.CLIPS
            "INFORMATION", "ABOUT" -> NativeDestination.SETTINGS
            else -> enumValue<NativeDestination>(name) ?: return null
        }
        val settings = if (destination == NativeDestination.SETTINGS) when (value.id("destination")) {
            "INFORMATION" -> SettingsPage.SYSTEM
            "ABOUT" -> SettingsPage.ABOUT
            else -> enumValue<SettingsPage>(value.id("settings")) ?: SettingsPage.MAIN
        } else SettingsPage.MAIN
        return NativeLocation(
            route = NativeRoute(destination, settings,
                if (destination == NativeDestination.CONNECTIONS) enumValue<NativeConnectionPage>(value.id("connection")) ?: NativeConnectionPage.FRIGATE
                else NativeConnectionPage.FRIGATE),
            activityPage = enumValue<NativeActivityPage>(value.id("activity")) ?: NativeActivityPage.NEW,
            clipsPage = enumValue<NativeClipsPage>(value.id("clips")) ?: NativeClipsPage.RECORDINGS,
            serverPage = enumValue<NativeServerPage>(value.id("server")) ?: NativeServerPage.PERFORMANCE,
        ).canonical()
    }

    private fun writeHome(value: NativeHomeBookmark): Bundle = Bundle().apply {
        putString("filter", value.filterKey.takeIf { it in HOME_FILTERS } ?: "all")
        putId("focus", value.focusedKey)
        putId("selected", value.selectedTileKey)
        putId("anchor", value.anchorKey)
        putInt("offset", offset(value.anchorOffset))
    }

    private fun readHome(value: Bundle) = NativeHomeBookmark(
        filterKey = value.id("filter")?.takeIf { it in HOME_FILTERS } ?: "all",
        focusedKey = value.id("focus"),
        selectedTileKey = value.id("selected"),
        anchorKey = value.id("anchor"),
        anchorOffset = offset(value.int("offset")),
    )

    private fun writeBrowser(value: NativeMediaBrowserBookmark): Bundle = Bundle().apply {
        putId("selected", value.selectedKey)
        putInt("position", position(value.selectedPosition))
        putId("focus", value.focusedKey)
        putString("pane", value.pane.name)
        putBundle("list", writeAnchor(value.listAnchor))
        putBundle("detail", writeAnchor(value.detailAnchor))
    }

    private fun readBrowser(value: Bundle) = NativeMediaBrowserBookmark(
        selectedKey = value.id("selected"),
        selectedPosition = position(value.int("position")),
        focusedKey = value.id("focus"),
        pane = enumValue<NativeBrowserPane>(value.id("pane")) ?: NativeBrowserPane.LIST,
        listAnchor = value.bundle("list")?.let(::readAnchor) ?: NativeBrowserScrollAnchor(),
        detailAnchor = value.bundle("detail")?.let(::readAnchor) ?: NativeBrowserScrollAnchor(),
    )

    private fun writeAnchor(value: NativeBrowserScrollAnchor): Bundle = Bundle().apply {
        putId("key", value.key)
        putInt("position", position(value.position))
        putInt("offset", offset(value.offset))
    }

    private fun readAnchor(value: Bundle) = NativeBrowserScrollAnchor(
        key = value.id("key"),
        position = position(value.int("position")),
        offset = offset(value.int("offset")),
    )

    private fun decodeLegacy(value: Bundle) = NativeSavedNavigationState(
        location = readLocation(value) ?: NativeLocation(),
        home = if (value.id("cameraFilter") != null || value.id("cameraFocus") != null) NativeHomeBookmark(
            filterKey = value.id("cameraFilter")?.takeIf { it in HOME_FILTERS } ?: "all",
            focusedKey = value.id("cameraFocus"),
            selectedTileKey = value.id("cameraSelected"),
            anchorKey = value.id("cameraAnchor"),
            anchorOffset = offset(value.int("cameraOffset")),
        ) else null,
        focus = readFocus(value.bundle("focus")),
    )

    private fun readFocus(value: Bundle?): Map<String, String> = value?.let { focus ->
        focus.safeKeys(MAX_FOCUS).mapNotNull { key -> focus.id(key)?.let { key to it } }.toMap()
    }.orEmpty()

    private fun validId(value: String?): String? = value?.takeIf { it.isNotEmpty() && it.length <= MAX_ID_LENGTH }
    private fun position(value: Int): Int = value.coerceIn(0, MAX_SCROLL)
    private fun offset(value: Int): Int = value.coerceIn(-MAX_SCROLL, MAX_SCROLL)
    private inline fun <reified T : Enum<T>> enumValue(name: String?): T? = name?.let { candidate ->
        enumValues<T>().firstOrNull { it.name == candidate }
    }
    private fun Bundle.putId(key: String, value: String?) { validId(value)?.let { putString(key, it) } }
    private fun Bundle.id(key: String): String? = validId(runCatching { getString(key) }.getOrNull())
    private fun Bundle.int(key: String, fallback: Int = 0): Int = runCatching { getInt(key, fallback) }.getOrDefault(fallback)
    private fun Bundle.double(key: String): Double? = runCatching {
        getDouble(key, Double.NaN).takeIf(Double::isFinite)
    }.getOrNull()
    private fun Bundle.boolean(key: String): Boolean = runCatching { getBoolean(key, false) }.getOrDefault(false)
    private fun Bundle.bundle(key: String): Bundle? = runCatching { getBundle(key) }.getOrNull()?.also {
        it.classLoader = NativeNavigationStateCodec::class.java.classLoader
    }
    private fun Bundle.safeKeys(limit: Int): List<String> = runCatching { keySet().asSequence().filter { validId(it) != null }.take(limit).toList() }.getOrDefault(emptyList())

    // Obtain the opaque layout-state type through public API; accept only that exact type.
    private fun scrollStateClass(context: Context): Class<*>? =
        LinearLayoutManager(context).onSaveInstanceState()?.javaClass

    private fun safeScroll(value: Parcelable?, expectedClass: Class<*>?): Parcelable? = value?.takeIf {
        it.javaClass == expectedClass
    }
    private fun Bundle.putScroll(key: String, value: Parcelable?, expectedClass: Class<*>?) {
        safeScroll(value, expectedClass)?.let { putParcelable(key, it) }
    }
    @Suppress("DEPRECATION")
    private fun Bundle.scroll(key: String, expectedClass: Class<*>?): Parcelable? =
        safeScroll(runCatching { getParcelable<Parcelable>(key) }.getOrNull(), expectedClass)

    private val HOME_FILTERS = setOf("all", "favorites", "views")
}
