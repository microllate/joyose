# GPU Profile / QSPM Stage 2 Analysis

## Device runtime profile

Runtime file:
`/data/vendor/gaming/com.tencent.tmgp.sgame-gpu`

Observed size: 332 bytes.

The runtime profile is **not byte-identical** to the JADX asset `8750/profiles/com.tencent.tmgp.sgame-gpu` (asset size 283 bytes). Earlier notes incorrectly described them as identical; this is corrected here.

## Protobuf envelope

The runtime file parses as nested protobuf `Any`-style data:

- outer field 1 contains an inner message
- inner field 1 contains a `type.googleapis.com/GraphicsProfilePrivate` Any
- Any field 2 contains a 244-byte opaque private payload
- inner field 2 contains a 33-byte key/value entry:
  `DisablePrivateProfileData = TRUE`

The private payload begins with:

`c9 0c 49 09 42 95 c4 4a a6 00 ...`

The APK 8750 profile begins with the same general private-profile structure but has a different payload and size. The payload is therefore device/profile-version specific.

## Important interpretation

The payload does not expose plain-text GPU frequency, voltage, KGSL OPP, `max_freq`, or `min_freq` values. Static inspection of the QSPM HIDL implementation also shows profile/file handling APIs such as `getGpuProf()` and `setAppProfile()`, but no direct evidence that this layer writes GPU OPP voltage or frequency.

The current evidence supports this chain:

`Joyose/PowerKeeper -> ProfileManager -> libprofilemanager-jni -> libupdateprof.qti.so -> QSPM HAL -> /data/vendor/gaming/*.gpu -> Qualcomm graphics/profile consumer`

The QSPM HAL should therefore not be modified as a GPU-voltage hook without further evidence.

## Next research target

The remaining useful target is the Qualcomm component that consumes the opaque `GraphicsProfilePrivate` payload and turns it into Adreno driver behavior. The goal is to determine whether any field affects performance hints/rendering behavior versus GPU frequency/voltage/OPP.

No runtime profile modification has been performed.
