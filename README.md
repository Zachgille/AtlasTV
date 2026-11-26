# AtlasTV

A Kotlin media client designed for a television and a directional remote. Browse movies and series, search a local catalog, maintain a watchlist, and resume playback with AndroidX Media3. Supply your own authorized media source.

## Engineering notes

* **Catalog browsing.** Room stores the local catalog. A shared SQL candidate set feeds eight genre shelves per media type. On a physical Onn box, three paired comparisons returned identical shelf ordering and reduced median combined query time from 1,232 ms to 745 ms.
* **Artwork scheduling.** Visible posters take priority over queued prefetch. Compatible requests share a download and decode, with separate disk-cache and network concurrency limits.
* **Playback recovery.** Retry can switch to a configured backup endpoint after eligible failures while retaining the selected episode and playback checkpoint. Authentication and codec errors follow their own recovery paths.

## Build locally

Install JDK 17, Android SDK platform 36 and Python 3. Point Android Studio at the SDK or set ANDROID_HOME. For personal, non-commercial use, review the [IMDb dataset terms](https://developer.imdb.com/non-commercial-datasets/) before preparing the discovery index. The preparation script downloads the data and activates a verified snapshot on your machine.

```shell
python scripts/prepare-imdb-discovery.py
./gradlew testDebugUnitTest assembleDebug
```

On Windows, use gradlew.bat. No provider credentials are required to compile. Enter your own account settings on the device. The repository includes synthetic parser fixtures, Room schemas, JVM tests, and Android instrumentation tests. Private discovery data, account settings, and personal device diagnostics are excluded.
