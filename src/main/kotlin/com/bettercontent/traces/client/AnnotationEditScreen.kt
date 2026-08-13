package com.bettercontent.traces.client

import com.bettercontent.traces.client.death.DeathEchoRecorder
import com.bettercontent.traces.domain.AnnotationComponents
import com.bettercontent.traces.domain.EchoMutation
import com.bettercontent.traces.dto.VisibleAnnotationDto
import com.bettercontent.traces.network.AnnotationCreatePacket
import com.bettercontent.traces.network.AnnotationMutationResultPacket
import com.bettercontent.traces.network.AnnotationUpdatePacket
import com.bettercontent.traces.network.TracesNetwork
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import java.util.UUID

data class AnnotationDraft(
    val target: BlockPos,
    val annotation: VisibleAnnotationDto?,
    var text: String = annotation?.text.orEmpty(),
    var icon: String = annotation?.icon.orEmpty(),
    var color: Int = annotation?.color ?: 0,
    var gesture: GestureChoice = if (annotation?.hasEcho == true) GestureChoice.Keep else GestureChoice.None,
    val recentClip: ByteArray? = null,
    var error: String = "",
)

sealed interface GestureChoice {
    data object None : GestureChoice
    data object Keep : GestureChoice
    data object Recent : GestureChoice
    data object Remove : GestureChoice
    data class Quark(val id: String) : GestureChoice
}

object AnnotationDrafts {
    private val pending = mutableMapOf<UUID, AnnotationDraft>()

    fun submit(requestId: UUID, draft: AnnotationDraft) { pending[requestId] = draft }

    fun failLocal(requestId: UUID, error: Throwable) {
        val draft = pending.remove(requestId) ?: return
        draft.error = error.message ?: "gesture capture failed"
        Minecraft.getInstance().setScreen(AnnotationEditScreen(draft))
    }

    fun accept(result: AnnotationMutationResultPacket) {
        val draft = pending.remove(result.requestId) ?: return
        if (result.success) {
            Minecraft.getInstance().player?.displayClientMessage(Component.literal("Annotation saved"), true)
            TracesNetwork.requestNearby(2)
        } else {
            draft.error = result.error.ifBlank { "Annotation request rejected" }
            Minecraft.getInstance().setScreen(AnnotationEditScreen(draft))
        }
    }
}

