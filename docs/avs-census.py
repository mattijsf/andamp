# SPDX-License-Identifier: GPL-3.0-or-later

"""Counts which AVS components a corpus of presets uses.

Run it against a directory of presets.

The component-id table is read out of grandchild/AVS-File-Decoder (MIT) at run
time, so the census uses the same table as the format description.

    git clone --depth 1 https://github.com/grandchild/AVS-File-Decoder
    python3 docs/avs-census.py AVS-File-Decoder ~/avs-presets

Prints per-component usage, and how many presets are fully covered by the top
N components: a preset runs only when every component it uses is supported.
"""
import re, struct, sys
from collections import Counter
from pathlib import Path

DEC = Path(sys.argv[1])          # AVS-File-Decoder checkout
ROOT = Path(sys.argv[2])         # directory of .avs files

src = (DEC / "src/lib/components.ts").read_text()
builtin_src = src[src.index("const builtin"):src.index("const dll")]
BUILTIN = {}
for m in re.finditer(r"name:\s*'([^']+)',[^}]*?code:\s*(0x[0-9a-fA-F]+)", builtin_src, re.S):
    BUILTIN[int(m.group(2), 16)] = m.group(1)

HEADER_LEN = 25
BUILTIN_MAX = 16384
EL_CODE = 0xFFFFFFFE
# The 2.8+ Effect List marker, 36 bytes: the APE code, then the name padded to
# a 32-byte field. The code block's length follows it.
EL28 = bytes([0x00,0x40,0x00,0x00]) + b"AVS 2.8+ Effect List Config" + bytes(5)

def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]

def walk(blob, out, depth=0):
    fp = 0
    while fp <= len(blob) - 8:
        code = u32(blob, fp)
        is_dll = code != EL_CODE and code >= BUILTIN_MAX
        size = u32(blob, fp + 4 + (32 if is_dll else 0))
        body = fp + 8 + (32 if is_dll else 0)
        if body + size > len(blob):
            raise ValueError("component overruns the blob")
        if is_dll:
            name = blob[fp + 4: fp + 36].split(b"\x00")[0].decode("latin-1")
            out.append(("APE: " + name, depth))
        elif code == EL_CODE:
            out.append(("Effect List", depth))
            walk_effect_list(blob, body, size, out, depth + 1)
        else:
            out.append((BUILTIN.get(code, f"Unknown({code})"), depth))
        fp = body + size

def walk_effect_list(blob, offset, size, out, depth):
    modebit = blob[offset] & 0x80
    config_size = (blob[offset + 4] if modebit else blob[offset]) + 1
    content = offset + config_size
    if blob[content:content + len(EL28)] == EL28:
        code_off = content + len(EL28)
        content = code_off + 4 + u32(blob, code_off)
    walk(blob[content: offset + size], out, depth)

files = sorted(p for p in ROOT.rglob("*") if p.suffix.lower() == ".avs")
uses, per_preset, failed = Counter(), [], []
for path in files:
    blob = path.read_bytes()
    if not blob.startswith(b"Nullsoft AVS Preset 0."):
        failed.append((path.name, "not an AVS preset"))
        continue
    found = []
    try:
        walk(blob[HEADER_LEN:], found)
    except Exception as e:  # counted as unparsed
        failed.append((path.name, str(e)))
        continue
    names = {n for n, _ in found}
    per_preset.append((path.name, names))
    uses.update(names)

print(f"presets: {len(files)}   parsed: {len(per_preset)}   unparsed: {len(failed)}")
for name, why in failed[:5]:
    print(f"  ! {name}: {why}")
print()
print(f"{'component':34} {'presets':>7} {'share':>7}")
n = len(per_preset)
for name, count in uses.most_common():
    print(f"{name:34} {count:7} {count / n:6.1%}")

print("\ncumulative coverage: presets fully renderable with the top N components")
order = [name for name, _ in uses.most_common()]
for k in range(1, len(order) + 1):
    top = set(order[:k])
    covered = sum(1 for _, names in per_preset if names <= top)
    print(f"  top {k:2} ({order[k-1]:30}) -> {covered:4}/{n} = {covered / n:5.1%}")
