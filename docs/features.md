# Features

Principle: you still have control, but you shouldn't need it, and the app doesn't encourage using it in an unhealthy way. Defaults should be right the first time. (Pitch wording "you get what you get" stays.)

- Auto volume balancing between tracks
- Auto mixing podcast and music
- Some channels are vocal only, some music only, others mixed
- Default channels (renamable in Settings → Channels; saving a name empty restores the default): The Breeze (easy listening that disappears while you work, good podcast background, low stress), The Buzz (energy), The Zone (focus), The Breath (chant, sacred music, relaxing), The Hall (music for the sake of music; heady stuff), The Shuffle (all) (the "All songs" channel), The Voice (podcast only). These replace the genre channels: until learning places songs, the five mood channels hold the genre-generated channels in order, songs unchanged. A channel with nothing on it stays off the dial
- Media notification (and lock screen): play/pause plus channel down and up, chevrons in the slots skip buttons would take. They step the dial one station either way in dial order, wrapping past the ends (the notification shows no dial, so an end would be a dead button), and tune and play like a knob release, with the usual resume rules. Still no skip, seek or track list there
- Settings → Bluetooth: "Resume on Bluetooth connection" toggle, default on. When a Bluetooth audio device connects, playback resumes on the channel that was last playing, using the normal resume rules (songs: live-radio resume; podcasts: saved position)
- "All songs" channel: every music track in the library, excluding anything over 20 minutes. No podcasts, including podcast episodes detected as music
- When podcast queue exhausted, transition to a music-only channel after current track is over
- Songs and podcasts are independent pipelines. They share only the current channel and the final audio mix. Keep them unentangled for now
- Two pages, swiped between: one controls songs, one controls podcasts

## Podcast page

- Under each podcast: a checkbox per channel selecting which channels the podcast can play on (more control than songs, since podcasts are curated, long-form and limited)
- Under each podcast: a "Pause music" slider toggle. On means the music pauses while this podcast's episodes play. Podcasts with it on are called "music podcasts", though a user may turn it on for any reason. A music podcast can be assigned to any channel, including music channels
- Per-podcast playback speed, set on the podcast page alongside channel assignment. Defaults to 1x, remembered per podcast, applies to every episode of that podcast on every channel. Pitch is preserved. Affects only the podcast pipeline; song playback speed is never changed
- Detect if a podcast episode has its own music track or is itself music (not just intro/outro). A podcast detected as itself music defaults to "Pause music" on so the music stream doesn't stomp on it. If the podcast has background music, the user decides per podcast whether to turn on "Pause music" or suppress the podcast background music
- One podcast queue for the whole app: an episode played on one channel won't play again on another channel the podcast is also assigned to

## Podcast scheduling on a channel (applies to all podcasts)

- Fresh on a channel (tuned in, or after an episode that failed to play), an episode only starts at a music track boundary: it waits until the current music track ends
- Music podcast: when the current music track ends and the next episode up is from a music podcast, the music queue does not continue; the episode plays by itself. When the episode ends, the music queue resumes
- Gap between episodes: after an episode ends, music continues for as long as the gap rule says. The rule is one of a list, each with its own parameters, chosen in Settings → Podcasts (that setting shows in developer mode only; the choice stays in force when developer mode is turned off). Default: tracks by episode length
  - Tracks by episode length (default): the next episode waits for the current track to finish. If the last episode was longer than 15 minutes, one more full track plays after the current one before the next episode; if longer than 30 minutes, three more full tracks. Parameters: both thresholds (minutes) and both track counts
  - Music to podcast ratio: T is the podcast time played back to back so far (playing time, so speed counts; pauses don't). The music runs for a nominal ratio × T (ratio 0–1, default 0.5), capped at the max gap (default 10 minutes; it can't be set below the floor). If a track boundary falls within ± the tolerance (default 10%) of the nominal time, the next episode starts there, letting the current track finish; otherwise it starts part-way through the song at the nominal time. A nominal gap under the floor (default 10 s) means no gap: the next episode follows straight on and its time adds to T (three 5-minute episodes back to back make T = 15 minutes). Any real music gap starts T over
  - Music podcasts always start at a track boundary (they replace the music, so they never cut a song), whatever the rule: under the ratio rule, one due part-way through a song waits for the song to end
- Settings → Podcasts is open to everyone and is where user-facing podcast settings will go; for now, without developer mode, it only points to the podcast page. In developer mode it shows the gap rule: it lists the rules (one selected), then the selected rule's parameters, each edited in a dialog with its range (invalid values refused), and "Restore defaults" (every rule's parameters; the selection stays). Each rule keeps its own parameters while another is selected

## Audio chain

