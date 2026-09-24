package com.pocketide.google

import android.content.Intent
import com.pocketide.AppGraph
import com.pocketide.model.LinkHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

fun createDriveAuth(graph: AppGraph): DriveAuth = StubDriveAuth().also { graph.hashCode() }

fun createDriveStore(graph: AppGraph): DriveStore = StubDriveStore().also { graph.hashCode() }

private class StubDriveAuth : DriveAuth {
    override val email: StateFlow<String?> = MutableStateFlow(null)
    override suspend fun authorize(): DriveAuthResult = DriveAuthResult.Failed("stub")
    override suspend fun completeConsent(data: Intent?): DriveAuthResult = DriveAuthResult.Failed("stub")
    override suspend fun token(): String = throw DriveException.Revoked()
    override suspend fun health() = LinkHealth.NOT_CONNECTED
    override suspend fun disconnect() = Unit
    override suspend fun authorizeNewAccount(): DriveAuthResult = DriveAuthResult.Failed("stub")
    override suspend fun tokenFor(email: String): String = throw DriveException.Revoked()
}

private class StubDriveStore : DriveStore {
    private fun no(): Nothing = throw DriveException.Revoked()
    override suspend fun list(): List<DriveFile> = no()
    override suspend fun find(name: String): DriveFile? = no()
    override suspend fun upload(name: String, source: File, existingId: String?): DriveFile = no()
    override suspend fun uploadBytes(name: String, bytes: ByteArray, existingId: String?): DriveFile = no()
    override suspend fun download(id: String, sink: OutputStream) = no()
    override suspend fun open(id: String): InputStream = no()
    override suspend fun delete(id: String) = no()
    override suspend fun quota(): DriveQuota = no()
    override fun withAccount(email: String): DriveStore = this
}