class AnnotationEditScreen(private val draft: AnnotationDraft) :
    Screen(Component.literal(if (draft.annotation == null) "New Trace Note" else "Edit Trace Note")) {
    constructor(target: BlockPos, annotation: VisibleAnnotationDto?, recentClip: ByteArray? = null, error: String = "") :
        this(AnnotationDraft(target, annotation, recentClip = recentClip, error = error))

    private lateinit var textBox: EditBox
    private lateinit var saveButton: Button
    private lateinit var iconButton: Button
    private lateinit var colorButton: Button
    private lateinit var gestureButton: Button
    private val icons = listOf("") + AnnotationComponents.icons
    private val colors = AnnotationComponents.colors.entries.toList()
    private val quarkEmotes by lazy { QuarkEmoteBridge.availableEmotes() }

    override fun init() {
        val left = width / 2 - 150
        val top = height / 2 - 70
        textBox = EditBox(font, left, top, 300, 20, Component.literal("Optional note text"))
        textBox.setMaxLength(AnnotationComponents.MAX_TEXT_LENGTH)
        textBox.value = draft.text
        textBox.setResponder { draft.text = it; refreshSave() }
        addRenderableWidget(textBox)

        iconButton = addRenderableWidget(Button.builder(iconLabel()) { cycleIcon() }.bounds(left, top + 28, 96, 20).build())
        colorButton = addRenderableWidget(Button.builder(colorLabel()) { cycleColor() }.bounds(left + 102, top + 28, 96, 20).build())
        gestureButton = addRenderableWidget(Button.builder(gestureLabel()) { cycleGesture() }.bounds(left + 204, top + 28, 96, 20).build())

        saveButton = addRenderableWidget(Button.builder(Component.literal("Save")) { save() }.bounds(left, top + 58, 92, 20).build())
        if (draft.annotation != null) addRenderableWidget(Button.builder(Component.literal("Delete")) {
            TracesNetwork.deleteAnnotation(draft.annotation.id, draft.annotation.revision); onClose()
        }.bounds(left + 104, top + 58, 92, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Cancel")) { onClose() }.bounds(left + 208, top + 58, 92, 20).build())
        setInitialFocus(textBox)
        refreshSave()
    }

    private fun cycleIcon() {
        draft.icon = icons[(icons.indexOf(draft.icon).coerceAtLeast(0) + 1) % icons.size]
        if (draft.icon.isEmpty()) draft.color = 0 else if (draft.color !in AnnotationComponents.colors.values) draft.color = colors.first().value
        iconButton.message = iconLabel(); colorButton.message = colorLabel(); refreshSave()
    }

    private fun cycleColor() {
        if (draft.icon.isEmpty()) return
        val current = colors.indexOfFirst { it.value == draft.color }
        draft.color = colors[(current + 1).coerceAtLeast(0) % colors.size].value
        colorButton.message = colorLabel()
    }

    private fun gestureOptions(): List<GestureChoice> = if (draft.annotation == null) {
        listOf(GestureChoice.None, GestureChoice.Recent) + quarkEmotes.map(GestureChoice::Quark)
    } else {
        buildList {
            if (draft.annotation.hasEcho) add(GestureChoice.Keep)
            add(GestureChoice.Recent)
            addAll(quarkEmotes.map(GestureChoice::Quark))
            if (draft.annotation.hasEcho) add(GestureChoice.Remove) else add(GestureChoice.None)
        }
    }

    private fun cycleGesture() {
        val choices = gestureOptions()
        draft.gesture = choices[(choices.indexOf(draft.gesture).coerceAtLeast(0) + 1) % choices.size]
        gestureButton.message = gestureLabel(); refreshSave()
    }

    private fun refreshSave() {
        if (!::saveButton.isInitialized) return
        val hasGesture = when (draft.gesture) {
            GestureChoice.Keep, GestureChoice.Recent, is GestureChoice.Quark -> true
            GestureChoice.Remove, GestureChoice.None -> false
        }
        saveButton.active = draft.text.isNotBlank() || draft.icon.isNotEmpty() || hasGesture
    }

    private fun save() {
        draft.text = textBox.value.trim()
        val requestId = UUID.randomUUID()
        AnnotationDrafts.submit(requestId, draft)
        when (val choice = draft.gesture) {
            is GestureChoice.Quark -> runCatching {
                QuarkEmoteBridge.request(choice.id)
                DeathEchoRecorder.captureSelectedEmote(
                    { clip -> sendMutation(requestId, EchoMutation.REPLACE, clip) },
                    { error -> AnnotationDrafts.failLocal(requestId, error) },
                )
            }.onFailure { AnnotationDrafts.failLocal(requestId, it) }
            GestureChoice.Recent -> {
                val clip = draft.recentClip
                if (clip == null) AnnotationDrafts.failLocal(requestId, IllegalStateException("the recent three-second pose recording is unavailable"))
                else sendMutation(requestId, EchoMutation.REPLACE, clip)
            }
            GestureChoice.Keep -> sendMutation(requestId, EchoMutation.KEEP, null)
            GestureChoice.Remove -> sendMutation(requestId, EchoMutation.REMOVE, null)
            GestureChoice.None -> sendMutation(requestId, EchoMutation.KEEP, null)
        }
        onClose()
    }

    private fun sendMutation(requestId: UUID, echoMutation: EchoMutation, clip: ByteArray?) {
        val annotation = draft.annotation
        if (annotation == null) TracesNetwork.createAnnotation(
            AnnotationCreatePacket(requestId, draft.target.asLong(), draft.text, draft.icon, draft.color, echoMutation, clip),
        ) else TracesNetwork.updateAnnotation(
            AnnotationUpdatePacket(requestId, annotation.id, annotation.revision, draft.text, draft.icon, draft.color, echoMutation, clip),
        )
    }

    private fun iconLabel() = Component.literal("Icon: ${draft.icon.ifEmpty { "None" }}")
    private fun colorLabel(): Component {
        val name = colors.firstOrNull { it.value == draft.color }?.key ?: "None"
        return Component.literal("Color: $name")
    }
    private fun gestureLabel() = Component.literal("Gesture: " + when (val choice = draft.gesture) {
        GestureChoice.None -> "None"; GestureChoice.Keep -> "Keep"; GestureChoice.Recent -> "Recent 3s"
        GestureChoice.Remove -> "Remove"; is GestureChoice.Quark -> choice.id
    })

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics)
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 94, 0xFFFFFF)
        if (draft.error.isNotBlank()) graphics.drawCenteredString(font, draft.error.take(72), width / 2, height / 2 + 18, 0xFF6666)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun isPauseScreen(): Boolean = false
}
