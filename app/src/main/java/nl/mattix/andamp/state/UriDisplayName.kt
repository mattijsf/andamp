// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * The file name a SAF uri shows to people. `lastPathSegment` is not that: on the Downloads
 * provider it is an opaque document id such as "msf:19". It is the fallback for uris that
 * do not answer the query.
 */
fun Context.displayNameOf(uri: Uri): String? {
    // a provider may throw (a revoked grant, a dead remote process); the fallback is used then
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && column >= 0) {
                cursor.getString(column)?.let { return it }
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
}
