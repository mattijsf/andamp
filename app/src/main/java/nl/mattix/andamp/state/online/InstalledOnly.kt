// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

/** The skins already on this phone, paged like any other [SkinsSource]. It makes no request. */
class InstalledOnly(
    private val skins: List<OnlineSkin>,
) : SkinsSource {
    override suspend fun page(
        offset: Int,
        count: Int,
    ): SkinsPage =
        SkinsPage(
            total = skins.size,
            offset = offset,
            items = skins.drop(offset).take(count),
        )
}
