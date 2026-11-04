# RouteScout Mobile

Android application for network diagnostics, VPN profile management, and intelligent connection routing.

## Current status

**0.6.1 — coordinated WireGuard lifecycle, verified traffic probe, and hardened profile import**

Implemented in this branch:
- Native Android project using Kotlin and Jetpack Compose.
- Dark premium interface inspired by the supplied reference: deep navy surfaces, restrained coral/cyan glow, fine glass-like borders, and serif section headings.
- Mobile navigation for Overview, Servers, Diagnostics, and Settings.
- Real on-device baseline checks for Android network validation, DNS resolution, and HTTPS reachability to example.com.
- GitHub Actions workflow to build a debug APK.
- Official VPN Gate public relay catalog fetched over HTTPS, refreshed on demand, and periodically refreshed in the background through WorkManager (subject to Android scheduling).
- CSV parsing with quoted-field support, profile validation, and duplicate removal.
- Server ranking based on VPN Gate's published score, ping, and throughput.
- On-demand TCP endpoint checks from the device; UDP is explicitly marked unverified until an actual VPN handshake.
- AUTO preflight of up to five top-ranked candidates, preferring a TCP endpoint that is reachable from the device and falling back to an unverified UDP profile only when needed.
- Temporary .ovpn profile export through Android FileProvider.
- OpenVPN profile filtering that removes local script/plugin/management directives and rejects external certificate paths.
- Unit tests for CSV parsing, server selection, WireGuard handshake evidence, the shared VPN engine contract, connection coordination, and OpenVPN profile sanitization.
- A shared VPN engine contract and serialized connection coordinator. The coordinator is a tested foundation; the current UI still uses the existing WireGuard connection flow directly.
- OpenVPN profile hardening: reject external certificate/key paths and profiles without a CA or peer fingerprint; enforce `remote-cert-tls server`; remove local script and management directives.
- Proton VPN Free setup path using the existing in-app WireGuard backend after the user downloads a configuration from the official account portal.
- WireGuard peer handshake and received-traffic verification before the UI reports a successful WireGuard connection.
- Public IPv4/IPv6 lookup using independent ipify and ipapi.co sources, country/ASN metadata, and same-family IP-source comparison.
- Direct DNS/HTTPS reachability probes for foreign websites, compared with example.com as a neutral control. The result is a heuristic, not proof of ISP filtering.
- Before/after public-IP comparison around an in-app WireGuard connection; the UI reports a changed IP only when two successful lookups differ.
- WorkManager background refresh of the VPN Gate catalog, with a 15-minute periodic request subject to Android battery and network scheduling.
- Local catalog cache containing up to 120 public profiles and recent TCP preflight results; the background worker rechecks up to 12 top-ranked candidates per refresh.

Not implemented yet:
- An embedded OpenVPN engine. VPN Gate profiles are currently handed off to an installed OpenVPN client; RouteScout does not yet own that OpenVPN tunnel.
- An embedded sing-box/libbox runtime. The engine contract is ready, but VLESS, VMess, Shadowsocks, Trojan, Hysteria2, TUIC, and other sing-box protocols are not yet available in RouteScout.
- Automated Proton account authentication/configuration retrieval. Proton's official account portal is used for config generation; RouteScout does not scrape private endpoints or store Proton credentials.
- Automatic server failover after an OpenVPN tunnel drops or a multi-profile WireGuard failover manager.
- Automatic switching to a backup server after a failed connection (the current WireGuard flow compares the public IP before and after connection but does not automatically rotate servers).
- Per-app and per-domain routing.
- Full speed, packet-loss, jitter, and leak testing.

The UI deliberately does not invent server locations, connection states, ping values, or foreign IP addresses. WireGuard is only shown as connected after a recent peer handshake and post-connect traffic evidence are observed.

## Important infrastructure note

### Free provider workflows

- **VPN Gate:** public volunteer relay directory, dynamic server list, OpenVPN profile export. An installed OpenVPN client is currently required for the handoff.
- **Proton VPN Free:** requires a free Proton account. The official portal can generate a WireGuard configuration on the Free plan; import the downloaded .conf into RouteScout to use its built-in WireGuard engine. The Free plan has one simultaneous device and free locations in ten countries; Proton states there are no data or time limits. The account portal is user-operated; RouteScout does not automate sign-in.

The automatically refreshed free-server catalog currently uses the official VPN Gate public directory. Proton remains an official user-generated configuration workflow; RouteScout does not automate Proton sign-in. Other third-party public catalogs may require temporary API keys, additional protocol engines, or provider-specific downloads and are not silently trusted or imported. Its relays are operated by volunteers, not by RouteScout. Availability, speed, and trustworthiness vary; do not send passwords or sensitive traffic through an untrusted relay. The directory's ping and score are upstream measurements, not measurements from the user's device. Public-IP checks contact ipify.org and ipapi.co; those services see the public IP making the request. RouteScout does not upload diagnostic reports. RouteScout can independently probe TCP endpoints; UDP reachability is only established by a successful OpenVPN handshake.

RouteScout currently exports a selected OpenVPN profile to an installed OpenVPN client. Background TCP preflight does not prove that an OpenVPN or UDP tunnel can complete a handshake. The user must complete the import and connection in that client and enter vpn / vpn if the relay requests credentials. This handoff is explicit; the app does not claim an OpenVPN tunnel is active when it cannot observe that tunnel.

A foreign public IP requires a real remote VPN server or a VPN provider configuration. This repository does not contain private server infrastructure. Never commit VPN keys, passwords, or production configuration files.

## License

RouteScout's original project code is licensed under GNU GPL-3.0-only. See `LICENSE` and `THIRD_PARTY_NOTICES.md`. The planned sing-box/libbox integration has additional upstream attribution and naming terms; it is not yet bundled in the current APK.

## Build

Open this repository in Android Studio (JDK 17, Android SDK 36) and run the app configuration.

A GitHub Actions workflow is included to build a debug APK and publish it as a workflow artifact.

## Design direction

The visual language follows the supplied reference image:
- near-black navy background;
- subtle coral and cyan ambient light;
- translucent dark panels with thin luminous borders;
- restrained serif display headings;
- compact metric cards and clear status hierarchy;
- no generic bright gradients or fake analytics.

## Next engineering milestones

1. Integrate a pinned, reproducible sing-box/libbox Android runtime behind the shared engine contract.
2. Implement RouteScout-owned Android `VpnService` lifecycle and verified libbox status/traffic counters.
3. Integrate an embedded OpenVPN engine and remove the external-client handoff.
4. Add measured server selection and automatic failover with bounded retries.
5. Add IPv6/DNS leak verification, jitter, packet-loss sampling, and opt-in throughput tests.
6. Add country filters, favourites, multi-profile storage, and server history.
7. Validate on physical Android devices and publish a reproducible GPL-compliant release.

CI note: the workflow uses the runner's preinstalled Android SDK command-line tools.
