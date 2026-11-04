# RouteScout architecture

## Product objective

RouteScout is an Android VPN client with local network diagnostics and measured connection selection. It must report a tunnel as connected only when the selected engine provides evidence that the remote peer is actually reachable.

The application cannot guarantee access through every network restriction. A working route requires a reachable remote endpoint, valid credentials, and a protocol the network permits. RouteScout's job is to measure, select, recover, and explain—not to invent working servers.

## Runtime layers

1. **Network Observer** — captures the active network, DNS/HTTPS reachability, public IPv4/IPv6, route changes, and timing measurements.
2. **Diagnosis Engine** — classifies failures (DNS, TCP, TLS, authentication, handshake, route, timeout) and distinguishes observations from hypotheses.
3. **VPN Engine adapters** — provide a common lifecycle for WireGuard, the planned embedded OpenVPN engine, and the planned libbox engine.
4. **Connection Coordinator** — serializes tunnel changes. It stops registered engines before starting another and accepts a connection only when the engine reports both a running tunnel and verification evidence.
5. **Strategy Engine** — will rank candidate profiles using current-network measurements and recent local outcomes. Retries must be bounded and avoid repeating a known failed endpoint.
6. **Telemetry Store** — records local session counters and diagnostic summaries. Diagnostic reports are not uploaded automatically.

## One Android VPN owner

Android exposes VPN operation through `VpnService`; Android permits only one active VPN service per user/profile. RouteScout therefore has one serialized connection coordinator even though individual backends may have different service implementations. Switching engines is a serialized operation: request shutdown from every registered engine, query each engine's live state, and start the next only when all report `isRunning=false`. A successful `disconnect()` return alone is not proof that the TUN interface was released. If any engine cannot be queried or still reports a running tunnel, RouteScout refuses to start another engine.

## Engine contract

The source package `com.routescout.mobile.vpn` defines:

- `VpnEngine` — engine identity, supported protocols, connect, disconnect, and status.
- `VpnEngineStatus` — running state, verified state, byte counters, handshake timestamp, and failure reason.
- `ConnectionCoordinator` — serializes lifecycle changes and rejects unverified connections.
- `WireGuardEngineAdapter` — adapts the existing WireGuard backend to the common contract.

The current UI uses the coordinator for WireGuard connect, disconnect, and status. The registry currently contains WireGuard only; sing-box/libbox and embedded OpenVPN are not yet registered. The WireGuard manager is application-scoped so the same GoBackend and Tunnel object survive Activity recreation.

## WireGuard verification and app routing

The active WireGuard UI path is routed through `ConnectionCoordinator`. A connection is not accepted merely because the backend reports `State.UP`: RouteScout sends a lightweight HTTPS request to `https://example.com/`, then waits for a recent peer handshake and traffic evidence. The coordinator re-reads live engine status after `connect()` returns.

RouteScout validates the imported WireGuard app filters before disconnecting an existing tunnel. If the profile excludes RouteScout, or uses an allow-list that omits RouteScout, the app refuses the attempt because its own traffic probe would bypass the tunnel and could not provide valid verification evidence. The profile is not changed automatically.

The backend and its `Tunnel` instance are process-scoped singletons. This matters because WireGuard Android's `GoBackend` tracks the current tunnel and service owner on the backend instance; creating a second backend object can lose the reference to an already-running tunnel after Activity recreation.

A successful HTTPS probe proves that this app received a remote response through its configured Android route. It does not prove that every app is routed through the VPN, nor does it replace DNS/IPv6 leak testing.

## Planned libbox integration

The planned multi-protocol runtime is the upstream Android libbox library from the sing-box project. The upstream project is GPL-3.0-or-later and includes an additional section 7 term restricting use of its application name and implied affiliation. RouteScout will retain the required notices and will not use upstream branding or imply endorsement.

As checked on 2026-11-04, the current libbox release candidate is **1.14.4** (published 2026-10-25). Its published AAR SHA-256 is `d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465`. This is a planning pin only; the dependency is not yet added to the app and the artifact must be re-verified during integration.

Integration is intentionally a separate milestone. It requires:

- pinning an exact upstream libbox release and recording its source commit and artifact checksum;
- adding the native AAR and its corresponding source/build recipe to the reproducible build;
- implementing one RouteScout-owned `VpnService` and the libbox platform interface;
- forwarding protect/open-TUN operations to Android correctly;
- validating service lifecycle, foreground notification, revoke, reboot, and network changes;
- testing IPv4, IPv6, DNS, per-app routing, and leak behavior on physical devices.

The current application does not yet embed libbox and does not yet support VLESS, VMess, Shadowsocks, Trojan, Hysteria2, TUIC, or other sing-box protocols.

## OpenVPN profile safety

VPN Gate profiles are untrusted input. The importer enforces a 256 KiB decoded-profile limit, rejects external certificate/key paths, unsupported HTTP/SOCKS proxy profiles, non-TUN device types, missing client/remote endpoints, and malformed trust material. Inline CA blocks must contain parseable X.509 certificates; SHA-256 peer fingerprints are accepted as an alternative trust check. When CA trust is used without a peer pin, the sanitizer adds `remote-cert-tls server`. The OpenVPN 2.6 manual explicitly treats `peer-fingerprint` as an alternative peer-identity check, so the sanitizer does not add the server-EKU constraint when a valid fingerprint pin is present. Inline usernames/passwords are not exported; an authentication prompt is retained only when the source profile requested it.

RouteScout must not silently enable legacy ciphers such as BF-CBC to make an old relay connect. Any legacy compatibility option must be explicit, explained, and separately security-reviewed.

## Diagnostics and confidence

A failed website probe is not proof of ISP filtering. DNS failure, TLS failure, HTTP status, timeout, and baseline control results are recorded separately. The diagnosis engine may label filtering as suspected only when multiple independent observations support that hypothesis. It must show the evidence and confidence rather than state a guess as fact.

## Privacy defaults

- Profile contents and private keys stay in app-private storage.
- Profiles and keys must never be written to logs or committed to source control.
- Diagnostic history is local by default.
- External public-IP services see the address making the request; the UI must disclose this.
- Any report export or upload requires an explicit user action.

## Milestones

1. Complete the current 0.6.1 audit fixes and obtain a clean CI run on the final branch head.
2. Integrate the pinned libbox 1.14.4 runtime with checksum verification and the exact matching platform-interface API.
3. Implement the libbox Android service lifecycle and a real TUN/traffic verification path before registering it with the coordinator.
4. Integrate an embedded OpenVPN engine only after its license, native ABI, and lifecycle compatibility are verified.
5. Add measured candidate ranking, bounded failover, and recovery; never switch on TCP preflight alone.
6. Add DNS/IPv6 leak verification, jitter, packet-loss sampling, and opt-in throughput tests.
7. Validate on physical Android devices and publish a reproducible GPL-compliant release.
