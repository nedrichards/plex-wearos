# Icon candidates

These generated raster concepts are exploration assets, not final launcher
resources. They are saved here so the final adaptive icon can be derived from a
chosen direction without relying on generated placeholders.

## Candidates

- `watch-play-waves.png`
  Strongest fit for the current app. It clearly reads as watch playback, uses a
  simple play mark, and has separable foreground shapes that can become adaptive
  foreground and monochrome layers. It may need simplification around the watch
  strap and wave arcs before becoming the production icon.

- `album-orbit.png`
  Best library/music concept. It reads more like music browsing than watch
  playback. The teal orbit is distinctive but may be too close to decorative
  detail once masked and scaled down.

- `session-control.png`
  Best remote-control concept. It is the simplest silhouette and should convert
  well to monochrome, but it undersells the watch-specific playback goal and
  could read as a generic media/network icon.

## Recommendation

Use `watch-play-waves.png` as the primary direction, then redraw it manually as
adaptive layers:

- dark, simple background
- off-white watch outline foreground
- amber play triangle and one or two wave arcs
- separate monochrome layer using the same silhouette

Keep the final mark simpler than the generated raster so it survives small Wear
launcher sizes and Android adaptive icon masking.

## Production use

The selected direction is now wired into the app resources:

- the density-specific `mipmap-*` WebP launcher fallbacks are derived from
  `watch-play-waves.png`
- adaptive launcher XML uses
  `drawable-nodpi/ic_launcher_foreground_watch_play_waves.png`
- `drawable/ic_launcher_background.xml` remains the adaptive background layer
- `drawable/ic_launcher_monochrome.xml` remains the themed icon layer

The original candidate remains here as the larger editable source.
