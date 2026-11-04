# Third-party notices

RouteScout's original project code is licensed under **GNU GPL-3.0-only**. See [LICENSE](LICENSE).

## Components currently used

### WireGuard Android tunnel library

- Dependency: `com.wireguard.android:tunnel:1.0.20260102`
- Upstream: https://github.com/WireGuard/wireguard-android
- License: Apache License 2.0
- License text: [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)

The upstream repository identifies its license as Apache-2.0. AndroidX, Jetpack Compose, and WorkManager dependencies retain their respective upstream notices and licenses.

## Planned component — libbox

The sing-box/libbox Android runtime is **not yet bundled** in the current APK. It is the planned multi-protocol engine, subject to a pinned, reproducible build and a completed compatibility review.

The upstream sing-box project is licensed under GPL-3.0-or-later and carries an additional term under GPL section 7:

> No derivative work may use the name or imply association with the sing-box application without prior consent.

If libbox is integrated, RouteScout will preserve the upstream copyright and license notices, the additional term, and the corresponding source/build instructions. RouteScout is an independent project and is not affiliated with or endorsed by SagerNet or the sing-box project.

## Distribution note

Before distributing an APK, include the corresponding source code and build instructions required by the applicable licenses. Do not remove upstream notices from bundled components.
