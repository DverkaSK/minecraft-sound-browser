package ru.dverkask.mcsounds.ui

import com.intellij.codeInsight.hint.HintManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ru.dverkask.mcsounds.McSoundsBundle
import ru.dverkask.mcsounds.audio.PlayerState
import ru.dverkask.mcsounds.audio.SoundPlayer
import ru.dverkask.mcsounds.audio.SoundPlayerListener
import ru.dverkask.mcsounds.catalog.CatalogStatus
import ru.dverkask.mcsounds.catalog.SoundCatalogListener
import ru.dverkask.mcsounds.catalog.SoundCatalogService
import ru.dverkask.mcsounds.editor.SoundReferenceDetector
import ru.dverkask.mcsounds.model.SoundCatalog
import ru.dverkask.mcsounds.model.SoundEvent
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.Timer

class MiniPlayerPanel : JPanel(), Disposable {

    private val player = SoundPlayer.getInstance()
    var onClose: () -> Unit = {}
    var onResize: () -> Unit = {}

    private val waveform = WaveformView(preferredHeight = ROW_HEIGHT) { frame -> player.seek(frame) }
    private val playButton = PlayButton(diameter = ROW_HEIGHT) { player.togglePlayPause() }
    private val time = JBLabel("0:00.00").apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = SwingConstants.CENTER
    }
    private val variantLabel = JBLabel().apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = SwingConstants.CENTER
    }
    private val buttons = mutableListOf<ActionButton>()
    private val variantPanel: JPanel
    private val timer = Timer(33) { updatePosition() }

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false

        val stop = button(object : DumbAwareAction(McSoundsBundle.message("player.stop"), null, AllIcons.Actions.Suspend) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = player.state == PlayerState.PLAYING || player.state == PlayerState.PAUSED ||
                    (player.sound != null && player.positionFrames() > 0)
            }
            override fun actionPerformed(e: AnActionEvent) = player.stop()
        })
        val close = button(object : DumbAwareAction(McSoundsBundle.message("mini.close"), null, AllIcons.Actions.Close) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = onClose()
        })

        val labelWidth = variantLabel.getFontMetrics(variantLabel.font).stringWidth("00 / 00") + JBUI.scale(4)
        variantLabel.preferredSize = Dimension(labelWidth, JBUI.scale(ROW_HEIGHT))
        variantLabel.maximumSize = variantLabel.preferredSize
        variantPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentY = CENTER_ALIGNMENT
            add(button(variantAction("player.variant.prev", AllIcons.General.ChevronLeft) { player.previousVariant() }))
            add(variantLabel.apply { alignmentY = CENTER_ALIGNMENT })
            add(button(variantAction("player.variant.next", AllIcons.General.ChevronRight) { player.nextVariant() }))
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            isVisible = false
        }

        waveform.preferredSize = JBUI.size(WAVE_WIDTH, ROW_HEIGHT)
        waveform.maximumSize = waveform.preferredSize
        val timeWidth = time.getFontMetrics(time.font).stringWidth("0:00.00") + JBUI.scale(6)
        time.preferredSize = Dimension(timeWidth, JBUI.scale(ROW_HEIGHT))
        time.maximumSize = time.preferredSize

        for (c in listOf(playButton, waveform, time)) c.alignmentY = CENTER_ALIGNMENT
        add(playButton)
        add(Box.createHorizontalStrut(JBUI.scale(8)))
        add(waveform)
        add(Box.createHorizontalStrut(JBUI.scale(2)))
        add(time)
        add(Box.createHorizontalStrut(JBUI.scale(2)))
        add(variantPanel)
        add(stop)
        add(close)

        registerKeyboardAction({ player.togglePlayPause() }, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), WHEN_IN_FOCUSED_WINDOW)

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(SoundPlayerListener.TOPIC, SoundPlayerListener { refresh() })
        refresh()
    }

    private fun refresh() {
        val event = player.event
        val state = player.state
        waveform.sound = player.sound
        waveform.loading = state == PlayerState.LOADING
        waveform.placeholder = when (state) {
            PlayerState.LOADING -> McSoundsBundle.message("player.placeholder.loading")
            PlayerState.ERROR -> McSoundsBundle.message("player.placeholder.error", player.errorMessage ?: "")
            else -> ""
        }
        toolTipText = event?.displayKey
        waveform.toolTipText = event?.displayKey
        playButton.playing = state == PlayerState.PLAYING
        playButton.isEnabled = event != null && state != PlayerState.LOADING
        playButton.toolTipText = McSoundsBundle.message(if (state == PlayerState.PLAYING) "player.pause" else "player.play")
        if (state == PlayerState.PLAYING) timer.start() else timer.stop()
        updatePosition()

        val variants = event?.files?.size ?: 0
        variantLabel.text = if (variants > 1) "${player.variantIndex + 1} / $variants" else ""
        variantLabel.toolTipText = player.variant?.name
        if (variantPanel.isVisible != (variants > 1)) {
            variantPanel.isVisible = variants > 1
            revalidate()
            onResize()
        }
        buttons.forEach { it.update() }
    }

    private fun updatePosition() {
        val sound = player.sound
        val pos = player.positionFrames()
        waveform.positionFrames = pos
        time.text = when {
            sound == null -> "–:––"
            pos > 0 -> TimeFormat.format(sound.secondsAt(pos), sound.durationSeconds)
            else -> TimeFormat.format(sound.durationSeconds)
        }
    }

    override fun dispose() {
        timer.stop()
    }

    private fun button(action: AnAction): ActionButton =
        ActionButton(action, action.templatePresentation.clone(), "McSoundsMiniPlayer", ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE).also {
            it.alignmentY = CENTER_ALIGNMENT
            it.maximumSize = it.preferredSize
            buttons += it
        }

    private fun variantAction(textKey: String, icon: Icon, perform: () -> Unit) =
        object : DumbAwareAction(McSoundsBundle.message(textKey), null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = (player.event?.files?.size ?: 0) > 1 && player.state != PlayerState.LOADING
            }
            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    companion object {
        const val ROW_HEIGHT = 28
        const val WAVE_WIDTH = 190
    }
}

