package app.entesaver.ui.components

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import app.entesaver.core.logic.Defaults

/**
 * The system file picker, opened where the daily history file lives
 * (Documents/Ente Saver), so Restore on a new install is one tap on
 * history.json rather than a search. A picker that does not know the place
 * simply opens where it usually does; any file can still be chosen.
 */
class OpenHistory : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:${Defaults.HISTORY_DIR}"
            )
        )
}
