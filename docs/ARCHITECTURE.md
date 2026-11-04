# RouteScout — architecture and implementation boundaries

**Status:** Android MVP with a first embedded multi-protocol engine  
**Last reviewed:** 2026-11-04  
**Platform:** Android 8.0+ (API 26+)  
**Original project license:** GPL-3.0-only

## 1. Product objective

RouteScout is an Android system VPN client with on-device diagnostics and evidence-based route selection.

The eventual network model has three roles:

- **EXIT:** project-controlled servers that provide public internet egress.
- **RELAY:** optional forwarding nodes that carry an encrypted client-to-EXIT stream and are not trusted as internet EXITs.
- **CONTROL PLANE:** signed node catalogue and configuration distribution. It is not on the user traffic path.

The EXIT/RELAY network is a target architecture, not an already deployed service. This repository does not currently contain an owned EXIT server, public relay enrollment, or a signed node registry.

## 2. Runtime architecture

1. **Android UI (Jetpack Compose):** profile import, connection state, server selection, quick diagnostics, full self-diagnostics, and report sharing.
2. **Network Observer:** active Android Network, validation/metering state, DNS observations, HTTPS probes, public IPv4/IPv6, and route-related state.
3. **Diagnosis Engine:** classifies DNS, HTTPS, handshake, route, and timeout symptoms. Operator filtering remains a hypothesis unless independent evidence supports it.
4. **VPN engine adapters:** existing WireGuard GoBackend adapter and the new sing-box/libbox adapter.
5. **Connection Coordinator:** serializes transitions, verifies shutdown, and rejects unverified connection states.
6. **Encrypted local state:** Android Keystore-backed AES-GCM profile stores; profile contents are never written to diagnostics.
7. **Optional provider workflows:** VPN Gate catalog and OpenVPN handoff remain separate from the embedded sing-box engine.

## 3. Android VPN ownership and service lifecycle

The current branch contains two VPN service implementations:

- WireGuard Android's existing \`GoBackend.VpnService\`.
- RouteScout's new \`RouteScoutVpnService\`, which hosts libbox.

Android permits one active VPN connection per Android user/profile. RouteScout's coordinator stops and verifies the previous engine before starting another. The two service implementations are not intended to run concurrently.

