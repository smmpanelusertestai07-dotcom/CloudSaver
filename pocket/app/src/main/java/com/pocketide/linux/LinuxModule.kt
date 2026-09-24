package com.pocketide.linux

import com.pocketide.AppGraph

fun createComputer(graph: AppGraph): Computer =
    ProotComputer(
        context = graph.context,
        dirs = graph.dirs,
        dataBudget = { graph.dataBudget },
        clock = graph.clock,
    )
