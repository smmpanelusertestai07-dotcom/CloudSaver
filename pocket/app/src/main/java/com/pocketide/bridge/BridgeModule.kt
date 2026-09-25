package com.pocketide.bridge

import android.os.Process
import com.pocketide.AppGraph
import kotlinx.coroutines.job

fun createPortBridge(graph: AppGraph): PortBridge =
    LoopbackPortBridge(parent = graph.scope.coroutineContext.job, listenerScan = ListenerScan(ownUid = Process.myUid()))

fun createPhoneBridge(graph: AppGraph): PhoneBridge {
    val ops = PhoneOps()
    ops.register(PhoneBridge.OPEN_URL, OpenUrlOp(AndroidBrowser(graph.context))::invoke)
    return UnixPhoneBridge(graph.dirs, ops, parent = graph.scope.coroutineContext.job)
}
