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

Android exposes VPN operation through `VpnService`. RouteScout must have one coordinator and one active tunnel owner per Android user/profile. Protocol engines must not start competing VPN services. Switching engines is a serialized operation: stop the current engine, confirm it has stopped, then start the next one. If shutdown cannot be confirmed, RouteScout must refuse to start another engine rather than risk two conflicting tunnels.

## Engine contract

The source package `com.routescout.mobile.vpn` defines:

- `VpnEngine` — engine identity, supported protocols, connect, disconnect, and status.
- `VpnEngineStatus` — running state, verified state, byte counters, handshake timestamp, and failure reason.
- `ConnectionCoordinator` — serializes lifecycle changes and rejects unverified connections.
- `WireGuardEngineAdapter` — adapts the existing WireGuard backend to the common contract.

The current UI still uses the existing WireGuard flow directly. The coordinator is a tested foundation and is not yet the active UI connection path.

## Planned libbox integration

The planned multi-protocol runtime is the upstream Android libbox library from the sing-box project. The upstream project is GPL-3.0-or-later and includes an additional section 7 term restricting use of its application name and implied affiliation. RouteScout will retain the required notices and will not use upstream branding or imply endorsement.

Integration is intentionally a separate milestone. It requires:

- pinning an exact upstream libbox release and recording its source commit and artifact checksum;
- adding the native AAR and its corresponding source/build recipe to the reproducible build;
- implementing one RouteScout-owned `VpnService` and the libbox platform interface;
- forwarding protect/open-TUN operations to Android correctly;
- validating service lifecycle, foreground notification, revoke, reboot, and network changes;
- testing IPv4, IPv6, DNS, per-app routing, and leak behavior on physical devices.

The current application does not yet embed libbox and does not yet support VLESS, VMess, Shadowsocks, Trojan, Hysteria2, TUIC, or other sing-box protocols.

## OpenVPN profile safety

VPN Gate profiles are untrusted input. The exporter removes local script and management directives, rejects external certificate/key paths, requires a usable remote endpoint, and requires a CA certificate or peer fingerprint. It also enforces `remote-cert-tls server` so the client checks that the peer certificate is intended for a TLS server.

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

1. Establish GPL-3.0 licensing, notices, a shared engine contract, and serialized connection lifecycle.
2. Integrate and verify the pinned libbox runtime inside RouteScout.
3. Integrate an embedded OpenVPN engine and remove the external-client handoff.
4. Add measured candidate ranking, bounded automatic failover, and recovery.
5. Add packet loss, jitter, throughput, DNS/IPv6 leak checks, and a local session-history dashboard.
6. Validate on physical Android devices and publish a reproducible release.
