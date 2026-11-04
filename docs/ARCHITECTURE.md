# RouteScout — architecture and implementation boundaries

**Status:** architecture baseline for the Android MVP  
**Last reviewed:** 2026-11-04  
**Platform:** Android only  
**License:** GPL-3.0-only for original project code

## 1. Product objective

RouteScout is an Android system VPN client with local network diagnostics, evidence-based route selection, and a gradually expandable hybrid network:

- **EXIT:** project-controlled servers that provide public internet egress.
- **RELAY:** optional volunteer-operated forwarding nodes. Initially they forward an encrypted client-to-EXIT stream and are not trusted as internet EXITs.
- **CONTROL PLANE:** a signed node catalogue and configuration distribution service. It is not on the user traffic path.

A route is not considered working because a server appears in a catalogue or responds to a TCP probe. A connection is accepted only after the engine reports a live tunnel and protocol-specific evidence of a reachable peer.

No VPN design can guarantee access through every network restriction. A usable route still requires a reachable endpoint, valid credentials, a supported protocol, and sufficient capacity.

## 2. Runtime architecture

1. **Android UI (Jetpack Compose):** profile import, connection state, diagnostics, server selection and settings.
2. **Network Observer:** active network, DNS/HTTPS probes, public IPv4/IPv6 observations, route changes and timing.
3. **Diagnosis Engine:** classifies DNS, TCP, TLS, authentication, handshake, route and timeout failures. Filtering remains a hypothesis unless independent evidence supports it.
4. **VPN Engine adapters:** common lifecycle contract for WireGuard, planned libbox and planned embedded OpenVPN.
5. **Connection Coordinator:** serializes engine changes, verifies shutdown before switching and rejects unverified connection states.
6. **Local State:** Android Keystore for key material; app-private storage for profiles; Room is a future option for structured session history.
7. **Control Plane client:** downloads a signed, expiring node manifest. Cached manifests are used only while their signature and validity window remain acceptable.

## 3. One Android VPN owner

Android's VPN service API is the system boundary for device-wide VPN routing. The current app uses the WireGuard Android GoBackend service. It remains the only active VPN service in this milestone. The ConnectionCoordinator serializes RouteScout's engine lifecycle and the UI now uses it for WireGuard connect, disconnect and status checks.

When libbox is introduced, it must use a RouteScout-owned VPN service and become the single TUN owner. The existing GoBackend service must then be removed from the active path; two independent VPN services must never compete for the device route.

A state that cannot be verified is treated as unsafe. The app must refuse to start another engine until it can confirm that the previous tunnel has stopped.

## 4. Full-device routing and leak policy

The target profile is full-device routing, not browser-only proxying:

- IPv4 default route: 0.0.0.0/0.
- IPv6 default route: ::/0 when the selected engine and EXIT support IPv6.
- DNS: use resolvers reachable through the tunnel.
- If IPv6 cannot be tunneled, block IPv6 rather than silently allowing it to bypass the VPN.
- Do not silently fall back to direct internet when a tunnel fails.
- Per-app routing is a later optional feature; full-device routing is the default.

Android Always-on VPN with "Block connections without VPN" is the system-level lockdown mechanism. RouteScout can guide the user to enable it, but must not claim that an in-app flag alone provides equivalent protection after process death or service failure.

## 5. Engine contract

The package com.routescout.mobile.vpn contains:

- VpnEngine: engine identity, supported protocols, connect, disconnect and live status.
- VpnEngineStatus: separates local tunnel state (isRunning) from verified remote connectivity (isVerified).
- ConnectionCoordinator: serializes all engine transitions and re-reads live status before accepting a connection.
- WireGuardEngineAdapter: adapts the existing WireGuard backend and requires a recent peer handshake plus traffic evidence.

Only WireGuard is registered in the current UI. SING_BOX and OPENVPN are planned engine identifiers, not claims of implemented support.

## 6. Transport strategy

### MVP

- **WireGuard:** first owned EXIT transport; already present in the app.
- **Second transport:** add only after the first engine is stable and tested on physical networks. Shadowsocks is a candidate, but protocol choice must be based on current upstream support, implementation quality and independent network testing.

### Later evaluation

- sing-box/libbox can provide multiple maintained transports behind one engine and one Android VPN service.
- Hysteria2 and TUIC depend on UDP/QUIC reachability and are not universal fallbacks.
- WebTunnel/Snowflake are separate Tor-related modes, not drop-in replacements for RouteScout's own EXIT network.
- MASQUE is an HTTP-based tunnel standard, not a guarantee of censorship resistance.
- ECH is a TLS privacy feature, not a VPN transport.

