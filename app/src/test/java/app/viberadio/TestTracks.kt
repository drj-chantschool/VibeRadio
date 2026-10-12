package app.viberadio

import app.viberadio.library.ContentKind
import app.viberadio.library.Track

private var nextId = 1L

fun track(genre: String?, kind: ContentKind = ContentKind.MUSIC): Track {
    val id = nextId++
    return Track(
        id = id,
        uri = "content://media/external/audio/media/$id",
        title = "Track $id",
        artist = null,
        album = null,
        genre = genre,
        durationMs = 180_000,
        kind = kind,
    )
}

fun tracks(genre: String?, count: Int): List<Track> = List(count) { track(genre) }
