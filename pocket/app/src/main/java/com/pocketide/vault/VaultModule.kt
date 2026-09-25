package com.pocketide.vault

import com.pocketide.AppGraph

fun createVaultKeys(graph: AppGraph): VaultKeys = VaultKeysImpl(
    phone = PhoneKeys(graph.secureStore),
    remote = RemoteKeys(graph.drive, graph.gitHub),
    account = graph.gitHubAuth.account,
    clock = graph.clock,
    onPasswordChanged = { on -> graph.settings.update { it.copy(extraPassword = on) } },
)
