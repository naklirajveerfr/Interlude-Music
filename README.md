<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" alt="Interlude app icon" width="200" />

# Interlude

### A YouTube Music client for Android

</div>

An unofficial, ad-free YouTube Music client for Android, built with **Material 3 Expressive**.

> [!IMPORTANT]
> Interlude is not affiliated with, endorsed by, or sponsored by Google or YouTube.

Interlude is a fork of [Metrolist](https://github.com/MetrolistGroup/Metrolist). It is a personal project that is actively evolving, so some features may still have rough edges.

<!-- Add screenshots here, for example: -->

<!-- ![Home](assets/screenshot-home.png) -->

## Features

### Playback

* **Crossfade** between songs, with an optional gapless mode for songs on the same album.
* **Seamless transition (beta):** analyzes the BPM of the ending and upcoming songs on your device, gradually matching their tempos before the mix. The next song begins after any silent intro, with the transition timed around fade-outs.
* Listening cache and downloads.
* Built-in equalizer.
* Sleep timer.

### Lyrics

* Synced lyrics, including word-by-word lyrics.
* Multiple lyrics providers with a customizable priority order in Content settings.
* **Musixmatch (beta):** uses an unofficial endpoint and may stop working without notice.
* **Per-song lyric provider:** choose a specific provider for an individual song from the player menu. The selection remains active until changed back to automatic.

### Look and Feel

* **Material 3 Expressive** design with wavy progress components.
* Two player styles: **Default** and **Full Art**.
* **Player button shapes:** Round, Pill, or Apple Music style.
* Custom player button colours.
* Customizable backgrounds for the player, mini player, and menus:

  * Blur
  * Transparent
  * Gradient
  * Opaque
* Adjustable roundedness for song thumbnails.
* Redesigned bottom navigation bar with a subtle fade into the content above.
* Home screen loading animation that smoothly fades content in when ready.

### More

Interlude also includes features from Metrolist, including:

* Listen Together
* Discord Rich Presence
* Last.fm scrobbling
* Google Cast
* And more

See the in-app settings for the complete list of features and options.

## Build from Source

You need a JDK and the Android SDK. Android Studio can install and configure both for you.

```bash
git clone https://github.com/naklirajveerfr/NakliMusic.git
cd NakliMusic
./gradlew assembleFossDebug
```

The first build may take several minutes.

The APK will be generated at:

```text
app/build/outputs/apk/foss/debug/
```

### Install on Android

With USB debugging enabled on your device:

```bash
adb install -r app/build/outputs/apk/foss/debug/*.apk
```

If multiple APKs are present in the directory, install the desired APK by its filename instead of using `*`.

## Status

Interlude is actively being developed.

* **Seamless Transition** is currently in beta.
* **Musixmatch** lyrics are currently in beta.
* Tempo detection works with songs cached on the device. Songs that have not been cached yet fall back to standard crossfade behavior.
  

## Credits

* [Metrolist](https://github.com/MetrolistGroup/Metrolist) — the base project Interlude is built upon.
* [SimpMusic](https://github.com/maxrave-dev/SimpMusic) — community lyrics database.
* BetterLyrics — word-by-word lyrics, unison, and artwork.
* [MaterialKolor](https://github.com/jordond/MaterialKolor) — dynamic colour.
* ArchiveTune — UI inspiration.

## Contributing

Bug reports, suggestions, and ideas are welcome.

When opening an issue, please include:

* Your Android version
* What you expected to happen
* What actually happened
* Any relevant logs or screenshots

## License

Interlude is licensed under the **GPL-3.0**, the same license as Metrolist.

See the [LICENSE](LICENSE) file for details.
