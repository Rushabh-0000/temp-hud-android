# Temp HUD — Version 2

**V2 release baseline:** 1.0.0.

The root app is a clean rebuild. Original Version 1 source and project files remain archived in [version-1/](version-1/). V2 reuses the thermal reader, overlay renderer, and overlay settings concepts only.

- Minimal full-screen UI: no in-app header, app logo, dashboard copy, or Start/Stop buttons.
- Grey CPU, GPU and CPU 0–7 tiles toggle their overlay rows; active tiles glow green.
- Overlay starts automatically when any metric, sensor row or custom row is enabled, and stops when nothing remains selected.
- CPU averages cpuss-0-usr and cpuss-1-usr. GPU averages gpuss-0-usr and gpuss-1-usr. Only zone types ending in -usr are read; -step is ignored.
- All sensors uses a two-column grid of heat-tinted cards; temperatures progress from green to yellow/orange/red as they rise. When expanded, every discovered sensor is polled live; collapsing the section stops the extra catalogue polling. Tap a card to toggle that source onto the overlay.
- Pause overlay hides and stops the overlay without clearing selected rows; Resume restores the previous selection. The first install stays off until a row is selected.
- Custom rows let a selected source be displayed under a user-defined name.
- Overlay settings are at the bottom: refresh interval, scale, text brightness, background opacity, position and lock.
- Sensor reads run off the UI thread. The Activity sampler stops while hidden; the overlay sampler stops when the service is destroyed.

Polling necessarily uses some power while active; zero battery drain cannot be guaranteed.
