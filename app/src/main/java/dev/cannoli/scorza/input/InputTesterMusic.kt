package dev.cannoli.scorza.input

import android.content.Context
import android.media.MediaPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.R
import javax.inject.Inject

/**
 * input_tester_bgm.ogg is "underwater" from Monster RPG 2 by Nooskewl Games, released CC0.
 */
@ActivityScoped
class InputTesterMusic @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var player: MediaPlayer? = null

    fun start() {
        if (player != null) return
        val created = MediaPlayer.create(context, R.raw.input_tester_bgm) ?: return
        created.isLooping = true
        created.start()
        player = created
    }

    fun stop() {
        player?.release()
        player = null
    }
}
