# Third-party notices

Opah depends on open-source software. Direct runtime dependencies are listed
below; transitive dependency details are available from Gradle's dependency
report for the exact release revision.

Users do not need to install these projects separately. They are included in
Opah where needed.

| Project | Use | License |
| --- | --- | --- |
| AndroidX Core, Activity, Lifecycle, DataStore, ProfileInstaller | Android application foundation | Apache License 2.0 |
| Jetpack Compose and Compose for TV | User interface | Apache License 2.0 |
| AndroidX Media3 | RTSP/HLS playback and UI | Apache License 2.0 |
| Kotlin and kotlinx.coroutines/serialization | Language and concurrency/JSON runtime | Apache License 2.0 |
| OkHttp | HTTP transport | Apache License 2.0 |

Test-only dependencies include JUnit 4 (Eclipse Public License 1.0) and AndroidX
test libraries (Apache License 2.0). The Gradle wrapper, Android Gradle Plugin,
and build tools are build-time components and retain their respective licenses.

This notice is informational and does not replace the license text provided by
each project.
