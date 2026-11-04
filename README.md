# RouteScout Mobile

Android system VPN client with on-device network diagnostics, encrypted profile storage, and serialized VPN-engine lifecycle management.

## Current implementation

**Version:** 0.7.0  
**Branch:** \`codex/routescout-mobile\`  
**Platform:** Android 8.0+ (API 26+), Kotlin, Jetpack Compose

Implemented in this branch:

- Kotlin/Compose application with Overview, Servers, Diagnostics, and Settings screens.
- Existing WireGuard engine with Android Keystore-encrypted profile storage, recent peer-handshake checks, and received-byte evidence.
- Shared \`VpnEngine\` contract and \`ConnectionCoordinator\`; engine changes are serialized and a new engine is not started while the previous engine's shutdown is unverified.
- Pinned sing-box/libbox 1.14.4 Android AAR dependency through JitPack.
- A dedicated \`RouteScoutVpnService\` and Android \`PlatformInterface\` for sing-box profiles. The service owns its TUN descriptor, protects upstream sockets, monitors the underlying non-VPN network, and keeps the foreground-service notification updated.
- Encrypted sing-box JSON profile storage using a separate Android Keystore AES-GCM key.
- sing-box JSON import validation. RouteScout requires a TUN inbound and supported proxy final outbound; it normalizes full IPv4/IPv6 routes, DNS hijacking, and automatic underlying-interface detection, and rejects direct final routes.
- Embedded sing-box transports exposed by imported profiles: Shadowsocks, VMess, VLESS, Trojan, Hysteria2, TUIC, and AnyTLS. Imported sing-box selector/urltest groups can provide upstream-managed server selection and health testing.
- Existing WireGuard profiles continue to use the WireGuard Android GoBackend. The coordinator switches between the two service backends sequentially; both are not intentionally run together.
- Android per-app allowlist/blocklist settings supplied by a sing-box TUN profile are applied through the Android VPN builder. A profile that supplies both lists is rejected because Android's VPN builder does not allow both modes simultaneously.
- Full on-device self-diagnostics: Android network type/validation, DNS server observations, the existing DNS/HTTPS/public-IP/site checks, and five repeated HTTPS timing probes.
- A shareable JSON diagnostic report with findings, confidence labels, engine state, and measurement limits. By default, public IPs, DNS addresses, and VPN endpoint are masked. Exact network data is included only after the user enables the explicit opt-in. VPN profile text, keys, passwords, UUIDs, tokens, SSIDs, BSSIDs, device identifiers, and raw native logs are never exported.
- Diagnostic report sharing through Android's system Sharesheet; nothing is uploaded automatically.
- Unit tests for engine coordination, profile validation, diagnostic metrics, and report redaction.
- Existing VPN Gate catalog and explicit OpenVPN profile handoff to an installed OpenVPN client.

## Supported profile types

| Profile path | Status |
|---|---|
| WireGuard \`.conf\` | Built-in existing GoBackend |
| sing-box JSON with TUN inbound | Built-in libbox engine |
| Shadowsocks / VMess / VLESS / Trojan | Through sing-box JSON |
| Hysteria2 / TUIC / AnyTLS | Through sing-box JSON |
| OpenVPN \`.ovpn\` | Export/handoff to an installed OpenVPN client; not embedded |

RouteScout does not claim that a protocol will work on every operator or network. The imported profile still needs a reachable server, valid credentials, and a compatible remote configuration.

## Important architecture boundary

This branch currently contains two sequential Android VPN service implementations:

1. WireGuard's existing GoBackend service.
2. RouteScout's new foreground VPN service hosting libbox.

Android permits only one active VPN connection per user/profile. The coordinator disconnects and verifies the previous engine before starting the next. The project has **not yet migrated WireGuard into the new RouteScout service**, so it is not accurate to claim that one service implementation owns every protocol. Physical-device lifecycle testing is still required.

The libbox platform bridge deliberately disables root shell, root bridge, and root auto-redirect capabilities. It does not request root access.

## Self-diagnostic report

Open **Diagnostics → Full self-diagnostics → Run check and prepare report**.

The app runs checks locally and opens the Android Sharesheet. Choose ChatGPT if it is available, or save the JSON file and attach it to the conversation.

Privacy defaults:

- Exact IP addresses, DNS server addresses, and VPN endpoint are masked by default.
- The opt-in checkbox includes those exact network details.
- VPN configuration contents and credentials are never included.
- No report is sent until the user chooses an app in the Sharesheet.

Interpretation limits are included in the report:

- Repeated HTTPS failures are not ICMP packet loss.
- HTTPS timing variation is not a formal jitter benchmark.
- DNS server addresses and successful DNS lookups do not independently prove absence of DNS leaks.
- A public IP that did not change is not, by itself, proof of a leak.
- A destination-specific failure is a heuristic, not proof of ISP filtering.

## Not implemented / not yet verified

- Embedded OpenVPN engine.
- RouteScout-operated EXIT server deployment.
- Volunteer RELAY agent, signed public node registry, enrollment, key rotation, and abuse controls.
- RouteScout-owned multi-provider failover catalogue. sing-box \`selector\`/\`urltest\` groups are used only when present in the imported profile.
- Full independent DNS-leak test service and formal IPv4/IPv6 leak acceptance suite.
- Formal ICMP packet-loss, jitter, and throughput benchmarks.
- Physical-device acceptance testing across Android versions, OEMs, Wi-Fi/mobile handover, Doze, process death, reboot, VPN revoke, and Always-on lockdown.
- Production signing and public release.

## Build and integrity

- JDK 17
- Android SDK 36
- Gradle wrapper included
- libbox dependency: \`com.github.singbox-android:libbox:1.14.4\`
- Official libbox AAR SHA-256: \`d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465\`

The Gradle \`verifyLibboxAar\` task checks the resolved AAR against the release SHA-256 and is wired into \`preBuild\`. GitHub Actions runs unit tests and builds a debug APK. A successful CI build is necessary but does not replace physical-device VPN and leak tests.

## Architecture and research

- [Architecture and release gates](docs/ARCHITECTURE.md)
- [Research decisions and evidence](docs/RESEARCH_DECISIONS.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## Licensing

Original RouteScout code: GPL-3.0-only.  
Bundled libbox: GPL-3.0-or-later.  
The upstream sing-box license includes an additional restriction against using the upstream application's name or implying association without consent. See [LICENSES/sing-box-LICENSE.txt](LICENSES/sing-box-LICENSE.txt) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

RouteScout is an independent project and is not affiliated with or endorsed by the sing-box or SagerNet projects.