**Important limitation:** WireGuard has not yet been migrated into \`RouteScoutVpnService\`. It is therefore accurate to say the app serializes two VPN backends, but not that a single service implementation owns all protocols. Consolidating WireGuard and libbox under one service is a later migration requiring parity and physical-device tests.

A state that cannot be verified is treated as unsafe. If libbox shutdown cannot be confirmed, the service retains a conservative active marker and the coordinator refuses to start another engine.

## 4. sing-box/libbox integration

Pinned dependency:

- Maven coordinate: \`com.github.singbox-android:libbox:1.14.4\`
- Packaging repository: https://github.com/singbox-android/libbox
- Official AAR release: https://github.com/singbox-android/libbox/releases/tag/1.14.4
- AAR size: 118,293,410 bytes.
- Official SHA-256: \`d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465\`

The \`verifyLibboxAar\` Gradle task streams the resolved artifact through SHA-256 and is a dependency of \`preBuild\`. The task must pass before the app can be built.

### Android platform bridge

\`RouteScoutVpnService\` implements the Android \`VpnService\` lifecycle and creates the TUN descriptor requested by libbox. \`RouteScoutPlatformInterface\`:

- protects upstream sockets with \`VpnService.protect(fd)\`;
- reports non-VPN interfaces and monitors underlying networks;
- supplies Android connection-owner information where the API supports it;
- disables root shell, root bridge, root auto-redirect, and neighbor-monitor features;
- does not collect Wi-Fi SSID/BSSID or location;
- does not log raw native messages, because configuration values may appear in error output.

The service uses an Android foreground-service notification and releases the TUN descriptor and command server during shutdown. Shutdown failure is not treated as success.

### Accepted sing-box profiles

The app accepts sing-box JSON only when it has:

- a TUN inbound;
- at least one supported proxy outbound;
- a final route that is not \`direct\`, \`block\`, or \`dns\`.

The profile normalizer then:

- enables \`auto_route\`;
- adds IPv4 and IPv6 TUN addresses if missing;
- adds IPv4 and IPv6 default routes;
- enables DNS hijacking;
- enables \`route.auto_detect_interface\` to protect upstream sockets from routing back into TUN;
- preserves profile route exclusions, but rejects exclusions that cover all IPv4 or all IPv6;
- rejects simultaneous Android app allowlist and blocklist configuration because Android's VPN builder does not support both in one session.

The normalizer may change imported route behaviour to enforce RouteScout's full-device default. The app does not silently claim that an arbitrary proxy-only config is a system VPN.

### Embedded protocols

The adapter recognizes sing-box outbounds for:

- Shadowsocks
- VMess
- VLESS
- Trojan
- Hysteria2
- TUIC
- AnyTLS

WireGuard remains on the existing GoBackend path. OpenVPN is not provided by sing-box in this integration and continues to use an installed OpenVPN client through the existing handoff.

Imported sing-box \`selector\`/\`urltest\` groups can perform profile-defined server selection. RouteScout does not yet maintain its own multi-provider health-ranked failover catalogue.

## 5. Full-device routing and leak policy

The intended default is full-device routing:

- IPv4 default route: \`0.0.0.0/0\`.
- IPv6 default route: \`::/0\`.
- DNS hijacking is enabled in normalized sing-box TUN profiles.
- If the remote outbound cannot carry IPv6, IPv6 traffic is still captured by TUN and should fail closed rather than silently use the physical network.
- Direct final outbounds and selectors that can choose a direct final route are rejected.
- The user can enable Android Always-on VPN with "Block connections without VPN" for system-level lockdown.

The app has not yet passed a formal DNS/IPv6 leak suite on physical devices. Route construction is an implementation property, not proof that every Android OEM, captive portal, or process-death path behaves correctly.

## 6. WireGuard verification

WireGuard is reported connected only when all of these are true:

1. the GoBackend reports a running tunnel;
2. a recent peer handshake is observed;
3. a successful HTTPS request was made after connection;
4. received-byte counters increased relative to the pre-connect snapshot.

A handshake alone is not proof that payload traffic is passing.

For sing-box, there is no generic per-peer handshake field exposed by this adapter. The app requires the libbox service to start, Android to expose a VPN Network, and a successful HTTPS request explicitly bound to that VPN Network. A background monitor repeats a small HTTPS check every 30 seconds.

## 7. Full self-diagnostics and report format

The Diagnostics screen can run a full local check and open Android's Sharesheet with a JSON report.

The report includes:

- report schema version, timestamp, app version, Android release/API, manufacturer/model;
- profile type and engine state;
- VPN running/verified state, byte counters where exposed, and handshake age where available;
- Android network type, validation/metering/captive-portal flags, VPN transport state, and observed DNS servers;
- DNS/HTTPS/site/public-IP results;
- five repeated HTTPS response-time samples, failure ratio, and adjacent-sample latency variation;
- findings with severity and confidence;
- explicit measurement limitations.

Privacy defaults:

- exact public IP, DNS addresses, and VPN endpoint are masked;
- exact network values are included only after the user enables the opt-in checkbox;
- profile JSON, private keys, passwords, UUIDs, tokens, SSID/BSSID, location, serial numbers, Android IDs, and raw native logs are never exported;
- report generation is local; nothing is uploaded automatically.

Interpretation boundaries:

- repeated HTTPS failure ratio is not ICMP packet loss;
- adjacent HTTPS latency variation is not a formal jitter benchmark;
- DNS server addresses and successful DNS resolution do not prove absence of DNS leaks;
- same public IP before and after VPN is not by itself proof of a leak;
- destination-specific reachability failure does not identify the network operator or prove censorship.

## 8. Smart routing and failover

Current capability:

- sing-box profiles may include their own \`selector\` and \`urltest\` outbounds;
- the imported final route must lead to a supported proxy path;
- the coordinator serializes engine transitions and refuses to proceed when shutdown is uncertain.

Not implemented:

- RouteScout-owned multi-provider server catalogue with measured health;
- independent EXIT diversity and relay enrolment;
- automatic cross-provider failover after a live tunnel drops;
- formal packet-loss, throughput, and DNS-leak tests.

Switching routes can break existing TCP sessions. RouteScout must not promise seamless preservation of all existing connections.

## 9. Hybrid node model — target only

### EXIT nodes

A future first deployment should use one project-controlled Linux server, followed by a second server at an independent provider/network after demand is measured. Each EXIT needs rate limits, connection limits, abuse handling, patching, and egress monitoring.

### Volunteer RELAY nodes

The default relay role should forward an encrypted client-to-EXIT stream, not expose a volunteer's residential address as public internet egress. A relay can still observe client source IP, next hop, timing, and traffic volume. Enrollment must be explicit, authenticated, revocable, and tested against NAT/CGNAT reachability.

### Signed registry

A future node manifest should include node ID, role, endpoint, public key, supported transport, capacity, health observation, expiry, and signature. Key rotation and revocation are release gates before public enrollment.

## 10. Privacy and security defaults

- Profiles remain in app-private encrypted storage.
- Private keys and credentials are never logged or included in reports.
- Diagnostic reports are generated only after explicit user action.
- Public-IP providers see the public IP making each request.
- Untrusted volunteer relays must be labelled as untrusted.
- No silent direct-network fallback is intended.
- No custom cryptographic algorithms are introduced.
- Root-only libbox integrations are disabled.

## 11. Current implementation status

Implemented in this branch:

- WireGuard GoBackend engine and encrypted profile store.
- Shared engine contract and serialized coordinator.
- sing-box/libbox 1.14.4 dependency and checksum-verification Gradle task.
- RouteScout foreground VPN service and minimal Android platform interface.
- Encrypted sing-box profile store.
- sing-box JSON profile validator/normalizer and protocol detection.
- Full-tunnel route and DNS-hijack configuration for imported sing-box TUN profiles.
- Full diagnostics, privacy-aware JSON report, Android Sharesheet export.
- Unit tests for coordinator lifecycle, profile normalization, diagnostics metrics, and report redaction.
- Existing VPN Gate catalog and external OpenVPN handoff.

Not implemented or not yet validated:

- Single shared VPN service for WireGuard and libbox.
- Embedded OpenVPN.
- Owned EXIT servers, volunteer RELAY agent, signed registry.
- RouteScout-owned cross-provider automatic failover.
- Formal external DNS leak and IPv6 leak test suite.
- Physical-device lifecycle, OEM, Doze, handover, reboot, and lockdown acceptance tests.
- Production signing and public release.

## 12. Licensing

- RouteScout original code: GPL-3.0-only.
- libbox Android packaging: GPL-3.0-or-later.
- Upstream sing-box engine: GPL-3.0-or-later with an additional notice: derivative works may not use the upstream application's name or imply association without prior consent.

License texts are preserved in \`LICENSES/GPL-3.0-or-later.txt\` and \`LICENSES/sing-box-LICENSE.txt\`. RouteScout is independent and is not affiliated with or endorsed by SagerNet or sing-box.

## 13. Release gates

A public beta must not be declared ready until:

1. CI verifies the exact libbox AAR checksum and builds from a clean checkout.
2. libbox and WireGuard service transitions pass tests on physical devices.
3. Process death, VPN revoke, reboot, Wi-Fi/mobile handover, Doze, and Always-on lockdown are tested.
4. IPv4 and IPv6 either route through the tunnel or are safely blocked on supported Android versions.
5. DNS routing is independently tested for leaks.
6. The app never reports connected without protocol/data-path evidence.
7. Shutdown uncertainty blocks another engine from starting.
8. No credentials, keys, raw config, or private identifiers appear in diagnostics or source control.
9. GPL source and corresponding build instructions are included in every distributed build.
