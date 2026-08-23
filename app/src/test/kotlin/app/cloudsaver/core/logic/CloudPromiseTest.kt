package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudPromiseTest {

    private fun promiseFor(id: String) =
        CloudPromise.forCloud(id, CloudCapability.defaultsFor(id))

    @Test
    fun `a cloud that frees up and de-duplicates can be trusted exactly`() {
        assertEquals(CloudPromise.Promise.EXACT, promiseFor("ente"))
        assertEquals(CloudPromise.Promise.EXACT, promiseFor("immich"))
    }

    @Test
    fun `a cloud without a duplicate check gets the honest ledger line`() {
        for (id in listOf("mega", "nextcloud", "proton", "filen", "onedrive")) {
            assertEquals("$id should be ledger-only", CloudPromise.Promise.LEDGER_ONLY, promiseFor(id))
        }
    }

    @Test
    fun `an app we cannot inspect says so, rather than borrowing a better line`() {
        assertEquals(CloudPromise.Promise.UNKNOWN, promiseFor("other"))
    }

    @Test
    fun `every selectable cloud has a line`() {
        // A cloud with no promise line would render an empty row, which reads
        // as "nothing to worry about" - the one impression we must not give.
        for (app in app.cloudsaver.data.CloudApps.SELECTABLE) {
            promiseFor(app.id)
        }
    }
}
