// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import nl.mattix.andamp.state.online.MuseumHttp
import okhttp3.Dispatcher
import okio.FileSystem

/**
 * How museum thumbnails are fetched and kept: a memory cache for what was just seen and a disk
 * cache, capped at [DISK_BYTES], for earlier visits.
 */
object SkinThumbnails {
    private var ahead: ImageLoader? = null

    /**
     * A second loader, for prefetching. It shares both caches with the first one and has its own
     * dispatcher, [AHEAD_AT_ONCE] requests wide. Coil has no notion of priority, and without this a
     * prefetch in flight would sit in the same queue as the tiles on screen.
     */
    fun ahead(context: Context): ImageLoader =
        ahead ?: run {
            val main = SingletonImageLoader.get(context)
            ImageLoader
                .Builder(context)
                .memoryCache { main.memoryCache }
                .diskCache { main.diskCache }
                .components {
                    add(
                        OkHttpNetworkFetcherFactory(
                            callFactory = {
                                MuseumHttp.client
                                    .newBuilder()
                                    .dispatcher(Dispatcher().apply { maxRequestsPerHost = AHEAD_AT_ONCE })
                                    .build()
                            },
                        ),
                    )
                }.build()
                .also { ahead = it }
        }

    fun install(context: Context) {
        SingletonImageLoader.setSafe { app ->
            ImageLoader
                .Builder(app)
                // the same client the catalog uses: one connection pool for the whole browser, and
                // its cap on requests per host
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { MuseumHttp.client })) }
                .memoryCache {
                    MemoryCache
                        .Builder()
                        .maxSizePercent(app, MEMORY_SHARE)
                        .build()
                }.diskCache {
                    DiskCache
                        .Builder()
                        .fileSystem(FileSystem.SYSTEM)
                        .directory(context.cacheDir.resolve(CACHE_DIR))
                        .maxSizeBytes(DISK_BYTES)
                        .build()
                }.crossfade(true)
                .build()
        }
    }

    private const val AHEAD_AT_ONCE = 2
    private const val MEMORY_SHARE = 0.2
    private const val DISK_BYTES = 96L * 1024 * 1024
    private const val CACHE_DIR = "skin-thumbnails"
}