object MiniPlayerPopup {
    private var current: Balloon? = null
    private var pendingLoad: Disposable? = null

    fun showReference(editor: Editor, candidate: SoundReferenceDetector.Candidate, anchor: Point) {
        val project = editor.project ?: return
        val catalog = SoundCatalogService.getInstance().catalog
        ReadAction.nonBlocking<SoundEvent?> { SoundReferenceDetector.resolve(candidate, catalog, project) }
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { event -> onResolved(editor, candidate, anchor, catalog, event) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun onResolved(
        editor: Editor, candidate: SoundReferenceDetector.Candidate, anchor: Point, catalog: SoundCatalog?, event: SoundEvent?,
    ) {
        if (editor.isDisposed) return
        if (event != null) {
            show(editor, event, anchor)
            return
        }
        if (catalog != null || !SoundReferenceDetector.needsCatalog(candidate)) {
            HintManager.getInstance().showErrorHint(
                editor, McSoundsBundle.message("inlay.unknown", candidate.displayName, catalog?.version ?: "-"),
            )
            return
        }
        val service = SoundCatalogService.getInstance()
        pendingLoad?.let { Disposer.dispose(it) }
        val disposable = Disposer.newDisposable("Minecraft sound pending load")
        pendingLoad = disposable
        ApplicationManager.getApplication().messageBus.connect(disposable)
            .subscribe(SoundCatalogListener.TOPIC, SoundCatalogListener { status ->
                when (status) {
                    is CatalogStatus.Ready -> {
                        Disposer.dispose(disposable)
                        if (!editor.isDisposed) showReference(editor, candidate, anchor)
                    }
                    is CatalogStatus.Failed -> {
                        Disposer.dispose(disposable)
                        if (!editor.isDisposed) HintManager.getInstance().showErrorHint(editor, status.message)
                    }
                    else -> Unit
                }
            })
        HintManager.getInstance().showInformationHint(editor, McSoundsBundle.message("status.loading"))
        if (service.status !is CatalogStatus.Loading) service.reload(refreshManifest = false)
    }

    fun show(editor: Editor, event: SoundEvent, anchor: Point) {
        val player = SoundPlayer.getInstance()
        if (player.event?.namespacedKey == event.namespacedKey && player.sound != null) player.replay() else player.open(event, play = true)

        current?.hide()
        val panel = MiniPlayerPanel()
        val balloon = JBPopupFactory.getInstance()
            .createBalloonBuilder(panel)
            .setFillColor(UIUtil.getPanelBackground())
            .setBorderColor(JBUI.CurrentTheme.Popup.borderColor(true))
            .setBorderInsets(JBInsets.create(6, 8))
            .setCornerRadius(JBUI.scale(12))
            .setShadow(true)
            .setShowCallout(true)
            .setPointerSize(JBUI.size(14, 7))
            .setHideOnClickOutside(true)
            .setHideOnKeyOutside(false)
            .setHideOnAction(false)
            .setBlockClicksThroughBalloon(true)
            .setAnimationCycle(0)
            .setRequestFocus(false)
            .createBalloon()
        panel.onClose = { balloon.hide() }
        panel.onResize = { balloon.revalidate() }
        Disposer.register(balloon, panel)
        balloon.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (current === balloon) current = null
                player.stop()
            }
        })
        current = balloon
        balloon.show(RelativePoint(editor.contentComponent, anchor), Balloon.Position.below)
    }

    fun anchorFor(editor: Editor, point: Point): Point {
        val bounds = editor.inlayModel.getElementAt(point)?.bounds
        if (bounds != null) return Point(bounds.x + bounds.width / 2, bounds.y + bounds.height)
        val lineY = editor.visualLineToY(editor.yToVisualLine(point.y))
        return Point(point.x, lineY + editor.lineHeight)
    }
}
