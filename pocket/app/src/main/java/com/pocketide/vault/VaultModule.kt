package com.pocketide.vault

import com.pocketide.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createVaultKeys(graph: AppGraph): VaultKeys = StubVaultKeys().also { graph.hashCode() }

private class StubVaultKeys : VaultKeys {
    private fun no(): Nothing = throw IllegalStateException("stub")
    override val state: StateFlow<KeyState> = MutableStateFlow(KeyState.None)
    override suspend fun setUp() = no()
    override suspend fun restore(extraPassword: CharArray?): KeyState = no()
    override suspend fun rekey(reason: RekeyReason) = no()
    override suspend fun checkKeyring(): KeyringCheck = no()
    override suspend fun setExtraPassword(password: CharArray?) = no()
    override suspend fun exportKeyCopy(): String = no()
    override suspend fun importKeyCopy(text: String) = no()
    override fun cipher(): VaultCipher = no()
    override fun generation() = 0
}
