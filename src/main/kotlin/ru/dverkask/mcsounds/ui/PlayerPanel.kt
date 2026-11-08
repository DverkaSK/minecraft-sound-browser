package ru.dverkask.mcsounds.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBSlider
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ru.dverkask.mcsounds.McSoundsBundle
import ru.dverkask.mcsounds.McSoundsIcons
import ru.dverkask.mcsounds.actions.FavoritesListener
import ru.dverkask.mcsounds.actions.McSoundsDataKeys
import ru.dverkask.mcsounds.audio.PlayerState
import ru.dverkask.mcsounds.audio.SoundPlayer
import ru.dverkask.mcsounds.audio.SoundPlayerListener
import ru.dverkask.mcsounds.settings.McSoundsSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.*

class PlayerPanel(parent: Disposable) : JPanel(BorderLayout()), UiDataProvider, Disposable {

    private val player = SoundPlayer.getInstance()
    private val settings = McSoundsSettings.getInstance()

    private val titleLabel = JBLabel().apply {
        font = JBFont.label().biggerOn(1f).asBold()
    }
    private val subtitleLabel = JBLabel().apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val waveform = WaveformView(onSeek = { frame -> player.seek(frame) })
    private val currentTime = timeLabel()
    private val totalTime = timeLabel()
    private val fileLabel = JBLabel().apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = SwingConstants.CENTER
    }
    private val playButton = PlayButton { player.togglePlayPause() }
    private val variantLabel = JBLabel().apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }

    private val volumeSlider = slider(0, 100, settings.volume)
    private val volumeValue = valueLabel("100%")
    private val pitchSlider = slider(McSoundsSettings.MIN_PITCH, McSoundsSettings.MAX_PITCH, settings.pitchPercent)
    private val pitchValue = valueLabel("2.00×")

    private val headerToolbar: ActionToolbar
    private val transportToolbar: ActionToolbar
    private val variantPrevToolbar: ActionToolbar
    private val variantNextToolbar: ActionToolbar
    private val variantPanel: JPanel

    private val timer = Timer(33) { updatePosition() }

    init {
        Disposer.register(parent, this)
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor()),
            JBUI.Borders.empty(10, 12, 8, 12),
        )

        val actionManager = ActionManager.getInstance()
        val headerGroup = DefaultActionGroup().apply {
            add(actionManager.getAction("McSounds.ToggleFavorite"))
            add(actionManager.getAction("McSounds.CopyGroup"))
        }
        headerToolbar = toolbar("McSoundsPlayerHeader", headerGroup)
        val titles = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.NORTH)
            add(subtitleLabel, BorderLayout.CENTER)
        }
        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(titles, BorderLayout.CENTER)
            add(headerToolbar.component, BorderLayout.EAST)
        }

        val times = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(3)
            add(currentTime, BorderLayout.WEST)
            add(fileLabel, BorderLayout.CENTER)
            add(totalTime, BorderLayout.EAST)
        }
        val wavePanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(waveform, BorderLayout.CENTER)
            add(times, BorderLayout.SOUTH)
        }

        transportToolbar = toolbar("McSoundsPlayerTransport", DefaultActionGroup(StopAction(), ReplayAction(), LoopAction()))
        variantPrevToolbar = toolbar("McSoundsPlayerVariants", DefaultActionGroup(PrevVariantAction()))
        variantNextToolbar = toolbar("McSoundsPlayerVariants", DefaultActionGroup(NextVariantAction(), RandomVariantAction()))
        variantPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(variantPrevToolbar.component)
            add(variantLabel)
            add(variantNextToolbar.component)
        }
        val transportLeft = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(playButton)
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(transportToolbar.component)
        }
        val transport = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
            add(transportLeft, BorderLayout.WEST)
            add(variantPanel, BorderLayout.EAST)
        }

        val sliders = JPanel(java.awt.GridLayout(1, 2, JBUI.scale(12), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
            add(sliderRow(JBLabel(McSoundsIcons.Sound), volumeSlider, volumeValue))
            add(sliderRow(JBLabel(McSoundsBundle.message("player.pitch")).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = JBFont.small()
            }, pitchSlider, pitchValue))
        }

        val south = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(transport, BorderLayout.NORTH)
            add(sliders, BorderLayout.SOUTH)
        }

        add(header, BorderLayout.NORTH)
        add(wavePanel, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)

        volumeSlider.toolTipText = McSoundsBundle.message("player.volume")
        volumeSlider.addChangeListener {
            settings.volume = volumeSlider.value
            updateSliderLabels()
            player.volumeChanged()
        }
        pitchSlider.toolTipText = McSoundsBundle.message("player.pitch.tooltip")
        pitchSlider.addChangeListener {
            updateSliderLabels()
            if (!pitchSlider.valueIsAdjusting || player.isPlaying) {
                settings.pitchPercent = pitchSlider.value
                player.pitchChanged()
            }
        }
        val resetPitch = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) pitchSlider.value = 100
            }
        }
        pitchValue.addMouseListener(resetPitch)
        pitchValue.toolTipText = McSoundsBundle.message("player.pitch.reset")
        playButton.toolTipText = McSoundsBundle.message("player.play")

        val connection = com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(SoundPlayerListener.TOPIC, SoundPlayerListener { refresh() })
        connection.subscribe(FavoritesListener.TOPIC, FavoritesListener { headerToolbar.updateActionsAsync() })

        updateSliderLabels()
        refresh()
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[McSoundsDataKeys.SOUND_EVENT] = player.event
    }

    fun refresh() {
        val event = player.event
        val state = player.state
        val sound = player.sound

        if (event == null) {
            titleLabel.text = McSoundsBundle.message("player.empty.title")
            subtitleLabel.text = McSoundsBundle.message("player.empty.subtitle")
        } else {
            titleLabel.text = event.displayKey
            val parts = mutableListOf(if (event.isVanilla) event.category.displayName else McSoundsBundle.message("player.project.sound"))
            parts += if (event.files.size == 1) McSoundsBundle.message("player.variants.one")
            else McSoundsBundle.message("player.variants.many", event.files.size)
            player.variant?.let { v ->
                if (v.stream) parts += McSoundsBundle.message("player.streamed")
            }
            subtitleLabel.text = parts.joinToString("  ·  ")
        }
        titleLabel.toolTipText = event?.displayKey
        titleLabel.icon = event?.let { McSoundsIcons.category(it.category) }
        titleLabel.iconTextGap = JBUI.scale(6)
        if (volumeSlider.value != settings.volume) volumeSlider.value = settings.volume
        if (!pitchSlider.valueIsAdjusting && pitchSlider.value != settings.pitchPercent) pitchSlider.value = settings.pitchPercent

        waveform.sound = sound
        waveform.loading = state == PlayerState.LOADING
        waveform.placeholder = when (state) {
            PlayerState.EMPTY -> McSoundsBundle.message("player.placeholder.empty")
            PlayerState.LOADING -> McSoundsBundle.message("player.placeholder.loading")
            PlayerState.ERROR -> McSoundsBundle.message("player.placeholder.error", player.errorMessage ?: "")
            else -> ""
        }
        waveform.isEnabled = sound != null

        val variant = player.variant
        fileLabel.text = variant?.let { v ->
            buildString {
                append(v.name)
                if (settings.applyFileAttributes && (v.pitch != 1f || v.volume != 1f)) {
                    append("  ·  ")
                    if (v.volume != 1f) append(McSoundsBundle.message("player.file.volume", fmt(v.volume)))
                    if (v.volume != 1f && v.pitch != 1f) append(", ")
                    if (v.pitch != 1f) append(McSoundsBundle.message("player.file.pitch", fmt(v.pitch)))
                }
            }
        } ?: ""
        totalTime.text = sound?.let { TimeFormat.format(it.durationSeconds) } ?: "–:––"

        playButton.playing = state == PlayerState.PLAYING
        playButton.isEnabled = event != null && state != PlayerState.LOADING
        playButton.toolTipText = McSoundsBundle.message(if (state == PlayerState.PLAYING) "player.pause" else "player.play")

        val variants = event?.files?.size ?: 0
        variantPanel.isVisible = variants > 1
        variantLabel.text = if (variants > 1) " ${player.variantIndex + 1} / $variants " else ""

        if (state == PlayerState.PLAYING) timer.start() else timer.stop()
        updatePosition()
        headerToolbar.updateActionsAsync()
        transportToolbar.updateActionsAsync()
        variantPrevToolbar.updateActionsAsync()
        variantNextToolbar.updateActionsAsync()
    }

    private fun updatePosition() {
        val sound = player.sound
        val pos = player.positionFrames()
        waveform.positionFrames = pos
        currentTime.text = sound?.let { TimeFormat.format(it.secondsAt(pos), it.durationSeconds) } ?: "–:––"
    }

    private fun updateSliderLabels() {
        volumeValue.text = "${volumeSlider.value}%"
        pitchValue.text = String.format(Locale.ROOT, "%.2f×", pitchSlider.value / 100.0)
    }

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    override fun dispose() {
        timer.stop()
    }

    private fun toolbar(place: String, group: ActionGroup): ActionToolbar =
        ActionManager.getInstance().createActionToolbar(place, group, true).apply {
            targetComponent = this@PlayerPanel
            setReservePlaceAutoPopupIcon(false)
            component.isOpaque = false
            component.border = JBUI.Borders.empty()
        }

    private fun timeLabel() = JBLabel("–:––").apply {
        font = JBFont.small().deriveFont(java.awt.Font.PLAIN)
        foreground = UIUtil.getContextHelpForeground()
    }

    private fun valueLabel(widest: String) = JBLabel(widest).apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = SwingConstants.RIGHT
        preferredSize = Dimension(preferredSize.width + JBUI.scale(2), preferredSize.height)
    }

    private fun slider(min: Int, max: Int, value: Int) = JBSlider(min, max, value).apply {
        isOpaque = false
        isFocusable = true
        preferredSize = Dimension(JBUI.scale(60), preferredSize.height)
    }

    private fun sliderRow(label: JComponent, slider: JSlider, value: JComponent) =
        JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(label, BorderLayout.WEST)
            add(slider, BorderLayout.CENTER)
            add(value, BorderLayout.EAST)
        }

    private abstract inner class PlayerAction(text: String, icon: Icon) : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class StopAction : PlayerAction(McSoundsBundle.message("player.stop"), AllIcons.Actions.Suspend) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = player.state == PlayerState.PLAYING || player.state == PlayerState.PAUSED ||
                (player.sound != null && player.positionFrames() > 0)
        }
        override fun actionPerformed(e: AnActionEvent) = player.stop()
    }

    private inner class ReplayAction : PlayerAction(McSoundsBundle.message("player.replay"), McSoundsIcons.Replay) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = player.event != null && player.state != PlayerState.LOADING
        }
        override fun actionPerformed(e: AnActionEvent) = player.replay()
    }

    private inner class LoopAction : DumbAwareToggleAction(
        McSoundsBundle.message("player.loop"), null, McSoundsIcons.Loop,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = settings.loop
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            settings.loop = state
        }
    }

    private inner class PrevVariantAction : PlayerAction(McSoundsBundle.message("player.variant.prev"), AllIcons.General.ChevronLeft) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = (player.event?.files?.size ?: 0) > 1
        }
        override fun actionPerformed(e: AnActionEvent) = player.previousVariant()
    }

    private inner class NextVariantAction : PlayerAction(McSoundsBundle.message("player.variant.next"), AllIcons.General.ChevronRight) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = (player.event?.files?.size ?: 0) > 1
        }
        override fun actionPerformed(e: AnActionEvent) = player.nextVariant()
    }

    private inner class RandomVariantAction : PlayerAction(McSoundsBundle.message("player.variant.random"), McSoundsIcons.Shuffle) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = (player.event?.files?.size ?: 0) > 1
        }
        override fun actionPerformed(e: AnActionEvent) = player.randomVariant()
    }
}
