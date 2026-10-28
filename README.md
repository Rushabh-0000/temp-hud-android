# Temp HUD — Version 2

**V2 release baseline:** 1.0.0.

The root app is the standalone Version 2 codebase.

- Minimal full-screen UI: no in-app header, app logo, dashboard copy, or Start/Stop buttons.
- Grey CPU, GPU and CPU 0–7 tiles toggle their overlay rows; active tiles glow green.
- Overlay starts automatically when any metric or sensor row is enabled, and stops when nothing remains selected.
- CPU averages cpuss-0-usr and cpuss-1-usr. GPU averages gpuss-0-usr and gpuss-1-usr. Individual CPU 0–7 tiles map to cpu-1-0-usr through cpu-1-7-usr; cpu-0-0-usr through cpu-0-3-usr stay selectable as separate thermal inputs, not extra cores. Built-in tiles use -usr sources; All sensors retains every discovered type including -step.
- All sensors uses a two-column grid of cards; long-press a card to edit its overlay label or reset it to the source's default name. Card and text colors run green through lime/yellow-green below 80°C, then yellow at 80°C, orange around 85°C, and red at 90°C. When expanded, every discovered sensor is polled live; collapsing the section stops the extra catalogue polling. Tap a card to toggle that source onto the overlay.
- Pause overlay hides and stops the overlay without clearing selected rows; Resume restores the previous selection. The first install stays off until a row is selected.
- CPU/GPU/core tiles heat-tint their backgrounds at the same 80°C threshold as All sensors. Text brightness uses the original 35–100% slider and original brightness curve, without forced glow or extra color gain. Locking or unlocking preserves the exact visible overlay position; position sliders still move it intentionally. Portrait and landscape positions are saved separately, so rotating restores that orientation's last location.
- Overlay settings are at the bottom: refresh interval, scale, text brightness, background opacity, position and lock.
- Sensor reads run off the UI thread. The Activity sampler stops while hidden; the overlay sampler stops when the service is destroyed.

Polling necessarily uses some power while active; zero battery drain cannot be guaranteed.
