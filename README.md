# Venezia – a GTA-style boat game in a 1:1 Venice

Open-world speedboat game set in modern-day Venice (historic centre, Giudecca, San Giorgio, Murano, Lido),
built **from scratch in Kotlin** on Android's OpenGL ES 3.0 – no game engine, no third-party libraries.

**Download:** grab `Venezia.apk` from the [latest release](../../releases/latest) and install it on your phone.

## Controls
- **Touch:** drag on the left side to steer, hold **GAS** / **BRAKE** on the right, drag on the right side to look around.
- **Keyboard:** W/S throttle and brake/reverse, A/D steer (arrow keys work too).

## How it is built
- `tools/converter` – Kotlin tool that turns the raw map data into `app/src/main/assets/venice.map`
  (1:1 projection around the Campanile di San Marco, 256 m tiles, land masks, quay walls, buildings with heights,
  bridges, piers, bricole, benches, wells, trees…).
- `app/src/main/java/.../core` – pure Kotlin: map reader, city mesher, boat physics, collisions, math.
- `app/src/main/java/.../render` – OpenGL ES renderer: procedural Venetian facades, sun shadows, lagoon water, sky.
- `tools/preview` – dev tools that render the game's meshes with the same shaders in WebGL2 / Blender for checking.
- The red speedboat was modelled in Blender by script and exported to `boat.mesh`.

## Data sources and licences
- Map data © **OpenStreetMap contributors**, available under the Open Database License (ODbL).
  The derived database `venice.map` is therefore also made available under the **ODbL**.
- Building footprints: **Regione del Veneto – L.R. n. 28/76 – Formazione della Carta Tecnica Regionale**, IODL 2.0.
- Building heights: **Copernicus Land Monitoring Service**, Urban Atlas Building Block Height 2021 (European Union).
