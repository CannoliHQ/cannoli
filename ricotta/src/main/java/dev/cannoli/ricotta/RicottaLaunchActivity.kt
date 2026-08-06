package dev.cannoli.ricotta

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.cannoli.igm.RicottaLaunchParams

class RicottaLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val params = RicottaLaunchParams.readFromIntent(intent)
        if (params == null) {
            Toast.makeText(this, "Update required: launch protocol mismatch", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val retroIntent = RicottaLaunchTranslator.toRetroIntent(this, params).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // The launcher starts this shim without an animation, but the shim starting RetroArch is a
        // second transition, and left alone it plays the standard app-open animation on top of a
        // launcher that has none anywhere else.
        startActivity(retroIntent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        overridePendingTransition(0, 0)
        // Finish in onStop, after RetroActivityFuture has come to the front and taken
        // focus. Finishing here in onCreate drops the focus handoff and leaves the
        // NativeActivity without a focused window (input-dispatch ANR).
    }

    override fun onStop() {
        super.onStop()
        finish()
    }
}
