# GPU Profile / QSPM Analysis

Date: 2026-09-12

## Scope

This note records static analysis of Xiaomi PowerKeeper/Joyose GPU profile handling for the Qualcomm device under test. No runtime settings are modified by this research.

## Confirmed call chain

```text
PowerKeeper/Joyose
    -> com.xiaomi.gpuprofile.manager.ProfileManager
    -> libprofilemanager-jni.so
    -> libupdateprof.qti.so
    -> vendor.qti.qspmhal
    -> Qualcomm profile/performance management
```

`ProfileManager` loads `libprofilemanager-jni` on Qualcomm devices. Its native `SaveProfile`, `SyncProfiles`, and `DeleteProfile` functions are exported from `libprofilemanager-jni.so`.

`InitializeLibrary()` in `libprofilemanager-jni.so` calls `dlopen("libupdateprof.qti.so", RTLD_NOW)` and resolves the `update_profiles` symbol with `dlsym`.

`libupdateprof.qti.so` imports both HIDL and AIDL QSPM HAL interfaces, including `vendor.qti.qspmhal@1.0.so` and `vendor.qti.qspmhal-V1-ndk.so`. It exports `update_profiles`, `process_profiles`, `send_profile`, and `send_profile_aidl`.

## GPU profile format

PowerKeeper's generated protobuf defines a public `GraphicsProfile` containing:

- `privateData` (`google.protobuf.Any`)
- `publicSettings`
- `api`

The shipped GPU profile files are therefore wrappers around an opaque `GraphicsProfilePrivate` payload. For example:

```text
assets/8750/profiles/com.tencent.tmgp.sgame-gpu
assets/8850/profiles/com.tencent.tmgp.sgame-gpu
assets/profiles/8350/com.tencent.tmgp.sgame-gpu
assets/profiles/8450/com.tencent.tmgp.sgame-gpu
assets/profiles/com.tencent.tmgp.sgame-gpu
```

The `Any.type_url` is:

```text
type.googleapis.com/GraphicsProfilePrivate
```

The private payload does not contain readable field names in the shipped APK. Static parsing shows the outer protobuf structure is valid, but the `Any.value` is opaque binary data. The application source does not contain a `GraphicsProfilePrivate.proto` definition.

## Important ProfileManager behavior

`ProfileManager.setVRS()` modifies only these explicit public profile settings:

```text
GLES:
    DisablePrivateProfileData = TRUE/FALSE

Vulkan:
    ro.vendor.qcom.adreno.qgl.DisablePrivateProfileData = TRUE/FALSE
```

This is a rendering/profile-data control, not evidence of a direct GPU OPP or voltage write.

For Qualcomm devices, `ProfileManager.nativeSaveProfile()` ultimately sends the generated profile file to `update_profiles()` in `libupdateprof.qti.so`.

## Current conclusion

The GPU profile path is real and Qualcomm-backed, but the currently available binaries do **not** prove that `com.tencent.tmgp.sgame-gpu` directly sets GPU frequency, voltage, or KGSL OPPs.

The `gpu:5` value observed in `powerkeeper.dfsanalyze` was separately traced to thermal DFS collection data and should not be treated as a GPU performance-level command.

Likewise, the observed Qualcomm `perfLock` hint 4227 is a CPU boost request (`cpu0/cpu4/cpu7` parameters) and should not be disabled as a GPU optimization.

## Recommended next research target

The next useful artifact is the Qualcomm QSPM HAL implementation/interface on the running ROM, especially the service-side implementation and its command definitions. The client library alone exposes generic profile transport functions but not the semantic fields of `GraphicsProfilePrivate`.

Do not modify GPU voltage, max frequency, or PowerKeeper profile contents until those semantics are established. KonaBess already changes the GPU OPP voltage table independently, so an additional unverified profile hook could create conflicting control paths.
