package com.bettercontent.playertraces.compat

import com.bettercontent.playertraces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Optional Better Content Threads bridge; no hard mod dependency. */
object ThreadsBridge {
    private data class ReturnState(val dimension:String,val origin:BlockPos,var armed:Boolean=false)
    private val returns=mutableMapOf<UUID,ReturnState>()

    fun traceCommitted(player:ServerPlayer){
        emit(player,"trace_commit","footprint")
        returns.putIfAbsent(player.uuid,ReturnState(player.serverLevel().dimension().location().toString(),player.blockPosition()))
    }

    fun checkReturn(player:ServerPlayer,storage:TraceStorageManager){
        if(player.tickCount%20!=0)return
        val state=returns[player.uuid]?:return
        val dimension=player.serverLevel().dimension().location().toString()
        if(dimension!=state.dimension){state.armed=true;return}
        val center=player.blockPosition()
        if(center.distSqr(state.origin)>16.0*16.0){state.armed=true;return}
        if(!state.armed)return
        val oldOwnTrace=storage.queryTraces(BlockPos(center.x-8,center.y-8,center.z-8),BlockPos(center.x+8,center.y+8,center.z+8)).any{
            it.sourcePlayerInternal==player.uuid&&it.createdAt<=player.serverLevel().gameTime-6000
        }
        if(oldOwnTrace){returns.remove(player.uuid);emit(player,"trace_return","own_old_trace")}
    }

    fun forget(player:ServerPlayer){returns.remove(player.uuid)}
    private fun emit(player:ServerPlayer,type:String,value:String){
        try{
            val api=Class.forName("com.bettercontent.bettercontentfixes.threads.ThreadSignals")
            api.getMethod("emit",ServerPlayer::class.java,String::class.java,String::class.java).invoke(null,player,type,value)
        }catch(_:ClassNotFoundException){}catch(_:NoSuchMethodException){}catch(_:ReflectiveOperationException){}
    }
}
