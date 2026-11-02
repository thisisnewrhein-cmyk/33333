# RouteScout Mobile

Android application for network diagnostics, VPN profile management, and intelligent connection routing.

## Current status

**0.2.0 — free server catalog milestone**

Implemented in this branch:
- Native Android project using Kotlin and Jetpack Compose.
- Dark premium interface inspired by the supplied reference: deep navy surfaces, restrained coral/cyan glow, fine glass-like borders, and serif section headings.
- Mobile navigation for Overview, Servers, Diagnostics, and Settings.
- Real on-device baseline checks for Android network validation, DNS resolution, and HTTPS reachability to example.com.
- GitHub Actions workflow to build a debug APK.
- Official VPN Gate public relay catalog fetched over HTTPS.
- CSV parsing with quoted-field support, profile validation, and duplicate removal.
- Server ranking based on VPN Gate's published score, ping, and throughput.
- Temporary .ovpn profile export through Android FileProvider.
- OpenVPN profile filtering that removes local script/plugin/management directives and rejects external certificate paths.
- Unit tests for CSV parsing and server selection.

Not implemented yet:
- An embedded OpenVPN engine. VPN Gate profiles are currently handed off to an installed OpenVPN client; RouteScout does not yet own that OpenVPN tunnel.
- Real in-app connection state and handshake verification for VPN Gate servers.
- Automatic server failover after an OpenVPN tunnel drops.
- Foreign IP verification and automatic IP switching.
- Per-app and per-domain routing.
- Full speed, packet-loss, jitter, and leak testing.

The UI deliberately does not invent server locations, connection states, ping values, or foreign IP addresses.

## Important infrastructure note

The free-server catalog uses the official VPN Gate public directory. Its relays are operated by volunteers, not by RouteScout. Availability, speed, and trustworthiness vary; do not send passwords or sensitive traffic through an untrusted relay. The directory's ping and score are upstream measurements, not measurements from the user's device.

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
2. Observe actual OpenVPN handshake state and tunnel byte counters from inside RouteScout.
3. Verify public IPv4/IPv6, DNS routing, and leaks after a real handshake.
4. Add measured server health checks and automatic failover with bounded retries.
5. Add country filters, favourites, multi-profile storage, and server history.
6. Validate on physical Android devices and build a release APK.

CI note: the workflow uses the runner's preinstalled Android SDK command-line tools.
