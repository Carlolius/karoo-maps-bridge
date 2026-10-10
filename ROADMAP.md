# Roadmap and known limits

## Known limits

- **The Karoo can't receive connections.** Karoo OS blocks incoming connections over WiFi
  (tested on a Karoo 2), so the Karoo always asks the phone. That also rules out automatic
  discovery via mDNS.
- **Extensions can't load routes into native navigation.** karoo-ext only offers `LaunchPinDrop`
  for single points. Full routes go through the Hammerhead Companion app (*Upload route*).
- **Google Maps routes can't be copied exactly.** Shared directions links only carry the stops;
  BRouter recomputes the route.
- **Bonus action needs a remote.** On Karoo OS 1.613, extension bonus actions can only be
  assigned to external controller buttons (ANT+ remotes, Di2/AXS extra buttons); there is no
  in-ride menu entry. Without a remote, use the app.
- **Geocoding precision.** Android's geocoder usually finds the exact address; the OpenStreetMap
  fallback can stop at street level.

## Ideas

- Test on the current Karoo.
