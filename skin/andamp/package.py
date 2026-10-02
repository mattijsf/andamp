# SPDX-License-Identifier: GPL-3.0-or-later

"""Deterministic .wsz writer.

A zip carries timestamps and ordering, so every entry is written with a fixed
1980-01-01 stamp in sorted order. Members sit at the archive root: classic
Winamp builds do not look inside folders.
"""

import zipfile


FIXED_DATE = (1980, 1, 1, 0, 0, 0)


def write(path, members: dict):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name in sorted(members):
            info = zipfile.ZipInfo(name, date_time=FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            info.create_system = 0
            z.writestr(info, members[name])
