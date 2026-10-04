package app.cloudsaver

import android.app.Activity
import android.os.Bundle
import app.cloudsaver.data.EnteApp

/**
 * Where the "Photos" home-screen shortcut lands: straight on into Ente
 * Photos, so the shortcut behaves as if it were Ente's own icon. When Ente is
 * not on the phone it opens Ente's page in the store instead of doing
 * nothing.
 */
class OpenEnteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!EnteApp.launch(this, errand = false)) {
            EnteApp.openInstallPage(this, EnteApp.Source.PLAY, errand = false)
        }
        finish()
    }
}
