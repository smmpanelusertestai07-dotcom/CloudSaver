package com.pocketide.bridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.pocketide.core.AppDirs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * [PhoneBridge] on filesystem Unix sockets, one per room, in the room's bridge directory (bound
 * into that room only, as /run/pocketide). That directory is writable from inside Linux, so the
 * socket is made and locked down in a private staging directory first and then renamed into
 * place: rename replaces whatever a room left there (an old socket, a file, a symlink) without
 * following it, and chmod never touches a path Linux could swap.
 */
internal class UnixPhoneBridge(
    private val dirs: AppDirs,
    private val ops: PhoneOps,
    parent: Job?,
    private val limits: PhoneLimits = PhoneLimits(),
) : PhoneBridge {
    private val scope = CoroutineScope(
        SupervisorJob(parent) + Dispatchers.IO +
            CoroutineExceptionHandler { _, error -> Log.w(TAG, "A phone bridge request failed.", error) },
    )
    private val rooms = ConcurrentHashMap<String, RoomSocket>()

    override fun start(agentId: String) {
        require(AGENT_ID.matches(agentId)) { "\"$agentId\" is not an agent id." }
        synchronized(rooms) {
            val current = rooms[agentId]
            if (current != null && current.isListening()) return
            current?.close()
            val room = RoomSocket(agentId)
            try {
                room.open()
                rooms[agentId] = room
            } catch (e: IOException) {
                rooms.remove(agentId)
                Log.w(TAG, "The phone bridge for $agentId could not start.", e)
            }
        }
    }

    override fun stop(agentId: String) {
        synchronized(rooms) { rooms.remove(agentId)?.close() }
    }

    override fun handle(op: String, handler: suspend (agentId: String, args: JsonObject) -> JsonElement) {
        ops.register(op, handler)
    }

    private inner class RoomSocket(private val agentId: String) {
        private val bound = LocalSocket()
        private val clients: MutableSet<LocalSocket> = ConcurrentHashMap.newKeySet()
        private val roomScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
        private val socketFile = File(dirs.roomBridge(agentId), PhoneBridge.SOCKET_NAME)

        @Volatile private var server: LocalServerSocket? = null
        @Volatile private var socketInode = -1L
        @Volatile private var closed = false

        /** Callers hold the rooms lock, so one fixed staging name is enough (and stays short). */
        fun open() {
            val staged = File(File(dirs.base, STAGING_DIR).apply { mkdirs() }, STAGED_NAME)
            staged.delete()
            socketFile.parentFile?.mkdirs()
            try {
                bound.bind(LocalSocketAddress(staged.path, LocalSocketAddress.Namespace.FILESYSTEM))
                val listening = LocalServerSocket(bound.fileDescriptor)
                server = listening
                Os.chmod(staged.path, OWNER_READ_WRITE)
                socketInode = Os.lstat(staged.path).st_ino
                Os.rename(staged.path, socketFile.path)
                roomScope.launch { acceptLoop(listening) }
            } catch (e: ErrnoException) {
                close()
                throw IOException("The room's phone socket could not be set up.", e)
            } catch (e: IOException) {
                close()
                throw e
            }
        }

        /** False once closed, or when the room deleted or replaced the socket file. */
        fun isListening(): Boolean = !closed && isOwnSocketFile()

        fun close() {
            closed = true
            // Closing does not wake a thread blocked in accept(); shutting the socket down does.
            bound.fileDescriptor?.let { fd ->
                try {
                    Os.shutdown(fd, OsConstants.SHUT_RDWR)
                } catch (e: ErrnoException) {
                    // Not listening yet, or already shut.
                }
            }
            server?.let(::closeQuietly)
            closeQuietly(bound)
            clients.forEach(::hangUp)
            roomScope.cancel()
            // Only the file this socket made: never one a room put in its place.
            if (isOwnSocketFile()) socketFile.delete()
        }

        private fun isOwnSocketFile(): Boolean = try {
            Os.lstat(socketFile.path).st_ino == socketInode
        } catch (e: ErrnoException) {
            false
        }

        private fun acceptLoop(listening: LocalServerSocket) {
            while (!closed) {
                val client = try {
                    listening.accept()
                } catch (e: IOException) {
                    return
                }
                admit(client)
            }
        }

        private fun admit(client: LocalSocket) {
            // Only this app's own processes (Linux runs as the app's user) may use it.
            val sameApp = try {
                client.peerCredentials.uid == Process.myUid()
            } catch (e: IOException) {
                false
            }
            when {
                !sameApp -> hangUp(client)
                clients.size >= limits.maxConnectionsPerRoom -> turnAway(client)
                else -> {
                    clients += client
                    if (closed) hangUp(client)
                    roomScope.launch { serve(client) }
                }
            }
        }

        private suspend fun serve(client: LocalSocket) {
            try {
                PhoneSession(agentId, client.inputStream, client.outputStream, ops, limits).serve()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // The client left; its requests ended with it.
            } finally {
                clients -= client
                hangUp(client)
            }
        }

        private fun turnAway(client: LocalSocket) {
            val busy = buildJsonObject {
                put("id", JsonNull)
                put("ok", false)
                put("error", "The phone bridge is busy: ${limits.maxConnectionsPerRoom} connections are open. Try again in a moment.")
            }
            try {
                client.outputStream.write((busy.toString() + "\n").toByteArray(Charsets.UTF_8))
            } catch (e: IOException) {
                // It left first.
            }
            hangUp(client)
        }
    }

    private companion object {
        const val TAG = "PocketBridge"
        const val STAGING_DIR = "bridge-staging"
        const val STAGED_NAME = "phone.sock"
        const val OWNER_READ_WRITE = 0x180 // 0600

        /** Built-in ids and Open VSX ids ("<namespace>.<name>"): one safe path segment. */
        val AGENT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        /** Shutting down first wakes a thread blocked reading this socket. */
        fun hangUp(socket: LocalSocket) {
            for (shut in listOf(socket::shutdownInput, socket::shutdownOutput)) {
                try {
                    shut()
                } catch (e: IOException) {
                    // Already shut, or never connected.
                }
            }
            closeQuietly(socket)
        }
    }
}
