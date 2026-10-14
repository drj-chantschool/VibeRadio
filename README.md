# VibeRadio

A low-touch audio player that works like a radio, not a jukebox.

VibeRadio builds a few channels from the genres in your library. You pick a channel, and that's it: no scrolling, no seeking, no skipping to the next best thing. You get one coarse knob, and you get what you get.

The goal is to break the fast-dopamine loop of endless browsing and skipping, and to retrain the habit of just listening.

## Concept

- **A few channels, not a library browser.** Channels are generated from the diversity of genres in your collection.
- **One control.** Turn the knob to change channel. There's no track list to dig through and no skip button.
- **Channels keep playing.** Turn back to a channel and you rejoin the song it was playing, part-way through, as if it never stopped. Podcasts pick up exactly where you left off.
- **Music and podcasts on the same dial.** Some channels are vocal-only, some are music-only, and some mix both.

## Planned features

- **Volume balancing.** Loudness is normalized automatically between tracks.
- **Podcast and music mixing.** Podcasts and music are combined automatically on mixed channels.
- **Channel types.** Each channel is vocal-only, music-only, or mixed.
- **Queue fallback.** When the podcast queue runs out, the player moves to a music-only channel after the current track finishes.
- **Ducking and voice emphasis.** When a podcast and music play together, the music ducks under speech and the podcast voice is emphasized.
- **Podcast music detection.**
  - If an episode is itself music, it goes to a podcast-only channel by default, so the music stream doesn't play over it.
  - If an episode has background music, you choose per podcast whether it goes to a podcast-only channel or has its background music suppressed.

Progress on each feature is tracked in [Issues](https://github.com/Drj-chantschool/viberadio/issues).

## Look and feel

The interface is minimal, with a subdued, low-brightness palette that feels closer to an e-ink tablet than a typical media app.

## Status

Early planning. No code yet. Target platform: **Android**.

## License

[MIT](LICENSE)
