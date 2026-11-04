# RouteScout — research decisions and implementation audit

**Review date:** 2026-11-04  
**Scope:** Android MVP branch \`codex/routescout-mobile\`

## 1. Engine choice

**Decision:** use sing-box/libbox as the embedded multi-protocol engine, while retaining the existing WireGuard GoBackend during the migration.

The sing-box 1.14.4 Android AAR is published by the separate \`singbox-android/libbox\` packaging repository. GitHub release metadata reports:

- Release: https://github.com/singbox-android/libbox/releases/tag/1.14.4
- Published: 2026-10-25
- Asset: \`libbox.aar\`
- Size: 118,293,410 bytes
- SHA-256: \`d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465\`

The Gradle dependency is pinned to \`com.github.singbox-android:libbox:1.14.4\`. The \`verifyLibboxAar\` task calculates the resolved artifact SHA-256 and compares it with the release metadata before \`preBuild\`.

Upstream sing-box release: https://github.com/SagerNet/sing-box/releases/tag/v1.14.4

**Confidence:** high that the tagged release and asset metadata exist; CI must still prove that the JitPack-resolved AAR is byte-for-byte the same artifact.

## 2. Why the integration has its own Android VPN service

The libbox Android runtime requires an Android platform bridge for TUN creation, socket protection, network-interface observation, and lifecycle management. The reference Android implementation demonstrates this integration pattern:

https://github.com/SagerNet/sing-box-for-android

RouteScout now contains:

- \`RouteScoutVpnService\`, a foreground \`VpnService\`;
- \`RouteScoutPlatformInterface\`, implementing the required libbox platform bridge;
- a command-server handler with root-only and SSH-agent capabilities disabled;
- a TUN descriptor lifecycle and conservative shutdown marker;
- a background HTTPS data-path monitor.

**Migration caveat:** WireGuard still uses the existing GoBackend VPN service. The coordinator serializes the two backends, but they have not yet been consolidated into one service implementation. This is an explicit intermediate state, not a completed single-service migration.

Android references:
- https://developer.android.com/develop/connectivity/vpn
- https://developer.android.com/develop/background-work/services/foreground-services

## 3. Supported protocols and profile model

The embedded engine accepts sing-box JSON profiles with a TUN inbound and supported proxy outbounds:

- Shadowsocks
- VMess
- VLESS
- Trojan
- Hysteria2
- TUIC
- AnyTLS

WireGuard continues through GoBackend. OpenVPN remains an external-client handoff; it is not represented as an embedded sing-box transport.

The validator rejects proxy-only configurations, unsupported final outbounds, and direct/block/DNS final routes. It enables:

- full IPv4 and IPv6 default routes;
- a dual-stack TUN address if the profile omitted an address family;
- DNS hijacking;
- automatic underlying-interface detection to prevent tunnel loops.

It also rejects route exclusions that cover all IPv4 or all IPv6, unconditional direct route rules, and simultaneous app allowlist/blocklist settings because Android's VPN builder cannot apply both in one session. If a sing-box allowlist is used, RouteScout adds its own package so its VPN-bound HTTPS verification can run; an explicit exclusion of RouteScout is rejected.

The normalizer intentionally enforces RouteScout's full-device default. Users should be told that imported route behaviour can be changed. It does not make a claim that every imported rule set is safe or leak-free.

**Confidence:** high for the code-level normalization rules; runtime route correctness still requires device tests.

## 4. Transport names are not resilience guarantees

- WireGuard depends on reachable UDP endpoints and server configuration.
- Hysteria2 and TUIC also depend on UDP/QUIC reachability and are not universal fallbacks.
- sing-box \`selector\`/\`urltest\` groups can perform profile-defined selection, but RouteScout does not yet own a measured multi-provider failover catalogue.
- OpenVPN remains external to the current embedded engine.
- No protocol is described as invisible to DPI or universally unblockable.

**Confidence:** high for these architectural limitations; success against any particular operator filter remains low until measured on that network.

## 5. Self-diagnostics and report privacy

The full diagnostic run records Android network capabilities, DNS server observations, the existing DNS/HTTPS/public-IP/site checks, and five repeated HTTPS response-time samples.

The shareable JSON report:

- includes stable finding codes, severity, and confidence;
- masks public IP, DNS addresses, and VPN endpoint by default;
- includes exact network data only after an explicit opt-in;
- never includes profile text, private keys, passwords, UUIDs, tokens, SSID/BSSID, location, serial numbers, Android IDs, or raw libbox logs;
- is created locally and shared only through Android's Sharesheet.

The report explicitly states that HTTPS failure ratio is not ICMP packet loss, HTTPS timing variation is not a formal jitter test, and DNS server observations do not prove absence of DNS leaks.

**Confidence:** high for the report's code-level privacy controls; the final shared file still needs a manual inspection test on a physical device.

## 6. Fail-closed engine switching

A new engine is not started until the coordinator verifies that the previous engine stopped. For libbox, a shutdown error leaves a persisted "may be active" marker and a running/unverified status. The adapter refuses to report a verified disconnect.

This is intentionally conservative: a false positive that the tunnel is still active is safer than starting another VPN while the system route owner is uncertain.

**Confidence:** high for the state-machine implementation; process death and OEM-specific service behaviour remain untested.

## 7. Licensing audit

- RouteScout original code: GPL-3.0-only.
- libbox Android packaging: GPL-3.0-or-later; license text is preserved in \`LICENSES/GPL-3.0-or-later.txt\`.
- Upstream sing-box engine: GPL-3.0-or-later plus an additional notice that derivative works may not use the upstream application's name or imply association without prior consent. The upstream license text is preserved in \`LICENSES/sing-box-LICENSE.txt\`.

RouteScout does not use sing-box or SagerNet as its product name and is not affiliated with either project.

Primary license sources:
- https://github.com/singbox-android/libbox/blob/1.14.4/LICENSE
- https://github.com/SagerNet/sing-box/blob/v1.14.4/LICENSE

## 8. Independent audit — unresolved risks

| Risk | Why it matters | Current control | Remaining evidence |
|---|---|---|---|
| Two service implementations | Lifecycle and permission handoff can differ across OEMs | Coordinator serializes and verifies shutdown | Physical-device switching tests |
| TUN route API differences | Android 8–12 and 13+ use different route APIs | Separate route handling and dual-stack defaults | Real IPv4/IPv6 leak tests |
| DNS leak detection is incomplete | LinkProperties is not an external DNS leak observation | DNS hijack defaults and explicit report limitation | Independent external DNS leak test |
| sing-box profile normalizer | JSON profiles can contain complex route rules and custom selectors | Reject direct final route; enforce full routes | Fuzz/fixture testing against real provider configs |
| libbox native ABI | Native library can fail on unsupported ABI/device | Pinned AAR and SHA check | Build matrix and device install/run tests |
| Android foreground-service restrictions | Android 14+ applies FGS type/permission rules | VPN service declares systemExempted type and permission | Android 14/15/16 physical-device tests |
| Automatic failover | A failed route can interrupt existing TCP sessions | Profile-defined selector/urltest only | RouteScout-owned measured failover engine |
| EXIT/RELAY infrastructure | No server fleet or relay enrollment exists | Explicitly not advertised as implemented | Deployment, security, abuse and privacy review |

## 9. Release decision

The code is an engineering milestone, not a public beta. A successful CI run proves compilation and automated unit tests only. It does not prove that a tunnel connects to a real server, that all protocols work, or that DNS/IPv6 never leak on physical devices.
