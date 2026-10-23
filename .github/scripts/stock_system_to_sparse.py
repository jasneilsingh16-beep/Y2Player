"""Turn the Updater package's raw system.img into the sparse image the
Y2Player firmware tools expect.

Updater packages trim trailing zero bytes from raw filesystem images, so the
file can stop short of the ext4 filesystem it holds. The superblock records the
real size; the image is padded back to it (with zeros, which is what was
trimmed) before sparse encoding.
"""
import shutil
import struct
import subprocess
import sys
from pathlib import Path

src, dst = Path(sys.argv[1]), Path(sys.argv[2])
with src.open("rb") as handle:
    handle.seek(1024)
    superblock = handle.read(1024)
blocks_lo, = struct.unpack_from("<I", superblock, 4)
log_block, = struct.unpack_from("<I", superblock, 24)
magic, = struct.unpack_from("<H", superblock, 56)
blocks_hi, = struct.unpack_from("<I", superblock, 0x150)
if magic != 0xEF53:
    sys.exit(f"{src} is neither sparse nor ext4 (magic {magic:#06x})")
block_size = 1024 << log_block
fs_bytes = ((blocks_hi << 32) | blocks_lo) * block_size
size = src.stat().st_size
print(f"ext4: {fs_bytes} bytes ({block_size}-byte blocks); file: {size} bytes")
if size > fs_bytes:
    sys.exit("file is larger than its filesystem; refusing to guess")
raw = dst.with_suffix(".raw")
shutil.copyfile(src, raw)
if size < fs_bytes:
    with raw.open("r+b") as handle:
        handle.truncate(fs_bytes)
    print(f"padded {fs_bytes - size} trailing zero bytes")
subprocess.run(
    [sys.executable, str(Path(__file__).resolve().parents[2] / "tools/firmware/sparse.py"),
     "pack", str(raw), str(dst)],
    check=True,
)
raw.unlink()
