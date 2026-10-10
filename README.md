# Karoo Maps Bridge

Send places and routes from Google Maps (and other map apps) on your Android phone to a
Hammerhead Karoo, straight into its **native** navigation.

Built for the Karoo 2, which lacks Hammerhead's own "share location from phone" feature.
Tested on a Karoo 2 (Karoo OS 1.613); it uses only the official SDK, so the current Karoo
should work too, but it has not been tested there.

> Unofficial community project. Not affiliated with or endorsed by Hammerhead, SRAM or Google.
> "Karoo" and "Hammerhead" are trademarks of their owners.

## What it does

**Places.** In Google Maps open a place or drop a pin, tap *Share* and choose *Send to Karoo*.
On the Karoo, open *Maps Bridge* (or use its bonus action): the phone's points are listed and
tapping one opens the Karoo's own "navigate to / save as POI" screen.

**Routes.** Share Google Maps *directions* and choose:

- *Send the stops*: every stop becomes a point, in order ("1/3 · Name").
- *Compute bike route*: up to 4 alternatives for each of three BRouter profiles (normal,
  fast, quiet / less traffic). Near-duplicates are dropped. They are drawn on a map, one colour
  each, with distance, climbing, time, surface (asphalt, cobbles, unpaved) and share of cycle
  lanes. *Upload route* hands the GPX to the Hammerhead Companion app, which syncs it to the
  Karoo as a native route with turn-by-turn.

Google Maps never shares the geometry of its routes, only the stops, so the route itself is
recomputed by BRouter. Add stops in Google Maps to make it follow the way you want.

**Other apps.** Anything that shares a `geo:` link, coordinates or a Google Maps link works:
WhatsApp locations, Apple Maps, Waze, OpenStreetMap, OsmAnd, OruxMaps, Organic Maps and
MAPS.ME (`omaps.app`, `ge0.me`, `maps.me/link/ge0` links), or plain address text.

| Shared | Coordinates |
|---|---|
| Dropped pin | exact (from the link) |
| Business / address | geocoded: Android's built-in geocoder, OpenStreetMap (Nominatim) as fallback |
| Directions | stops in order, or BRouter route alternatives |
| `geo:` links and other apps | exact |

## How the two apps talk

The Karoo always *asks* the phone: Karoo OS blocks incoming connections, so nothing can be
pushed to it. The phone app runs a small HTTP server (port 8730) that starts when you share
something and stops by itself (10 minutes after the Karoo fetched, or 2 hours if it never
does), or from its notification.

The Karoo finds the phone by trying, in order: a phone IP set by hand, the network gateway
(the phone itself when the Karoo is on its hotspot) and the last 5 phones that answered.
No accounts, no cloud server of ours.

Phone API: `GET /points` returns `{"points": [{"id", "lat", "lng", "name", "source", "timestamp"}, ...]}`,
newest first.

## Install

Download both APKs from the [latest release](https://github.com/Carlolius/karoo-maps-bridge/releases/latest).

- **Phone** (Android 8+): install `karoo-maps-bridge-phone-*.apk` (allow installing unknown
  apps). For *Upload route*, install the Hammerhead Companion app.
- **Karoo 2**: enable developer options (Settings, About, tap *Build number* 8 times), turn on
  USB debugging and run `adb install karoo-maps-bridge-karoo-*.apk`.
  [Guide](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html).
- **Current Karoo**: sideload the Karoo APK through the Companion app.

For internet on the ride, connect the Karoo to the phone's hotspot; it then finds the phone
automatically. On another network (home WiFi), type the phone's IP (shown in its notification)
in the Karoo app once.

## Privacy

Nothing is sent to us: there is no server of ours. Depending on what you share, the phone
contacts:

- Google, to resolve shared `maps.app.goo.gl` short links, and through Android's built-in
  geocoder to find the coordinates of an address.
- OpenStreetMap's Nominatim, only if Android's geocoder finds nothing.
- The public BRouter server (brouter.de), with the route's start and stops, when you compute a
  route; OpenStreetMap's tile server for the route map.

Location permission is only used to start a computed route where you are, when the Google Maps
directions started at "Your location". Points stay on the phone (last 20) until you delete them.

BRouter and Nominatim are free community services with fair-use limits; please don't hammer them.

## Build (no Android Studio needed)

Everything builds inside Docker. karoo-ext is on GitHub Packages, which needs a GitHub token
even for public packages: `TOKEN`/`USERNAME` env vars, or taken from `gh auth` automatically.

```sh
./build.sh                  # both debug APKs -> */build/outputs/apk/debug/
./build.sh test             # unit tests
./build.sh --release        # tests + signed release APKs in dist/ (see build.sh for signing)
./build.sh --purge          # remove the builder image and gradle cache
```

## License and credits

Apache-2.0, see [LICENSE](LICENSE).

- [karoo-ext](https://github.com/hammerheadnav/karoo-ext), Hammerhead's extension SDK (Apache-2.0).
- [Leaflet](https://leafletjs.com) 1.9.4 for the route map (BSD-2-Clause, bundled with its licence).
- The `ge0` link decoder is a port of Organic Maps' `libs/ge0/parser.cpp` (Apache-2.0).
- Routes by [BRouter](https://brouter.de); map tiles and route data © [OpenStreetMap](https://www.openstreetmap.org/copyright)
  contributors (ODbL).
