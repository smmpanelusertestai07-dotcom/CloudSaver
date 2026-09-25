package com.pocketide.linux

import com.pocketide.AppGraph
import com.pocketide.agents.AgentWorkers

fun createComputer(graph: AppGraph): Computer =
    ProotComputer(
        context = graph.context,
        dirs = graph.dirs,
        dataBudget = { graph.dataBudget },
        clock = graph.clock,
        // Claude Code, Codex and agy are installed into their rooms as soon as there is a computer.
        onReady = { AgentWorkers.installNow(graph.context) },
    )
