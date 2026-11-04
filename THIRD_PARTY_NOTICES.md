# Third-party notices

RouteScout's original project code is licensed under **GNU GPL-3.0-only**. See [LICENSE](LICENSE).

## WireGuard Android tunnel library

- Dependency: \`com.wireguard.android:tunnel:1.0.20260102\`
- Upstream: https://github.com/WireGuard/wireguard-android
- License: Apache License 2.0
- License text: [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)

## sing-box / libbox Android runtime

The Android runtime is bundled through the pinned dependency:

- Dependency: \`com.github.singbox-android:libbox:1.14.4\`
- Android packaging source: https://github.com/singbox-android/libbox/tree/1.14.4
- AAR release: https://github.com/singbox-android/libbox/releases/tag/1.14.4
- Release asset: \`libbox.aar\`
- Size reported by GitHub: 118,293,410 bytes
- SHA-256 reported by GitHub: \`d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465\`
- Upstream engine release: https://github.com/SagerNet/sing-box/releases/tag/v1.14.4

The AAR is resolved by Gradle and verified by the \`verifyLibboxAar\` task before \`preBuild\`. It is not committed as a binary file to this repository.

### Licenses and attribution

- The libbox Android packaging repository supplies a GPL-3.0-or-later license. A copy is preserved at [LICENSES/GPL-3.0-or-later.txt](LICENSES/GPL-3.0-or-later.txt).
- The upstream sing-box engine is GPL-3.0-or-later and carries an additional notice: derivative works may not use the name of the upstream application or imply association with it without prior consent. The exact upstream license text is preserved at [LICENSES/sing-box-LICENSE.txt](LICENSES/sing-box-LICENSE.txt).

RouteScout is an independent project. It does not use sing-box or SagerNet as its product name and is not affiliated with or endorsed by either project.

## Other dependencies

AndroidX, Jetpack Compose, Kotlin, WorkManager, and other Gradle dependencies retain their respective upstream notices and licenses. Their license texts are distributed with their respective source/artifacts.

## Distribution note

Before distributing an APK, include the corresponding source code and build instructions required by all applicable licenses, preserve upstream notices and license terms, and provide the required source for the exact bundled native runtime. Do not remove upstream copyright or attribution notices.
