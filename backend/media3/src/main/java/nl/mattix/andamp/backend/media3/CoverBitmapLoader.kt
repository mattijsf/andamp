// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.AsyncFunction
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Loads the notification's cover for a song stored on the phone.
 *
 * Media3's own loader fetches an address and decodes what comes back, which
 * works for a picture's `https://…` address but not for a local file, whose
 * cover is in its tags or in the phone's library. For a `content://` or
 * `file://` address two places are tried, in this order:
 *
 * 1. the picture in the file's own tags;
 * 2. the thumbnail the phone's library keeps for the song, for a file whose
 *    tags hold no picture.
 *
 * Any other address, and a local one with no picture in either place, goes to
 * Media3's loader.
 *
 * A cover is decoded no larger than [MAX_SIDE] on its longer side, wherever it
 * comes from. A picture in a file's tags or behind an address can be thousands
 * of pixels wide, and a decoded pixel takes four bytes.
 *
 * The work runs on one thread of its own, because reading tags opens and
 * parses the file.
 */
internal class CoverBitmapLoader(
    context: Context,
    private val elsewhere: BitmapLoader = DataSourceBitmapLoader(context.applicationContext, MAX_SIDE),
) : BitmapLoader {
    private val app = context.applicationContext
    private val work: ExecutorService = Executors.newSingleThreadExecutor { job -> Thread(job, "andamp-cover") }

    /** Delegated to the loader that fetches addresses. */
    override fun supportsMimeType(mimeType: String): Boolean = elsewhere.supportsMimeType(mimeType)

    /** Delegated: the bytes are already an encoded picture. */
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = elsewhere.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (!onThisPhone(uri)) return elsewhere.loadBitmap(uri)
        // The uri the file can be read under now, worked out on the worker and used by all
        // three places a cover is looked for; see Media3PlaybackHost.readableUri.
        val readable =
            java.util.concurrent.atomic
                .AtomicReference(uri)
        // a local address with no cover is not reported as a failure: the
        // fetching loader gets it next, and its answer is the caller's
        return Futures.catchingAsync(
            onWorker {
                val at = Media3PlaybackHost.readableUri(uri).also(readable::set)
                inTags(at) ?: fromLibrary(at) ?: error("no cover in $uri")
            },
            Throwable::class.java,
            AsyncFunction { elsewhere.loadBitmap(readable.get()) },
            MoreExecutors.directExecutor(),
        )
    }

    /** The picture the file carries in its own tags, when it carries one. */
    private fun inTags(uri: Uri): Bitmap? {
        val tags = MediaMetadataRetriever()
        return try {
            tags.setDataSource(app, uri)
            tags.embeddedPicture?.let(::decodeBounded)
        } catch (_: RuntimeException) {
            // not audio, not openable, or gone: all mean no picture here
            null
        } finally {
            runCatching { tags.release() }
        }
    }

    /** Decodes an encoded [picture], sampled down until its longer side fits in [MAX_SIDE]. */
    private fun decodeBounded(picture: ByteArray): Bitmap? {
        // the first pass reads the picture's size and decodes no pixels
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(picture, 0, picture.size, options)
        options.inSampleSize = coverSampleSize(options.outWidth, options.outHeight, MAX_SIDE)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(picture, 0, picture.size, options)
    }

    /**
     * The thumbnail the phone's library keeps for this song. Needs API 29 and
     * a `content://` address.
     */
    private fun fromLibrary(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return runCatching { app.contentResolver.loadThumbnail(uri, Size(SIDE, SIDE), null) }.getOrNull()
    }

    private fun onWorker(job: () -> Bitmap): ListenableFuture<Bitmap> {
        val answer = SettableFuture.create<Bitmap>()
        work.execute {
            runCatching(job).fold({ answer.set(it) }, { answer.setException(it) })
        }
        return answer
    }

    private companion object {
        /** The side of the thumbnail asked for, in pixels. */
        const val SIDE = 512

        /** The longer side of a decoded cover, at most, in pixels. */
        const val MAX_SIDE = 1024
    }
}

/**
 * What a picture of [width] by [height] pixels is divided by so that its
 * longer side fits in [max] pixels. A power of two, because the decoder rounds
 * any other number down to one.
 */
internal fun coverSampleSize(
    width: Int,
    height: Int,
    max: Int,
): Int {
    val longer = maxOf(width, height)
    var sample = 1
    // rounded up, as the decoder rounds a side that does not divide evenly
    while ((longer + sample - 1) / sample > max) sample *= 2
    return sample
}

/** A `content://` or `file://` address. */
private fun onThisPhone(uri: Uri): Boolean =
    uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == ContentResolver.SCHEME_FILE
