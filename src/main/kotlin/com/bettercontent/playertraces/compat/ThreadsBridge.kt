package com.bettercontent.playertraces.compat

import com.bettercontent.playertraces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import java.util.UUID

/** Optional Better Content Threads bridge; no hard mod dependency. */
object ThreadsBridge {
    private const val ROOT="PlayerTracesThreadEpisode"
    private data class ReturnState(val dimension:String,val origin:BlockPos,val token:String,var armed:Boolean=false)
    private val returns=mutableMapOf<UUID,ReturnState>()

    fun traceCommitted(player:ServerPlayer){
        val state=state(player)?:ReturnState(player.serverLevel().dimension().location().toString(),player.blockPosition(),token(player)).also{returns[player.uuid]=it;save(player,it)}
        emit(player,"trace_commit","footprint",state.token)
    }

    fun checkReturn(player:ServerPlayer,storage:TraceStorageManager){
        if(player.tickCount%20!=0)return
        val state=state(player)?:return
        val dimension=player.serverLevel().dimension().location().toString()
        if(dimension!=state.dimension){if(!state.armed){state.armed=true;save(player,state)};return}
        val center=player.blockPosition()
        if(center.distSqr(state.origin)>16.0*16.0){if(!state.armed){state.armed=true;save(player,state)};return}
        if(!state.armed)return
        val oldOwnTrace=storage.queryTraces(BlockPos(center.x-8,center.y-8,center.z-8),BlockPos(center.x+8,center.y+8,center.z+8)).any{
            it.sourcePlayerInternal==player.uuid&&it.createdAt<=player.serverLevel().gameTime-6000
        }
        if(oldOwnTrace){returns.remove(player.uuid);clear(player);emit(player,"trace_return","own_old_trace",state.token)}
    }

    fun forget(player:ServerPlayer){returns.remove(player.uuid)}
    private fun state(player:ServerPlayer):ReturnState?=returns[player.uuid]?:run{
        val persisted=player.persistentData.getCompound(Player.PERSISTED_NBT_TAG)
        if(!persisted.contains(ROOT,Tag.TAG_COMPOUND.toInt()))return@run null
        val root=persisted.getCompound(ROOT);val dimension=root.getString("dimension");val token=root.getString("token")
        if(dimension.isBlank()||token.isBlank()||token.length>128)return@run null
        ReturnState(dimension,BlockPos.of(root.getLong("origin")),token,root.getBoolean("armed")).also{returns[player.uuid]=it}
    }
    private fun save(player:ServerPlayer,state:ReturnState){val persisted=player.persistentData.getCompound(Player.PERSISTED_NBT_TAG);val root=net.minecraft.nbt.CompoundTag();root.putString("dimension",state.dimension);root.putLong("origin",state.origin.asLong());root.putString("token",state.token);root.putBoolean("armed",state.armed);persisted.put(ROOT,root);player.persistentData.put(Player.PERSISTED_NBT_TAG,persisted)}
    private fun clear(player:ServerPlayer){val persisted=player.persistentData.getCompound(Player.PERSISTED_NBT_TAG);persisted.remove(ROOT);player.persistentData.put(Player.PERSISTED_NBT_TAG,persisted)}
    private fun token(player:ServerPlayer)="${player.uuid}:trace:${player.server.tickCount}"
    private fun emit(player:ServerPlayer,type:String,value:String,correlation:String){
        try{
            val api=Class.forName("com.bettercontent.threads.api.ThreadSignals")
            api.getMethod("emit",ServerPlayer::class.java,String::class.java,String::class.java,String::class.java).invoke(null,player,type,value,correlation)
        }catch(_:ClassNotFoundException){}catch(_:NoSuchMethodException){}catch(_:ReflectiveOperationException){}
    }
}
