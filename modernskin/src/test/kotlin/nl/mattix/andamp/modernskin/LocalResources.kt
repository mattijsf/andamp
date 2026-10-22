// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import org.junit.Assume.assumeTrue
import java.net.URL

/**
 * A test resource from the local skin set (`src/test/resources`, never in the repository): the
 * test is skipped where the skins are not present.
 */
internal fun Any.localResource(name: String): URL {
    val url = javaClass.getResource(name)
    assumeTrue("$name is a local skin file, not in the repository", url != null)
    return url!!
}
