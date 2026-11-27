# Live Venice: real weather and real vaporetti

GTACTV is made for people who live in Venice, so the game world should follow the real city: when it rains on
the Fondamenta, it rains in the game, and the line 1 boat you see from your window is also in the game, on its line.

## 1. Weather — done (v1)

`app/src/main/java/.../live/Weather.kt` polls **Open-Meteo** (`api.open-meteo.com`, free, no API key,
CC BY 4.0) every 10 minutes for Rialto (45.4371 N, 12.3326 E):

| Open-Meteo field | Used for in the game |
|---|---|
| `temperature_2m` | HUD temperature |
| `weather_code` (WMO) | HUD icon + label, fog / rain / storm detection |
| `cloud_cover` | cloud cover in the sky shader, greyer fog + sky colours when overcast |
| `visibility` + fog codes 45/48 | fog density (thick *caligo* in real fog) |
| `wind_speed_10m`, `wind_direction_10m` | cloud drift direction/speed, open-water wave height |
| `wind_gusts_10m`, `precipitation`, `is_day` | read already; for rain particles, gusts and night lighting |

Turn it off in *Impostazioni → Meteo e mondo*. Without a connection the game uses the month's average temperature.

**Next:** rain/snow particles and wet surfaces (`isRain()`), gust bursts on the water, sun position + night lighting
from the real clock (`is_day`, sunrise/sunset), *acqua alta* from the tide gauge (Comune di Venezia, Punta della Salute).

## 2. Vaporetti — v2 (27 Nov)

* Real types at real size: motobattello 23.9 x 4.2 m, motoscafo 21.1 x 3.75 m (lines 3, 4.x, 5.x, 6),
  motonave/foraneo 31 x 5.5 m (lines 11-19) - `tools/procedural/vaporetti.py`.
* Motion: every fix becomes a destination; `core/Navigator` plans a water path (canals, clearance from the quays),
  the boat sails it at the speed that gets it there by the next fix, accelerating, braking and turning at a
  realistic rate. Fixes slightly behind the boat (GPS jitter) don't make it reverse. A stopped boat docks on the
  water side of its pontoon (a second boat queues behind). Overlaps (bad data only) ease apart.
* Offline: the timetable (stop patterns + departures from the cached GTFS) drives the same boats; built-in lines
  1 and 2 if the timetable was never downloaded.
* Sound: diesel throb, docking churn + thump + gangway clank, departure rev (`audio/Audio.kt`).

## 2a. Vaporetti — v1 (live positions)

Implemented in `live/Vaporetti.kt` + `render/LiveBoats.kt`, straight from **ACTV's own open data** (the same source
miniVenice uses; miniVenice's terms don't allow other apps to use its real-time service, so we never touch it):
* positions: GTFS-Realtime `https://tpl.actv.it/nav/GTFSRT/vehiclePositions`, polled every 15 s, decoded by a tiny
  hand-written protobuf reader (no library);
* line numbers / destinations: `routes.txt` + `trips.txt` from the GTFS zip
  `https://actv.avmspa.it/sites/default/files/attachments/opendata/navigazione/actv_nav.zip`, downloaded once a week;
* each boat is dead-reckoned along its bearing between fixes and eased, drawn with `assets/live/vaporetto.mesh`
  (`tools/procedural/vaporetto.py`), solid for the player's boat, and shown with its line number on the minimap and
  the full-screen map. Toggle: *Impostazioni → Meteo e mondo → Vaporetti ACTV in tempo reale*.

Still to do from the plan below: timetable simulation when offline (Layer A), different hull types per line, the line
number on the boat's own sign board, following the water paths instead of straight dead reckoning.

## 2b. Vaporetti — original plan

Two layers, so the boats are always there and become "real" whenever live data is available:

### Layer A — the timetable (offline, always works)
ACTV publishes the navigation timetable as open **GTFS** (agency *Navigazione*, 26 ferry routes — see
mobilitydatabase.org feed mdb-1063 / actv.avmspa.it open data). A converter step (like `tools/converter`) turns it
into `assets/actv_gtfs.bin`:
* `stops` → matched to our pontoons in `landmarks/actv.txt` (same names);
* `shapes` / stop sequences → water paths projected into map metres (`Places.project`);
* `stop_times` → per trip, the time it leaves each stop.

At runtime every trip running *now* (real Europe/Rome time) is placed by interpolating between its last and next
stop along the path, slowing into the pontoon and waiting the dwell time. That alone gives a lagoon full of boats on
the right lines at the right times.

### Layer B — live positions (when online)
The live tracking website you use shows every boat, its line and where it is. Once we know its address we look at
which data feed it loads (usually a JSON endpoint polled every few seconds) and add `live/Vaporetti.kt`:
poll it every 5–10 s, match each live boat to its GTFS trip (line + direction + nearest scheduled position) and
snap/blend the simulated boat to the reported position, so it glides instead of teleporting.
**Needed from you:** the website's address. (We must respect its terms of use; if it has no public API we stay on
Layer A.)

### Boat models (Blender or `tools/procedural/`)
| Type | Lines (typical) | Notes |
|---|---|---|
| *Motobattello* "vaporetto" (serie 80/90) | 1, 2, N | ~24 m, open aft deck, the classic one |
| *Motoscafo* | 4.1/4.2, 5.1/5.2, 3, 6 | lower, more streamlined, enclosed cabin |
| *Motonave* | 14, 17 (car ferry Tronchetto–Lido) | big double-deck ferries |
| *Alilaguna* (separate company) | airport lines | optional later |

Each boat shows its **line number** on the bow sign (same sign-sheet approach as the pontoon names:
`landmarks/actv_signs.png`) and follows real ACTV livery (white/grey hull, yellow-black accents).
Gameplay hooks later: collisions + wake from big boats, horns, riding along as a passenger, "follow line N" camera.
