package app.opah.tv.ui

import app.opah.tv.R

internal class DocumentationResourceProviderImpl : DocumentationResourceProvider {
    override fun drawable(resourceName: String): Int = when (resourceName) {
        "docs_camera_entry" -> R.drawable.docs_camera_entry
        "docs_camera_garden" -> R.drawable.docs_camera_garden
        "docs_camera_driveway" -> R.drawable.docs_camera_driveway
        "docs_camera_birdseye" -> R.drawable.docs_camera_birdseye
        else -> error("Unknown documentation resource: $resourceName")
    }
}
