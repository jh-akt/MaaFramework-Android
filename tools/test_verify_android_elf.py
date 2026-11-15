import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_android_elf import verify, verify_apk, verify_runtime


def elf(alignment=16384, relro_end=None):
    data = bytearray(4096)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, 183)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 2 if relro_end else 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 6, 0, 0, 0, len(data), 32768, alignment)
    if relro_end:
        struct.pack_into("<IIQQQQQQ", data, 120, 0x6474E552, 4, 0, 0, 0, 0, relro_end, 1)
    return bytes(data)


class VerifyAndroidElfTest(unittest.TestCase):
    def test_accepts_aligned_load_and_relro(self):
        verify(elf(relro_end=16384), "aligned")

    def test_rejects_4k_load(self):
        with self.assertRaisesRegex(ValueError, "LOAD"):
            verify(elf(4096), "old")

    def test_rejects_relro_sharing_writable_page(self):
        with self.assertRaisesRegex(ValueError, "RELRO"):
            verify(elf(relro_end=4096), "old")

    def test_rejects_incongruent_load(self):
        data = bytearray(elf())
        struct.pack_into("<Q", data, 64 + 16, 4096)
        with self.assertRaisesRegex(ValueError, "LOAD"):
            verify(data, "misaligned")

    def test_apk_checks_agents_and_nested_python_extensions(self):
        for name in ("assets/bundled_runtime/agent/go-service",
                     "assets/bundled_runtime/python/lib/python3.14/site-packages/pkg/native.so"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                apk = Path(temp) / "test.apk"
                with zipfile.ZipFile(apk, "w") as archive:
                    archive.writestr("lib/arm64-v8a/libGood.so", elf())
                    archive.writestr(name, elf(4096))
                with self.assertRaisesRegex(ValueError, name):
                    verify_apk(apk)

    def test_apk_rejects_non_elf_jni_library(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "test.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libBad.so", b"truncated")
            with self.assertRaisesRegex(ValueError, "ELF64"):
                verify_apk(apk)

    def test_runtime_and_apk_skip_non_native_assets(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "python3").write_bytes(elf())
            (root / "README").write_text("runtime documentation")
            self.assertEqual(1, verify_runtime(root))
            apk = root / "test.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/python3", elf())
                archive.writestr("assets/README", "runtime documentation")
            self.assertEqual(1, verify_apk(apk))


if __name__ == "__main__":
    unittest.main()
