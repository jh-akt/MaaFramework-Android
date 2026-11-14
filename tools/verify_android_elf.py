#!/usr/bin/env python3
"""Check packaged 64-bit JNI ELF LOAD and RELRO layout for 16 KiB pages.

Reference: https://developer.android.com/guide/practices/page-sizes
This does not replace execution on a real 16 KiB device.
"""
import argparse
import struct
import zipfile
from pathlib import Path

PAGE = 16384


def verify(data: bytes, name: str) -> None:
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"{name}: expected little-endian ELF64")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size < 56 or offset + size * count > len(data):
        raise ValueError(f"{name}: invalid program header table")
    segments = [struct.unpack_from("<IIQQQQQQ", data, offset + i * size) for i in range(count)]
    loads = [p for p in segments if p[0] == 1]
    if not loads:
        raise ValueError(f"{name}: no LOAD segments")
    for p in loads:
        if p[7] < PAGE or p[2] % PAGE != p[3] % PAGE:
            raise ValueError(f"{name}: LOAD offset={p[2]:#x}, vaddr={p[3]:#x}, align={p[7]:#x} is not 16 KiB compatible")
    for relro in (p for p in segments if p[0] == 0x6474E552):
        end = relro[3] + relro[6]
        separate_writable_page = any(p[3] <= relro[3] and p[3] + p[6] == end for p in loads)
        if end % PAGE and not separate_writable_page:
            raise ValueError(f"{name}: writable data shares an unaligned RELRO tail page")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="*", type=Path)
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    checked = 0
    for path in args.files:
        verify(path.read_bytes(), str(path))
        checked += 1
    if args.apk:
        with zipfile.ZipFile(args.apk) as apk:
            for name in apk.namelist():
                if name.startswith(("lib/arm64-v8a/", "lib/x86_64/")) and name.endswith(".so"):
                    verify(apk.read(name), name)
                    checked += 1
    if not checked:
        parser.error("no 64-bit ELF files found")
    print(f"Verified 16 KiB LOAD and RELRO layout for {checked} ELF files")


if __name__ == "__main__":
    main()
