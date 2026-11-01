#!/usr/bin/env python3
"""Reject damaged APK contents, including truncated DEX files inside a valid signed ZIP."""
import argparse
import hashlib
import re
import struct
import zipfile
import zlib


def verify_apk(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate ZIP entry names")
        bad = archive.testzip()
        if bad:
            raise ValueError(f"ZIP integrity check failed: {bad}")
        for required in ("AndroidManifest.xml", "resources.arsc", "classes.dex"):
            if required not in names:
                raise ValueError(f"APK is missing {required}")
        dex_names = [name for name in names if re.fullmatch(r"classes(?:\d+)?\.dex", name)]
        expected = {"classes.dex"} | {f"classes{i}.dex" for i in range(2, len(dex_names) + 1)}
        if set(dex_names) != expected:
            raise ValueError("DEX sequence is incomplete")
        for name in dex_names:
            data = archive.read(name)
            if len(data) < 112 or not re.fullmatch(rb"dex\n\d{3}\x00", data[:8]):
                raise ValueError(f"{name}: invalid DEX header")
            declared_size = struct.unpack_from("<I", data, 32)[0]
            if declared_size != len(data):
                raise ValueError(f"{name}: truncated DEX ({len(data)} bytes, header declares {declared_size})")
            if hashlib.sha1(data[32:]).digest() != data[12:32]:
                raise ValueError(f"{name}: DEX SHA-1 does not match its contents")
            if zlib.adler32(data[12:]) & 0xFFFFFFFF != struct.unpack_from("<I", data, 8)[0]:
                raise ValueError(f"{name}: DEX checksum does not match its contents")
    return len(dex_names)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    args = parser.parse_args()
    try:
        count = verify_apk(args.apk)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"APK verification failed: {error}\n")
    print(f"APK verified: ZIP integrity and all {count} DEX files are intact.")
