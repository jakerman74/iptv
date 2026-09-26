# Channel Surfer

An Android phone app for browsing an M3U live TV playlist. The playlist engine is written in Rust. Kotlin provides the screen, network download, and Media3 video player.

The first launch loads the curated playlist at `https://raw.githubusercontent.com/jakerman74/iptv/master/channel-surfer/lineup.m3u`, which is maintained in the user's IPTV-org fork. Paste another HTTPS M3U address in the source field and tap **Load** to change it. Streams are supplied by the selected playlist; the app does not supply paid channels or bypass access controls.

## Features

- Channel up/down, **Surprise me**, search, category filter, favorites, and a scrollable channel list.
- Hide a channel to remove it from your lineup; hidden URLs persist on the device. **Share lineup** exports the current filtered list as M3U text for a repository or other player. Use Favorites to export only the channels you have selected.
- Tap the video to show Media3 playback controls. Favorites and the playlist URL persist on the device.
- When a stream reports a playback error or does not become ready in 15 seconds, the app advances to the next filtered channel. Manual channel changes cancel the previous timeout.
- The app accepts HTTPS playlist URLs and HTTPS stream URLs only; Android cleartext traffic is disabled.
- Rust parses extended M3U metadata, handles the visible lineup, navigation, shuffle, and favorites. Android makes the network requests and decodes video.

## Build

Install Android Studio (Android SDK 35, NDK), Rust with `rustup`, and `cargo-ndk`:

```sh
cargo install cargo-ndk
rustup target add aarch64-linux-android x86_64-linux-android
```

Open this directory in Android Studio. Install Android SDK 36, set `ANDROID_HOME` (or `local.properties` with `sdk.dir`), and set `ANDROID_NDK_HOME` if your NDK is not discovered by `cargo-ndk`. Gradle builds Rust for ARM64 phones and x86_64 emulators before packaging the APK. Use a local Gradle 8.11.1 installation, run `gradle wrapper --gradle-version 8.11.1`, then `./gradlew assembleDebug`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

Rust tests: `cargo test --manifest-path rust/Cargo.toml`.

## Current limits

This version shows channels, not a live program guide. An M3U playlist rarely contains reliable currently airing show data; a separate XMLTV source and channel mapping would be needed for an EPG. Android Media3 handles common HTTPS live stream formats, but individual entries may be offline, geoblocked, use only HTTP, or require unsupported headers or codecs. The 15-second timeout avoids getting stuck while surfing; a slow working stream may be skipped. The app plays only while on screen.

## Pi-hole and Cloudflare DNS

1. Run Pi-hole on your home network and set your router's DHCP DNS address to Pi-hole's local IP. Set the IPv6 DNS address as well or disable IPv6 DNS advertisement if your network would otherwise bypass Pi-hole.
2. Configure [cloudflared as Pi-hole's DNS-over-HTTPS upstream](https://docs.pi-hole.net/guides/dns/cloudflared/), pointing it to [Cloudflare 1.1.1.1](https://developers.cloudflare.com/1.1.1.1/encryption/dns-over-https/). Merely choosing 1.1.1.1 as Pi-hole's upstream does not encrypt DNS.
3. Keep Android Private DNS from pointing to a different public provider while using home Pi-hole: that would bypass Pi-hole. On cellular, Pi-hole requires a VPN tunnel home with DNS routed to it.

Pi-hole blocks domains; Cloudflare DoH encrypts the Pi-hole-to-Cloudflare DNS hop. HTTPS encrypts playlist and stream content. Neither DNS service anonymizes viewing traffic from the ISP: destination IP addresses and traffic volume remain observable. A trusted full-device VPN or Cloudflare WARP routes traffic through a tunnel, changing who can observe the destinations; the VPN provider then becomes part of your trust model. WARP and a VPN home for Pi-hole may compete for Android's VPN slot.

### If the goal is to hide streaming destinations from the ISP

Install Cloudflare's [Android 1.1.1.1 app](https://developers.cloudflare.com/warp-client/get-started/android/) and connect in **WARP** mode, not DNS-only mode. Full WARP routes device traffic through Cloudflare, including the video streams. Your ISP still sees a connection to Cloudflare and its volume/timing; Cloudflare can see destinations. Consumer WARP typically uses Cloudflare DNS, so do not assume Pi-hole blocks domains on the phone while WARP is connected. Pi-hole with DoH is the simpler separate setup for home Wi-Fi when DNS blocking is the priority. More complex Cloudflare One managed profiles can route selected DNS queries to a private resolver, but are outside this app's setup. This Android app does not install, control, or verify WARP.
