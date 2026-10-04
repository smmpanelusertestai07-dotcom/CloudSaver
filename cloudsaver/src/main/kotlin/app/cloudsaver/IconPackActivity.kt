package app.cloudsaver

import android.app.Activity
import android.os.Bundle

/**
 * The door launchers knock on to find Ente Saver's icon pack. The pack is
 * res/xml/appfilter.xml and its pictures; a launcher reads those by name.
 * Some launchers start this screen when the pack is applied, so it simply
 * closes - it is not a screen anyone is meant to see.
 */
class IconPackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
