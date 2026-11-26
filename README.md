# GTACTV – a GTA-style boat game in a 1:1 Venice

Open-world speedboat game set in modern-day Venice (historic centre, Giudecca, San Giorgio, Murano, Lido),
built **from scratch in Kotlin** on Android's OpenGL ES 3.0 – no game engine, no third-party libraries.

**Download:** grab `GTACTV.apk` from the [latest release](../../releases/latest) and install it on your phone.
Every pull request is also built: open its *Checks → Build APK → Artifacts* to download a test APK.

## Controls
- **Steering:** drag the *timone* pad bottom-left (analog), or switch to two arrows in the settings.
- **Throttle (manetta):** drag the lever on the right; it stays where you leave it, notch in neutral, bottom = reverse;
  double-tap it for neutral. ⏻ turns the engine off.
- **Trim + / −:** outboard trim. Trimmed in (−) = best hole shot and grip in turns but more drag; trim out (+) once on
  the plane for more speed (green band = sweet spot, 140 km/h); too far out and the bow starts porpoising.
- **Minimap:** tap it for the full-screen map: pinch/drag, tap a spot → *NAVIGA* (waypoint) or *VAI QUI* (teleport).
- **Camera:** drag anywhere else; the camera button cycles chase / high / helm views.
- **Keyboard:** W/S throttle and brake/reverse, A/D steer (arrow keys work too).

## Settings worth knowing
- **Distanza visiva** (render distance, in chunks of 256 m): everything past it is unloaded and hidden in the fog —
  the single biggest performance knob on phones. Vegetation and close-up detail have their own shorter distances.
- **Risoluzione:** dynamic (never below 70 %) or fixed. **Prepara tutta Venezia** pre-builds every chunk to storage so
  nothing is meshed while you drive.
- **Meteo reale:** live Venice weather from Open-Meteo (see `docs/LIVE_VENICE.md`).

## How it is built
- `tools/converter` – Kotlin tool that turns the raw map data into `app/src/main/assets/venice.map`
  (1:1 projection around the Campanile di San Marco, 256 m tiles, land masks, quay walls, buildings with heights,
  bridges, piers, bricole, benches, wells, trees…).
- `app/src/main/java/.../core` – pure Kotlin: map reader, city mesher, boat physics, collisions, math.
- `app/src/main/java/.../render` – OpenGL ES renderer: procedural Venetian facades, sun shadows, lagoon water, sky.
- `tools/preview` – dev tools that render the game's meshes with the same shaders in WebGL2 / Blender for checking.
- The red speedboat was modelled in Blender by script and exported to `boat.mesh`.
- `tools/procedural` – Python generators that write `.mesh` files directly (no Blender needed): the Ponte della
  Costituzione and the Santa Lucia station, built from their published dimensions.

## Data sources and licences
- Map data © **OpenStreetMap contributors**, available under the Open Database License (ODbL).
  The derived database `venice.map` is therefore also made available under the **ODbL**.
- Building footprints: **Regione del Veneto – L.R. n. 28/76 – Formazione della Carta Tecnica Regionale**, IODL 2.0.
- Building heights: **Copernicus Land Monitoring Service**, Urban Atlas Building Block Height 2021 (European Union).
- Live weather: **Open-Meteo.com**, CC BY 4.0.
