# Temp HUD — Version 2

The root app is a clean rebuild. Original Version 1 source and project files remain archived in [version-1/](version-1/). V2 reuses the thermal reader, overlay renderer, and overlay settings concepts only.

- Minimal full-screen UI: no in-app header, app logo, dashboard copy, or Start/Stop buttons.
- Grey CPU, GPU and CPU 0–7 tiles toggle their overlay rows; active tiles glow green.
- Overlay starts automatically when any metric, sensor row or custom row is enabled, and stops when nothing remains selected.
- CPU averages cpuss-0-usr and cpuss-1-usr. GPU averages gpuss-0-usr and gpuss-1-usr. Only zone types ending in -usr are read; -step is ignored.
- All sensors shows live values and has a manual Refresh action. Tap a source row to toggle it onto the overlay.
- Custom rows let a selected source be displayed under a user-defined name.
- Overlay settings are at the bottom: refresh interval, scale, text brightness, background opacity, position and lock.
- Sensor reads run off the UI thread. The Activity sampler stops while hidden; the overlay sampler stops when the service is destroyed.

Polling necessarily uses some power while active; zero battery drain cannot be guaranteed.
