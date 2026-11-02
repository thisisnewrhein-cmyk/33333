# RouteScout Mobile

Android application for network diagnostics, VPN profile management, and intelligent connection routing.

## Current status

**0.3.0 — free provider workflows**

Implemented in this branch:
- Native Android project using Kotlin and Jetpack Compose.
- Dark premium interface inspired by the supplied reference: deep navy surfaces, restrained coral/cyan glow, fine glass-like borders, and serif section headings.
- Mobile navigation for Overview, Servers, Diagnostics, and Settings.
- Real on-device baseline checks for Android network validation, DNS resolution, and HTTPS reachability to example.com.
- GitHub Actions workflow to build a debug APK.
- Official VPN Gate public relay catalog fetched over HTTPS.
- CSV parsing with quoted-field support, profile validation, and duplicate removal.
- Server ranking based on VPN Gate's published score, ping, and throughput.
- On-demand TCP endpoint checks from the device; UDP is explicitly marked unverified until an actual VPN handshake.
- AUTO preflight of up to five top-ranked candidates, preferring a TCP endpoint that is reachable from the device and falling back to an unverified UDP profile only when needed.
- Temporary .ovpn profile export through Android FileProvider.
- OpenVPN profile filtering that removes local script/plugin/management directives and rejects external certificate paths.
- Unit tests for CSV parsing, server selection, and WireGuard handshake evidence.
- Proton VPN Free setup path using the existing in-app WireGuard backend after the user downloads a configuration from the official account portal.

Not implemented yet:
- An embedded OpenVPN engine. VPN Gate profiles are currently handed off to an installed OpenVPN client; RouteScout does not yet own that OpenVPN tunnel.
- Automated Proton account authentication/configuration retrieval. Proton's official account portal is used for config generation; RouteScout does not scrape private endpoints or store Proton credentials.
- Real WireGuard peer handshake and received-traffic verification before the UI reports a successful WireGuard connection.
- Public IPv4/IPv6 lookup using independent ipify and ipapi.co sources, country/ASN metadata, and same-family IP-source comparison.
- Automatic server failover after an OpenVPN tunnel drops.
- Foreign IP verification and automatic IP switching.
- Per-app and per-domain routing.
- Full speed, packet-loss, jitter, and leak testing.

The UI deliberately does not invent server locations, connection states, ping values, or foreign IP addresses. WireGuard is only shown as connected after a recent peer handshake and post-connect traffic evidence are observed.

## Important infrastructure note

### Free provider workflows

- **VPN Gate:** public volunteer relay directory, dynamic server list, OpenVPN profile export. An installed OpenVPN client is currently required for the handoff.
- **Proton VPN Free:** requires a free Proton account. The official portal can generate a WireGuard configuration on the Free plan; import the downloaded .conf into RouteScout to use its built-in WireGuard engine. The Free plan has one simultaneous device and free locations in ten countries; Proton states there are no data or time limits. The account portal is user-operated; RouteScout does not automate sign-in.

Windscribe's official configuration generator requires a paid account, so it is not represented as a free third-party-config provider.

The free-server catalog uses the official VPN Gate public directory. Its relays are operated by volunteers, not by RouteScout. Availability, speed, and trustworthiness vary; do not send passwords or sensitive traffic through an untrusted relay. The directory's ping and score are upstream measurements, not measurements from the user's device. Public-IP checks contact ipify.org and ipapi.co; those services see the public IP making the request. RouteScout does not upload diagnostic reports. RouteScout can independently probe TCP endpoints; UDP reachability is only established by a successful OpenVPN handshake.

RouteScout currently exports a selected OpenVPN profile to an installed OpenVPN client. The user must complete the import and connection in that client and enter vpn / vpn if the relay requests credentials. This handoff is explicit; the app does not claim an OpenVPN tunnel is active when it cannot observe that tunnel.

A foreign public IP requires a real remote VPN server or a VPN provider configuration. This repository does not contain private server infrastructure. Never commit VPN keys, passwords, or production configuration files.

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

1. Integrate a maintained OpenVPN engine into RouteScout, subject to compatible licensing and Android lifecycle review.
2. Integrate and observe an in-app OpenVPN handshake state and tunnel byte counters, subject to compatible licensing.
3. Verify public IPv4/IPv6, DNS routing, and leaks after a real handshake.
4. Add measured server health checks and automatic failover with bounded retries.
5. Add IPv6/DNS leak verification, jitter, packet-loss sampling, and opt-in throughput tests.
5. Add country filters, favourites, multi-profile storage, and server history.
6. Validate on physical Android devices and build a release APK.

CI note: the workflow uses the runner's preinstalled Android SDK command-line tools.
