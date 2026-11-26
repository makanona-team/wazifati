"""يفحص ملف APK بعد البناء: المكتبات التي يحتاجها دخول Google يجب أن تكون معرّفة داخل ملفات dex.
(3.0.4: اكتشفنا أن androidx.core كانت مفقودة منذ 1.1 فانهار الدخول على أندرويد 13 وأقدم.)"""
import struct
import sys
import zipfile

REQUIRED = [
    "Landroidx/core/app/ComponentActivity;",
    "Landroidx/activity/ComponentActivity;",
    "Landroidx/fragment/app/FragmentActivity;",
    "Lcom/google/android/gms/common/GoogleApiAvailability;",
    "Landroidx/credentials/CredentialManager;",
    "Landroidx/credentials/playservices/CredentialProviderPlayServicesImpl;",
]


def uleb(b, o):
    r = s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7f) << s
        s += 7
        if x < 0x80:
            return r, o


def defined(apk):
    out = set()
    z = zipfile.ZipFile(apk)
    for f in z.namelist():
        if not (f.startswith("classes") and f.endswith(".dex")):
            continue
        b = z.read(f)
        so = struct.unpack_from("<I", b, 0x3C)[0]
        to = struct.unpack_from("<I", b, 0x44)[0]
        cdz, cdo = struct.unpack_from("<II", b, 0x60)
        for i in range(cdz):
            ci = struct.unpack_from("<I", b, cdo + 32 * i)[0]
            si = struct.unpack_from("<I", b, to + 4 * ci)[0]
            off = struct.unpack_from("<I", b, so + 4 * si)[0]
            _, o = uleb(b, off)
            out.add(b[o:b.index(b"\0", o)].decode("utf8", "replace"))
    return out


if __name__ == "__main__":
    d = defined(sys.argv[1])
    missing = [c for c in REQUIRED if c not in d]
    if missing:
        print("❌ مكتبات ناقصة داخل التطبيق:\n  " + "\n  ".join(missing))
        sys.exit(1)
    print(f"✅ فحص المكتبات: {len(REQUIRED)} أساسية موجودة ({len(d)} صنفاً)")
