# Research decisions — RouteScout

**Review date:** 2026-11-04

## Decision 1 — Keep one Android VPN owner

Android's VPN service API is the system boundary for a VPN connection. The current app uses WireGuard's GoBackend VPN service. A future libbox engine must not start a competing VPN service. The migration must move TUN ownership to one RouteScout-managed service and remove the old active backend path only after parity tests pass.

References:
- Android VPN guide: https://developer.android.com/develop/connectivity/vpn
- WireGuard GoBackend implementation: https://github.com/WireGuard/wireguard-android/blob/master/tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java

## Decision 2 — Use libbox as a planned multi-protocol engine, not as an unreviewed drop-in

The 1.14.4 sing-box release is published:
https://github.com/SagerNet/sing-box/releases/tag/v1.14.4

The separate Android packaging repository published a 1.14.4 AAR on 2026-10-25. GitHub release metadata reports:
- SHA-256: d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465
- Size: 118,293,410 bytes.

Release: https://github.com/singbox-android/libbox/releases/tag/1.14.4

This is a third-party packaging path. Before adding it to Gradle, pin the artifact, verify its digest in CI, inspect the source/build recipe, and test ABI compatibility. Do not assume that adding a dependency automatically provides an Android VPN service; the app must implement the PlatformInterface and VPN service lifecycle.

Reference Android implementation:
https://github.com/SagerNet/sing-box-for-android

## Decision 3 — Do not treat protocol names as resilience guarantees

- WireGuard is efficient but UDP reachability and endpoint availability remain network-dependent.
- Hysteria2/TUIC rely on UDP/QUIC and are not universal fallbacks.
- MASQUE is a tunnelling standard, not a guarantee against filtering.
- Snowflake/WebTunnel are Tor-related modes and require separate product and trust boundaries.
- ECH does not replace a VPN transport.

## Decision 4 — Volunteer relays should not be default EXIT nodes

A volunteer relay should forward an encrypted client-to-EXIT connection. It may still observe the client's source IP, next hop, timing and traffic volume. It should not hold inner tunnel keys or be able to inspect user payloads. Public internet egress through a volunteer's home IP is excluded from the MVP because it materially increases abuse, privacy and operational risks.

## Decision 5 — Fail closed when tunnel state is unknown

If a previous engine cannot confirm shutdown, the coordinator must refuse to start another engine. If a route fails, RouteScout must not silently expose traffic through the direct network. Android Always-on VPN lockdown is the system-level mechanism users can enable for stronger protection.

## Confidence

| Finding | Confidence |
|---|---|
| Current app has WireGuard GoBackend as active VPN backend | High — verified in branch manifest and UI |
| libbox 1.14.4 AAR release exists with the recorded digest | High — GitHub release metadata |
| libbox integration requires an Android platform interface and TUN lifecycle | High — reference Android implementation |
| A specific transport will bypass a specific operator filter | Low until measured on that network |
| Volunteer relays will be reliably reachable from residential NAT/CGNAT | Low until field-tested |