- Each stream (podcast, music) goes through its own gain stage, then its own tone EQ, then its own separation EQ, then the two are mixed. The streams are the two players: music is the session player (songs, and also episodes on podcast-only channels and music podcasts), podcasts are shows played over music
- Auto track leveling, songs and podcasts: each song or episode is measured once (integrated loudness, BS.1770 LUFS, plus peak) and played at one fixed gain that brings it to the target loudness. Not compression; the gain never changes within an item. Boosts stop before the peak passes −1 dBFS, so nothing clips. Results are cached (songs by file hash, episodes by ID); downloaded episodes are measured ahead, streamed ones play unadjusted. An item not yet measured when it starts plays unadjusted; its level is ramped in if it arrives within 15 s, otherwise used from its next play
- Mixing factor: while a podcast plays over music, the music drops by the mixing factor (ramped)
- Tone EQ (user-facing): one 10-band graphic EQ per stream, always on, flat by default. Bands at 31, 62, 125, 250, 500 Hz and 1, 2, 4, 8, 16 kHz, ±12 dB each: the middle eight are one-octave peaking filters, 31 Hz and 16 kHz are low and high shelves (half-gain corner on the edge with the next band in). A preamp of −(largest boost) is applied automatically, so a boost never clips; it sits after leveling, so the −1 dBFS rule still holds. A band change glides (about 48 dB/s, coefficients recomputed every 32 frames), never zippers
- Separation EQ: how the two streams are pulled apart in frequency while both play. A swappable module (`SeparationEq`), chosen in developer mode; each owns how it ramps:
  - Band pass and notch (default): the podcast through a band pass centred at 2 kHz; the music through a band gap (notch) centred at 2 kHz, wider than the podcast band pass and a partial cut rather than full removal. Ramps by crossfading dry and filtered signal
  - Graphic: per-band dB offsets on the tone EQ's ten bands, one set per stream, added to that stream's tone curve: final band gain = tone + offset × ramp. Offsets ±24 dB. Defaults, roughly the band pass and notch: music −9 dB at 1, 2 and 4 kHz, −4 dB at 500 Hz and 8 kHz, 0 elsewhere; podcast −12 dB at 31–250 Hz, −6 at 500 Hz, 0 at 1–4 kHz, −6 at 8 kHz, −12 at 16 kHz. Neighbouring bands overlap, so the music's cut around 2 kHz comes out deeper than −9 dB (about −13); a graphic EQ can't make a true band pass, so the podcast gets a voice-forward tilt. That difference is what comparing the two modules is for
- Only one stream active: separation is flat (whatever the module); the tone EQ still applies
- A music podcast playing by itself counts as one stream active (no separation, no mixing factor)
- Separation and mixing factor changes ramp when a stream starts or stops, so the sound never snaps. Switching module, or changing its figures, while both streams play ramps the current one out, then the new one in; while separation is flat, the switch is immediate
- Settings → Audio: target loudness, mixing factor and ramp time are adjustable, with a "Restore defaults" option (these three; tone curves and separation keep their own). Defaults:
  - Target loudness: −18 LUFS
  - Mixing factor (music under a podcast): −10 dB
  - Ramp time (separation and mixing factor): 1.5 s
- Settings → Audio → Equalizer: the tone curve for each stream (Music, Podcasts), band by band, each edited in a dialog (±12 dB; invalid values refused), with "Restore defaults" (both flat). Available to everyone but tucked a level down, in line with the product principle
- Settings → Audio → Separation EQ (developer mode only), same layout as the gap rule: the modules (one selected), then the selected module's parameters, each edited in a dialog with its range (invalid values refused), and "Restore defaults" (every module's parameters; the selection stays). Each module keeps its own parameters while another is selected, and the choice stays in force with developer mode off. Band pass and notch defaults:
  - Podcast band pass: 1 kHz – 4 kHz
  - Music notch: 700 Hz – 5.7 kHz
  - Music notch depth: −9 dB
- Settings → Audio → "Library folders": which folders are in the library. Shows only folders that hold audio (songs or MediaStore podcasts, as scanned) and the folders on the way to them; a folder holding nothing but one subfolder folds into it ("Music/Albums"). Each row has a checkbox (in, partly in, out) and its track count; everything is in by default, and the user unchecks what shouldn't be there. Unchecking a folder leaves out it and everything under it, including subfolders added later. Checking a subfolder of an unchecked folder puts back only that subfolder; the rest of the parent stays out. Tapping a partly-in folder puts all of it back. Left-out tracks are on no channel (The Shuffle included) and learn nothing; their feedback and history stay in the log. A change rebuilds the channels from the last scan straight away; the song playing finishes, and a queued one may still play

## Songs

