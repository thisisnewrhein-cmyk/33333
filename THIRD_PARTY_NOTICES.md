# Third-party notices

RouteScout's original project code is licensed under **GNU GPL-3.0-only**. See LICENSE.

## Components currently used

### WireGuard Android tunnel library

- Dependency: com.wireguard.android:tunnel:1.0.20260102
- Upstream: https://github.com/WireGuard/wireguard-android
- License: Apache License 2.0
- License text: LICENSES/Apache-2.0.txt

AndroidX, Jetpack Compose, Kotlin, and WorkManager retain their respective upstream notices and licenses.

## Planned component — sing-box/libbox

The sing-box/libbox Android runtime is **not yet bundled** in the current APK.

Candidate version checked on 2026-11-04: **1.14.4**.

- Android packaging repository: https://github.com/singbox-android/libbox
- Release: https://github.com/singbox-android/libbox/releases/tag/1.14.4
- AAR SHA-256: d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465
- Upstream engine: https://github.com/SagerNet/sing-box
- Upstream license: GPL-3.0-or-later, with an additional section 7 term restricting use of the upstream application name and implied affiliation.

Before integration, RouteScout must preserve the applicable upstream copyright/license notices, the additional term and corresponding source/build instructions. RouteScout is an independent project and is not affiliated with or endorsed by SagerNet or the sing-box project.

## Distribution note

Before distributing an APK, include the corresponding source code and build instructions required by all applicable licenses. Do not remove upstream notices from bundled components.
