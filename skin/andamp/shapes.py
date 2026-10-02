# SPDX-License-Identifier: GPL-3.0-or-later

"""M3 shape scale, mapped to pixel-art corner masks.

The M3 dp scale (none 0 / medium 12 / large 16 / full) has no meaning at this
canvas size, so it is mapped to corner masks: hand-plotted up to radius 3, a
quarter arc from 4 up. Each entry lists the (x, y) offsets removed from the
corner.
"""

# Offsets cut from a top-left corner, by radius.
CORNER_CUT = {
    0: (),
    1: ((0, 0),),
    2: ((0, 0), (1, 0), (0, 1)),
    3: ((0, 0), (1, 0), (2, 0), (0, 1), (0, 2)),
    4: ((0, 0), (1, 0), (2, 0), (3, 0), (0, 1), (1, 1), (0, 2), (0, 3)),
}

# Ordinal mapping of the M3 shape tokens onto this canvas.
NONE, CHAMFER, ROUND, PILL = 0, 1, 2, 3
MEDIUM, LARGE, FULL = CHAMFER, ROUND, PILL


ALL = ("tl", "tr", "bl", "br")
TOP = ("tl", "tr")
BOTTOM = ("bl", "br")
LEFT = ("tl", "bl")
RIGHT = ("tr", "br")


def cut_offsets(w, h, radius, corners=ALL, clamp=True):
    """Offsets removed from a w*h box for the given radius and corner set.

    `radius` is either one value for all four corners or a {corner: radius}
    mapping. A connected button group uses the mapping: the full radius on the
    outside corners, a small one where a segment meets its neighbor.
    """
    corners = set(ALL if corners is None else corners)
    if not isinstance(radius, dict):
        radius = dict.fromkeys(ALL, radius)
    # A radius is clamped to half the box's shorter side, so a theme can ask
    # for `full` everywhere.
    #
    # `clamp=False` is for a box that is a horizontal slice of a taller window:
    # a 14px title bar follows the window's corner, the one REGION.TXT traces.
    limit = min(w, h) // 2 if clamp else max(w, h)
    out = []
    for corner in ALL:
        if corner not in corners:
            continue
        vertical, horizontal = corner
        for dx, dy in CORNER_CUT[max(0, min(radius.get(corner, 0), limit))]:
            out.append((dx if horizontal == "l" else w - 1 - dx,
                        dy if vertical == "t" else h - 1 - dy))
    return out


def outline(w, h, radius, corners=ALL, clamp=True):
    """Pixels forming the 1px silhouette outline of the rounded box."""
    cut = set(cut_offsets(w, h, radius, corners, clamp))

    def inside(i, j):
        return 0 <= i < w and 0 <= j < h and (i, j) not in cut

    return [
        (i, j)
        for j in range(h)
        for i in range(w)
        if inside(i, j)
        and not (inside(i - 1, j) and inside(i + 1, j)
                 and inside(i, j - 1) and inside(i, j + 1))
    ]


def _arc_cut(r):
    """Cells outside a quarter arc of radius r. Radii 1-3 are hand-plotted in
    CORNER_CUT; from 4 up the arc is computed."""
    return tuple(
        (x, y)
        for y in range(r)
        for x in range(r)
        if (x + 0.5 - r) ** 2 + (y + 0.5 - r) ** 2 > r * r
    )


for _r in range(4, 41):
    CORNER_CUT[_r] = _arc_cut(_r)


def disc(d):
    """Filled pixel circle of diameter d, as an icon mask: the play button and
    the window-button state layers."""
    r = (d - 1) / 2
    rows = []
    for y in range(d):
        row = "".join(
            "#" if (x - r) ** 2 + (y - r) ** 2 <= (r + 0.35) ** 2 else "."
            for x in range(d)
        )
        rows.append(row)
    return rows


def corner_polygon(radius):
    """The top-left corner alone, from the left edge round to the top.

    REGION.TXT's `[Corners]` section carries this for the playlist and the
    plug-in window, which resize; Andamp mirrors it into the other three
    corners. It is the staircase `outline_polygon` uses.
    """
    cut = set(CORNER_CUT[radius])
    # cx[y] = how many cells are cut from the left of row y, top-left corner.
    cx = [sum(1 for x in range(radius) if (x, y) in cut) for y in range(radius)]
    pts = [(0, radius)]
    for y in range(radius - 1, -1, -1):
        for p in ((cx[y], y + 1), (cx[y], y)):
            if pts[-1] != p:
                pts.append(p)
    return pts


def outline_polygon(w, h, radius):
    """Clockwise vertex list tracing the silhouette of a rounded box, from the
    CORNER_CUT mask the canvas draws with, so REGION.TXT and the drawn corner
    agree.
    """
    radius = max(0, min(radius, min(w, h) // 2))
    top_left = corner_polygon(radius)
    top_right = [(w - x, y) for x, y in reversed(top_left)]
    bottom_right = [(w - x, h - y) for x, y in top_left]
    bottom_left = [(x, h - y) for x, y in reversed(top_left)]

    pts, out = top_left + top_right + bottom_right + bottom_left, []
    for p in pts:
        if not out or out[-1] != p:
            out.append(p)
    if len(out) > 1 and out[0] == out[-1]:
        out.pop()
    return _drop_collinear(out)


def _drop_collinear(pts):
    """Drop vertices that lie on a straight run. Winamp reads PointList from a
    single line."""
    out = []
    n = len(pts)
    for i, p in enumerate(pts):
        a, b = pts[i - 1], pts[(i + 1) % n]
        if (p[0] - a[0]) * (b[1] - p[1]) != (p[1] - a[1]) * (b[0] - p[0]):
            out.append(p)
    return out or pts
