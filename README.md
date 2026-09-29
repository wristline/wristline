# Wristline

Wristline is a Wear OS app for Galaxy Watch that lets you browse and control AI coding-agent sessions running on your PC through Wristline Bridge: https://github.com/wristline/wristline-bridge

**Requires wristline-bridge.** The watch app does nothing on its own: install the companion server on the
computer where your agents run (`npm i -g wristline-bridge && wristline-bridge setup`), expose it over
HTTPS (for example Tailscale Funnel), then pair the watch with the 6-digit code from `wristline-bridge pair`.
Without a bridge, "Try demo" on the welcome screen shows the app with sample data.

## Status

Early development.

## Background alerts

With **Settings > Background alerts** on, a foreground service keeps the single connection to the
bridge while the app is closed, so permission prompts and questions arrive as notifications that
vibrate (tap one to answer it), and finished tasks as quieter updates. It shows as an ongoing
activity such as "2 running · 1 waiting" and uses more battery than leaving it off. Notifications
must be allowed for it to run.

There is no start-at-boot: after the watch restarts, or if the system stops the service, monitoring
resumes the next time you open Wristline. Unpairing or a revoked pairing turns it off.

## Development setup

The watch app builds from the command line with the Android SDK command-line tools and the Gradle wrapper in this repository (no Android Studio). See [docs/dev-setup.md](docs/dev-setup.md) for the SDK install, environment variables, build commands, wireless adb pairing and emulator notes.

## Privacy

The app talks only to the bridge you configure, over HTTPS. There are no developer servers, analytics,
ads or crash reporting. Pairing stores a bearer token on the watch; nothing leaves the watch except what
goes to your own bridge. Full policy: [docs/privacy.md](docs/privacy.md), published at
https://wristline.github.io/wristline/privacy (GitHub Pages from the `docs/` folder).

## Play Store

Not yet published. The listing draft is in [docs/play-listing.md](docs/play-listing.md).

## License

TBD.
