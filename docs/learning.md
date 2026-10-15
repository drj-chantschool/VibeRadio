# Learning (songs)

Scope: songs only. Podcasts run in a separate pipeline with manual channel assignment and no learning (#17, #18). The two pipelines share only the current channel and the final audio mix.

Goal: the app learns which songs belong together, which channel to start on, and how often the user wants to hear each song, from how the user actually listens. It never adds browsing or seeking.

## Inputs

- A feature vector per song, keyed on file hash (#12). Initial scaffold (layout v1): 12 features, the total thumbs up and thumbs down counts on each of the five mood channels (Breeze, Buzz, Zone, Breath, Hall) plus The Shuffle (all). Audio-derived features come later; a layout version bump rebuilds every vector. The vector is a derived cache; the log below is the source of truth.
- A timestamped log of every thumbs up and thumbs down on each song, recording the channel it happened on (#13). Channels are stored by stable ID, never by their renamable display name.

## Models

Two models share the one feature vector:

- Channel fit: reads the 10 mood-channel features to decide which channel(s) a song belongs on.
- Preference: reads all 12 features to decide how often the user wants to hear the song.

## What it decides

- Which songs share a channel (#14). A song can be on more than one channel.
- Which channel to default to at a given time (#15). The user can always turn the knob.
- How often each song comes up.

## Seasonal listening

Listening habits are seasonal, and the learner must respect that. A song thumbed up every December and thumbed down in July is a December song, not a disliked one. Feedback is weighted by closeness to the current time of year (and time of day/week), not only by recency. Out-of-season thumbs downs are discounted rather than treated as contradicting in-season thumbs ups. This is why feedback is kept as a log rather than a running score.

## Controls (song page)

- Short press thumbs up / thumbs down: feedback only. Thumbs down does not skip; the song plays to the end.
- Button state is per play, not per song. A press is lit until the track ends; meanwhile the same thumb again cancels it and the other thumb replaces it. When the track ends the thumb still lit is logged (at most one event per play) and both buttons reset to neutral. The next play of the same song starts neutral regardless of the log or the feature vector; neither is ever read back to set the button. A song accumulates any number of ups and downs, including both, across plays.
- Long press thumbs down: context menu with "Wrong channel" (#16). The song leaves the current channel; the app makes sure it's on some other channel first. Not offered on the All songs channel, whose membership is fixed by rule; there a long press is a plain thumbs down.

## Coverage rule

Every song in the library is on at least one channel. Learning moves songs, never drops them. The All songs channel counts, so songs up to 20 minutes are always covered. The app does not support deleting songs; a song only leaves when its file leaves the device library.

## Expected outcome

One channel will likely fill with songs the user only plays occasionally or when in the right mood. That's fine. VibeRadio isn't trying to serve every specific request; other audio apps cover that.
