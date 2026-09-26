package com.pocketide.cloud

import com.pocketide.AppGraph
import com.pocketide.github.restClient

fun createComputers(graph: AppGraph): Computers = LiveComputers(
    api = CodespacesRest(restClient(graph)),
    gitHub = graph.gitHub,
    clock = graph.clock,
)