- Learning, songs only (see [learning.md](learning.md)): song channels and the default channel are learned from per-song audio feature vectors plus a timestamped thumbs up/down log, sensitive to seasonal listening habits
- Thumbs up / thumbs down on the current song (thumbs down does not skip); long press thumbs down for "Wrong channel"
- Thumbs buttons are momentary, not song state. A press lights the button only for the rest of the current play; until the play ends, pressing the lit thumb again cancels it and pressing the other thumb switches to it. When the play ends, the thumb still lit (if any) is appended to the log (timestamp, channel) and the buttons reset to neutral, so every play starts neutral, even for a song that has been thumbed before. A cancelled or replaced press is never logged. A song can collect any number of ups and downs, or both, over time; the button never reflects past plays
- Every song in the library is on at least one channel; the All songs channel counts. No in-app deletion
- MIDI files (.mid, .midi, .kar, .rmi) are skipped at library scan and are not songs. Filter by MIME type (audio/midi, audio/x-midi, audio/sp-midi) or extension, since MediaStore lists them as audio
- Song shuffle: no passes. Each next song is an independent random draw from the whole channel, weighted p × (p × age)^1.5. age = time since the song was last heard (keyed on file hash), measured on a listening clock that only runs while songs play, in channel cycles (a cycle = the channel's total running time), capped at a large 20 cycles; never heard counts as 1 cycle. p = preference from the learning model (see [learning.md](learning.md)), floor 0.1. A just-played song can't repeat back to back, nothing is starved, and play frequency is roughly proportional to p (so up to ~10:1 between favorite and least liked)
- Live-radio resume, songs only: each channel remembers its last song (file hash), duration, and wall-clock start time. On tuning in, if now < start + duration, resume that song at (now − start) with a short fade-in; otherwise shuffle as usual. Fall back to shuffle if the clock went backward, the file is gone, or the song is no longer on that channel (e.g. after "Wrong channel")
- Podcasts resume where you left off: an episode's playback position is saved and playback continues from that point. No wall-clock catch-up, so an episode is never marked played unless it was actually heard

## Diagnostics (Phase A: single tester, on-device)

- Purpose: expose the app's data for analysis (by Claude through the Google Drive connector or the share sheet, or a PC notebook). Not a user feature; hidden behind developer mode
- Developer mode: off by default. Turned on by tapping the version line at the foot of Settings 7 times (Android convention). The last 3 taps count down in a toast; a pause over 3 s starts the count over. Once on, Settings shows a Debug page; its last switch turns developer mode back off, which hides the page and stops the nightly export. Anything already exported stays where it is
- Settings → Debug → "Diagnostics folder": pick a folder once with the system folder picker (Storage Access Framework). The app keeps the permission. No accounts, tokens or network code in the app; the folder's document provider (e.g. Google Drive, if it offers folder access) does any upload
- Settings → Debug → "Export now": writes one bundle into the chosen folder. The row shows how the last export went (saved, ready to share, or why it failed)
- Settings → Debug → "Share export": exports, then opens the share sheet with every file as an attachment (Drive, the Claude app, email). Works with no folder chosen; the fallback if Drive doesn't offer folder access
- Settings → Debug → "Export nightly" switch, default off, only with developer mode: once a day while charging and idle (JobScheduler, survives reboots)
- Bundle: one subfolder per export, named by local time (e.g. 2026-10-15_081203), so the latest is the last by name. Text formats that read without tools:
  - README.md: what each file holds
  - summary.json: app version, export time, feature layout version, counts, songs per channel, feedback date range
  - settings.json: audio settings (both tone curves, with the band frequencies), the separation EQ (selected module and every module's parameters), the podcast gap rule (selected rule and every rule's parameters), Bluetooth setting, folders left out of the library, channel names (ID, default and current name)
  - feedback.csv: every thumbs event (hash, thumb, time, local time, UTC offset, channel ID, separation EQ module in effect at the press: its ID while a podcast played over the music, empty otherwise or for presses from before it was recorded), log order
  - play_history.csv: per song, last played (wall clock) and listening-clock reading
  - channel_airings.csv: what each song channel last aired
  - features.csv: hash + feature vector rebuilt from the log, with named columns
  - channels.csv: current placement, one row per (channel ID, hash)
  - library.csv: every song on a channel: hash (empty until it has played once), title, artist, album, genre, duration, content kind, folder, URI
  - levels.csv: measured loudness and peak per song and downloaded episode, with the gain at the current target
  - podcasts.json: the podcast library as the app stores it (shows, channel IDs, Pause music, speed, played, resume positions)
- Personal data (titles, artists) is included in Phase A, since the folder is the user's own. Phase B (more testers) sends the same bundle to a backend instead, with personal fields hashed or stripped by default
- Logs from the Similarity plan's Phase 0 (Wrong channel events, play log, tune log) join the bundle as new CSVs when they exist
