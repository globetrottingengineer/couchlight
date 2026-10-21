# Couchlight

**A Google TV / Android TV home screen that lights up what's on.** Couchlight strips the ads and bloat off an
Android TV over Wi-Fi and replaces the stock home with one scrolling row of apps under a full-screen backdrop
of what each app is playing, or what's trending on it, with the title, year, genres, rating and description,
and a **Watch now** button that opens the title inside its app.

Couchlight is a vibe-coded fork of [CleanYourTV](https://github.com/Gathin23/cleanyourtv) by Gathin, built in one
evening with Claude Code on a JMGO S91A projector. Everything CleanYourTV does (no root, nothing uninstalled,
a one-line restore, the debloat lists for 120+ TVs in [`examples/`](examples/)) still works; the original
README is in [docs/CLEANYOURTV-README.md](docs/CLEANYOURTV-README.md).

## What Couchlight adds

| | |
|---|---|
| **One scrolling row** | Every app sits in a single row that scrolls sideways (Google TV / Apple TV style), so every app gets the big backdrop, not just a dock of five. `single_row` |
| **Content backdrop** | The focused app's backdrop shows what it is **playing or has paused** (Android media sessions) or what is **trending on that service in your country** (TMDB), rotating every few seconds. Netflix, Prime Video, Apple TV, Disney+, ZDF and more. `content_shelf` |
| **Title details** | Under each title: *Series · 2025 · Drama, Comedy · ★ 7.8* and a two-line description, like Google TV's banner. Long titles shrink to fit instead of being cut off. |
| **Watch now** | Up on an app focuses the backdrop: Left / Right flip through its titles, OK opens the title's **own page in its app** (looked up on JustWatch: `tv.apple.com/…`, `netflix.com/title/…`, `watch.amazon.de/…`), falling back to the app's search, then the app. Down goes back. `content_cards` gives a row of title cards instead. |
| **Hold-OK app menu** | Open, Move, App info, View in Play Store, Remove from Home and, for apps you installed, Uninstall, floating over the tile like tvOS. |
| **Weather** | Temperature, sky, today's high and low and your city above the date, from Open-Meteo (no account). The place comes from the TV's connection, once a day. `weather` |
| **Looks** | Cinematic backdrop, time-of-day sky, a cleaner header, rectangle or round (`round_tiles`) tiles with or without the dock band (`dock_band`). |

All of these are switches in [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml)
and can be overridden by a theme's `config.json` ([themes/README.md](themes/README.md)).

## Quick start

Follow CleanYourTV's steps (developer options on the TV, then [`PROMPT.md`](PROMPT.md) with a coding assistant),
or build and install the launcher yourself:

1. Get a free TMDB key at <https://www.themoviedb.org/settings/api> (needed for trending; everything else works
   without it) and put it in `launcher/local.properties`, which git ignores:
   ```properties
   tmdb.api.key=your_key_here
   ```
2. Build and install: `launcher/build-and-install.sh` (macOS/Linux) or `launcher\build-and-install.ps1` (Windows),
   with your TV's address. The script also enables the media listener used for "Now playing".
3. Switch Developer options and network debugging off on the TV afterwards (see `PROMPT.md`).

## Privacy

Couchlight makes no requests until you give it a TMDB key. With one, it sends only these:
- **TMDB:** the trending query with your key and country.
- **JustWatch:** on OK, a lookup of that title's name.
- **ipapi.co:** your connection's IP, once a day, to place the weather.
- **Open-Meteo:** a rounded latitude and longitude for the weather.

It reads media sessions only to show what is playing, and reads no notifications.

## Credits and licence

MIT, like the original: see [LICENSE](LICENSE). CleanYourTV © 2026 Gathin; the Couchlight changes are released
under the same licence. Trending data and images come from [TMDB](https://www.themoviedb.org) ("This product
uses the TMDB API but is not endorsed or certified by TMDB"); title links from [JustWatch](https://www.justwatch.com);
weather from [Open-Meteo](https://open-meteo.com).
