package app.entesaver

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.entesaver.core.logic.Evidence
import app.entesaver.data.db.AppDb
import app.entesaver.data.db.LedgerRow
import app.entesaver.data.db.record
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One delivered copy, one ledger entry, with the best proof it ever had. A
 * copy verified with its batch and proven on its own later (the confirm
 * return pass) used to keep VERIFIED, so a byte-identical copy made again or
 * a restored install was told less than this phone knew.
 */
@RunWith(AndroidJUnit4::class)
class LedgerRecordTest {

    private lateinit var db: AppDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDb::class.java
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entry(evidence: String, at: Long) = LedgerRow(
        outputSha256 = "sha-1",
        fingerprint = "fp-1",
        displayName = "IMG_1.jpg",
        outputBytes = 1_000L,
        evidence = evidence,
        confirmedAt = at
    )

    @Test
    fun aStrongerProofRaisesTheEntry() = runBlocking {
        db.ledger().record(entry(Evidence.VERIFIED.name, 1_000L))
        db.ledger().record(entry(Evidence.CONFIRMED_EXACT.name, 2_000L))
        val kept = db.ledger().all().single()
        assertEquals(Evidence.CONFIRMED_EXACT.name, kept.evidence)
        assertEquals(2_000L, kept.confirmedAt)
    }

    @Test
    fun aWeakerOrEqualProofNeverLowersIt() = runBlocking {
        db.ledger().record(entry(Evidence.CONFIRMED_PACED.name, 1_000L))
        db.ledger().record(entry(Evidence.VERIFIED.name, 2_000L))
        db.ledger().record(entry(Evidence.CONFIRMED_PACED.name, 3_000L))
        val kept = db.ledger().all().single()
        assertEquals(Evidence.CONFIRMED_PACED.name, kept.evidence)
        assertEquals(1_000L, kept.confirmedAt)
    }

    @Test
    fun anOldNameForTheStrongestProofIsNotRaisedOver() = runBlocking {
        // "CONFIRMED" is how early versions wrote CONFIRMED_EXACT.
        db.ledger().insert(entry("CONFIRMED", 1_000L))
        db.ledger().record(entry(Evidence.CONFIRMED_EXACT.name, 2_000L))
        assertEquals(1_000L, db.ledger().all().single().confirmedAt)
    }
}