RouteScout must not describe any transport as invisible to DPI or universally unblockable.

## 7. Hybrid node model

### EXIT nodes

- Initially one project-controlled Linux server.
- Add a second EXIT at a different provider/network only after the first has measured demand.
- Keep control-plane administration separate from public user traffic when practical.
- Apply per-user rate limits, connection limits, abuse handling, security updates and egress monitoring.

### Volunteer RELAY nodes

- Default role is encrypted forwarding only, not public internet egress.
- A relay can observe the client's source IP, the next-hop relay/EXIT, timing and traffic volume. It must not receive the inner tunnel keys or plaintext DNS/HTTP contents.
- Relay identity is authenticated and revocable.
- Node enrollment is explicit; arbitrary public proxies are not automatically trusted.
- NAT/CGNAT can prevent inbound reachability. A volunteer device is not assumed to be a usable relay merely because it is online.

### Signed node registry

Each manifest entry should include node ID, role, endpoint, public key, supported transport, capacity, health observation, expiry and signature. The client verifies signatures before applying configuration. Key rotation and revocation must be designed before public enrollment.

## 8. Smart routing and failover

A candidate is eligible only after profile validation and a successful protocol handshake. TCP reachability alone is not proof of tunnel viability.

Ranking may consider:
- recent handshake and connection success rate;
- latency, jitter and packet loss measured from the device;
- actual throughput, measured only with user consent;
- server load and remaining capacity;
- independence of provider, address range and network path;
- recent failures on the current access network.

Retries are bounded and avoid repeatedly selecting the same failed endpoint. Switching routes can break existing TCP sessions; RouteScout can restore new connections but must not promise seamless preservation of every existing session.

## 9. Privacy and security defaults

- Profile contents and private keys remain in app-private storage.
- Private keys must never be logged, exported in diagnostics or committed to Git.
- Diagnostic history is local by default.
- Public-IP services see the address making the request; the UI discloses the services used.
- Report export/upload requires explicit user action.
- Untrusted volunteer relays are labelled as such.
- No silent direct-connection fallback.
- No custom cryptographic algorithms.

## 10. Current implementation status

Implemented in the working branch:
- Kotlin and Jetpack Compose application.
- WireGuard through the WireGuard Android tunnel library.
- Encrypted WireGuard profile storage using Android Keystore.
- Local DNS/HTTPS and public-IP diagnostics.
- VPN Gate catalogue and explicit OpenVPN profile handoff.
- WireGuard handshake and received-traffic verification.
- Shared engine contract, serialized coordinator and WireGuard adapter.
- UI connect/disconnect/status flow routed through the coordinator.
- Unit tests for coordinator state transitions.

Not implemented:
- Embedded libbox runtime.
- RouteScout-owned replacement VPN service.
- Embedded OpenVPN engine.
- Own EXIT server deployment.
- Volunteer RELAY agent or signed public node registry.
- Automatic multi-server failover.
- Full DNS/IPv6 leak test suite and physical-device acceptance tests.

## 11. libbox dependency decision

The sing-box 1.14.4 release and the singbox-android/libbox 1.14.4 Android AAR release were checked on 2026-11-04.

- AAR release: https://github.com/singbox-android/libbox/releases/tag/1.14.4
- AAR SHA-256: d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465
- AAR size: 118,293,410 bytes.
- Upstream sing-box release: https://github.com/SagerNet/sing-box/releases/tag/v1.14.4

The Android AAR is distributed by the separate singbox-android/libbox packaging repository through GitHub Releases/JitPack; it is not committed to the app repository. Before integration, pin the exact version, verify the artifact digest in CI, inspect the source/build recipe and licenses, and test the native ABI on supported Android architectures. The dependency is not included in this milestone.

The sing-box Android reference client demonstrates use of Android VPN service and a PlatformInterface implementation:
https://github.com/SagerNet/sing-box-for-android

## 12. Release gates

A public beta must not be declared ready until:
1. A single TUN owner is verified on Android 8+ and supported OEM builds.
2. IPv4 and IPv6 either route through the tunnel or are safely blocked.
3. DNS queries do not bypass the tunnel.
4. The app does not report connected without live protocol evidence.
5. VPN revoke, process death, reboot, Wi-Fi/mobile handover and Doze are tested.
6. Always-on/lockdown behaviour is documented and tested.
7. No credentials or private keys appear in logs, reports or source control.
8. CI builds from a clean checkout and verifies pinned native artifacts.
