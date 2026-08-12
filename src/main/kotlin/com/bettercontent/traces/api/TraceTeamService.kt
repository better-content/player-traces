package com.bettercontent.traces.api

import com.bettercontent.traces.domain.GLOBAL_TEAM
import com.bettercontent.traces.domain.TraceTeam

interface TraceTeamService {
    fun teamFor(id: String?): TraceTeam
    fun canRead(playerTeam: TraceTeam, targetTeam: TraceTeam): Boolean
}

class DefaultTraceTeamService : TraceTeamService {
    override fun teamFor(id: String?): TraceTeam = GLOBAL_TEAM

    override fun canRead(playerTeam: TraceTeam, targetTeam: TraceTeam): Boolean = true
}
