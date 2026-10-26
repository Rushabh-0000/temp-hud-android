# Temp HUD — Version 2

The root project is a clean V2 rebuild. The original Android project is preserved under [version-1/](version-1/); V2 reuses only the thermal reader, overlay renderer, and overlay settings concepts.

- Minimal full-screen UI with no app header/logo or extra dashboard copy.
- CPU, GPU and CPU 0–7 tiles begin grey; tapping a tile toggles it and active tiles glow green.
- The overlay starts automatically when at least one tile, picked sensor or custom row is enabled; it stops when nothing remains selected. No Start/Stop buttons.
- CPU averages cpuss-0-usr and cpuss-1-usr. GPU averages gpuss-0-usr and gpuss-1-usr. Only zone types ending in -usr are read; -step is ignored.
- The All sensors list has a manual Refresh action and allows sensor rows to be toggled onto the overlay.
- Custom rows let a sensor be assigned a custom label.
- Settings include refresh interval, scale, text brightness, background opacity, position and position lock.
- Sensor reads run off the UI thread. The Activity sampler stops when the screen is hidden; the overlay sampler stops when the overlay service is destroyed.

Polling uses some power while active; zero battery drain cannot be guaranteed.
