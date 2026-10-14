package app.viberadio.ui

import android.content.Context
import app.viberadio.R
import app.viberadio.dial.ChannelLineup

/** Default names and one-line descriptions for the named channels ([ChannelLineup]), as string resource IDs. */
internal object LineupStrings {
    val names: Map<String, Int> = mapOf(
        ChannelLineup.BREEZE to R.string.channel_breeze,
        ChannelLineup.BUZZ to R.string.channel_buzz,
        ChannelLineup.ZONE to R.string.channel_zone,
        ChannelLineup.BREATH to R.string.channel_breath,
        ChannelLineup.HALL to R.string.channel_hall,
        ChannelLineup.SHUFFLE to R.string.channel_shuffle,
        ChannelLineup.VOICE to R.string.channel_voice,
    )

    val descriptions: Map<String, Int> = mapOf(
        ChannelLineup.BREEZE to R.string.channel_breeze_about,
        ChannelLineup.BUZZ to R.string.channel_buzz_about,
        ChannelLineup.ZONE to R.string.channel_zone_about,
        ChannelLineup.BREATH to R.string.channel_breath_about,
        ChannelLineup.HALL to R.string.channel_hall_about,
        ChannelLineup.SHUFFLE to R.string.channel_shuffle_about,
        ChannelLineup.VOICE to R.string.channel_voice_about,
    )

    fun defaultNames(context: Context): Map<String, String> =
        ChannelLineup.ids.associateWith { context.getString(names.getValue(it)) }
}
