# RouteScout Mobile

Android application for network diagnostics, VPN profile management, and intelligent connection routing.

## Current status

**0.1.0 — foundation build**

Implemented in this branch:
- Native Android project using Kotlin and Jetpack Compose.
- Dark premium interface inspired by the supplied reference: deep navy surfaces, restrained coral/cyan glow, fine glass-like borders, and serif section headings.
- Mobile navigation for Overview, Servers, Diagnostics, and Settings.
- Real on-device baseline checks for Android network validation, DNS resolution, and HTTPS reachability to example.com.
- GitHub Actions workflow to build a debug APK.

Not implemented yet:
- VPN tunnel engine and profile import.
- Foreign IP switching.
- Automatic server selection and failover.
- Per-app and per-domain routing.
- Full speed, packet-loss, jitter, and leak testing.

The UI deliberately does not invent server locations, connection states, ping values, or foreign IP addresses.

## Important infrastructure note

A foreign public IP requires a real remote VPN server or a VPN provider configuration. This repository does not contain servers, credentials, or private keys. Never commit VPN keys, passwords, or production configuration files.

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

1. Add secure WireGuard profile import and encrypted local storage.
2. Integrate a maintained VPN engine through Android VpnService.
3. Implement real tunnel state, IP verification, DNS handling, and kill switch.
4. Add server health checks and controlled reconnect/failover.
5. Add per-app routing and a proper diagnostic report.
6. Validate on physical Android devices and build a release APK.

CI note: the workflow uses the runner's preinstalled Android SDK command-line tools.
