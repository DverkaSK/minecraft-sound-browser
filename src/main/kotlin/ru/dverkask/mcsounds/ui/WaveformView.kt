package ru.dverkask.mcsounds.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ru.dverkask.mcsounds.audio.DecodedSound
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent
import kotlin.math.max
import kotlin.math.min

class WaveformView(
    private val preferredHeight: Int = 72,
    private val onSeek: (Long) -> Unit,
) : JComponent() {

    var sound: DecodedSound? = null
        set(value) { field = value; repaint() }

    var positionFrames: Long = 0
        set(value) { if (field != value) { field = value; repaint() } }

    var placeholder: String = ""
        set(value) { field = value; repaint() }

    var loading: Boolean = false
        set(value) { field = value; repaint() }

    private var hoverX = -1
    private var dragging = false

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (sound == null || !isEnabled) return
                dragging = true
                seekTo(e.x)
            }

            override fun mouseDragged(e: MouseEvent) {
                hoverX = e.x.coerceIn(0, width)
                if (dragging) seekTo(e.x)
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                dragging = false
            }

            override fun mouseMoved(e: MouseEvent) {
                hoverX = e.x
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hoverX = -1
                repaint()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    override fun getPreferredSize(): Dimension = JBUI.size(200, preferredHeight)
    override fun getMinimumSize(): Dimension = JBUI.size(60, minOf(48, preferredHeight))

    private fun seekTo(x: Int) {
        val s = sound ?: return
        val area = waveArea()
        val ratio = ((x - area.x).toDouble() / area.width).coerceIn(0.0, 1.0)
        val frame = (ratio * s.frameCount).toLong()
        positionFrames = frame
        onSeek(frame)
    }

    private fun waveArea(): Rectangle {
        val compact = preferredHeight < 48
        val padX = JBUI.scale(if (compact) 6 else 8)
        val padY = JBUI.scale(if (compact) 5 else 6)
        return Rectangle(padX, padY, max(1, width - padX * 2), max(1, height - padY * 2))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val arc = JBUI.scale(if (preferredHeight < 48) 8 else 10).toFloat()
            g2.color = BACKGROUND
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            g2.color = BORDER
            g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))

            val s = sound
            if (s == null) {
                paintPlaceholder(g2)
                return
            }
            paintBars(g2, s)
        } finally {
            g2.dispose()
        }
    }

    private fun paintPlaceholder(g2: Graphics2D) {
        val area = waveArea()
        g2.color = if (loading) ColorUtil.withAlpha(ACCENT, 0.5) else UNPLAYED
        val mid = area.y + area.height / 2
        val step = JBUI.scale(3)
        var x = area.x
        while (x < area.x + area.width) {
            g2.fillRect(x, mid, JBUI.scale(2), JBUI.scale(1).coerceAtLeast(1))
            x += step
        }
        val text = placeholder
        if (text.isNotEmpty()) {
            g2.font = JBFont.small()
            val fm = g2.fontMetrics
            val tw = fm.stringWidth(text)
            val padX = JBUI.scale(8)
            val bx = (width - tw) / 2 - padX
            val by = mid - fm.height / 2 - JBUI.scale(2)
            g2.color = BACKGROUND
            g2.fillRoundRect(bx, by, tw + padX * 2, fm.height + JBUI.scale(4), JBUI.scale(8), JBUI.scale(8))
            g2.color = UIUtil.getContextHelpForeground()
            g2.drawString(text, (width - tw) / 2, by + JBUI.scale(2) + fm.ascent)
        }
    }

    private fun paintBars(g2: Graphics2D, s: DecodedSound) {
        val area = waveArea()
        val barW = JBUI.scale(2)
        val gap = JBUI.scale(1)
        val bars = max(1, (area.width + gap) / (barW + gap))
        val progress = if (s.frameCount == 0L) 0.0 else positionFrames.toDouble() / s.frameCount
        val playedX = area.x + (progress * area.width).toInt()
        val mid = area.y + area.height / 2f
        val half = area.height / 2f
        val minH = JBUI.scale(1).toFloat()

        for (i in 0 until bars) {
            val from = (i.toLong() * s.peaks.size / bars).toInt()
            val to = max(from + 1, ((i + 1).toLong() * s.peaks.size / bars).toInt()).coerceAtMost(s.peaks.size)
            var peak = 0f
            var rms = 0f
            for (j in from until to) {
                peak = max(peak, s.peaks[j])
                rms = max(rms, s.rms[j])
            }
            val x = area.x + i * (barW + gap)
            val played = x + barW / 2 <= playedX
            val base = if (played) ACCENT else UNPLAYED
            val ph = max(minH, peak * half)
            val rh = max(minH, min(rms * 1.6f, peak) * half)
            g2.color = ColorUtil.withAlpha(base, if (played) 0.45 else 0.55)
            g2.fill(RoundRectangle2D.Float(x.toFloat(), mid - ph, barW.toFloat(), ph * 2, barW.toFloat(), barW.toFloat()))
            g2.color = base
            g2.fill(RoundRectangle2D.Float(x.toFloat(), mid - rh, barW.toFloat(), rh * 2, barW.toFloat(), barW.toFloat()))
        }

        if (positionFrames > 0) {
            g2.color = ACCENT
            g2.fillRoundRect(playedX - JBUI.scale(1), area.y - JBUI.scale(2), JBUI.scale(2), area.height + JBUI.scale(4), JBUI.scale(2), JBUI.scale(2))
        }

        if (hoverX >= area.x && hoverX <= area.x + area.width) {
            g2.color = ColorUtil.withAlpha(UIUtil.getLabelForeground(), 0.35)
            g2.fillRect(hoverX, area.y, JBUI.scale(1).coerceAtLeast(1), area.height)
            val ratio = (hoverX - area.x).toDouble() / area.width
            val text = TimeFormat.format(ratio * s.durationSeconds, s.durationSeconds)
            g2.font = JBFont.small()
            val fm = g2.fontMetrics
            val tw = fm.stringWidth(text)
            val padX = JBUI.scale(5)
            val bw = tw + padX * 2
            val bh = fm.height + JBUI.scale(2)
            val bx = (hoverX - bw / 2).coerceIn(JBUI.scale(2), width - bw - JBUI.scale(2))
            val by = if (preferredHeight < 48) (height - bh) / 2 else JBUI.scale(3)
            g2.color = TOOLTIP_BG
            g2.fillRoundRect(bx, by, bw, bh, JBUI.scale(6), JBUI.scale(6))
            g2.color = TOOLTIP_FG
            g2.drawString(text, bx + padX, by + JBUI.scale(1) + fm.ascent)
        }
    }

    override fun getAccessibleContext(): AccessibleContext {
        if (accessibleContext == null) {
            accessibleContext = object : AccessibleJComponent() {
                override fun getAccessibleRole(): AccessibleRole = AccessibleRole.SLIDER
            }
        }
        return accessibleContext
    }

    companion object {
        val ACCENT: Color = JBColor.namedColor("Component.focusColor", JBColor(0x3574F0, 0x548AF7))
        private val UNPLAYED: Color = JBColor(0xB4B8C2, 0x5A5D63)
        private val BORDER: Color = JBColor.namedColor("Borders.color", JBColor(0xEBECF0, 0x393B40))
        private val TOOLTIP_BG: Color = JBColor(0x27282E, 0xDFE1E5)
        private val TOOLTIP_FG: Color = JBColor(0xFFFFFF, 0x1E1F22)
        private val BACKGROUND: Color = JBColor.lazy {
            val panel = UIUtil.getPanelBackground()
            if (ColorUtil.isDark(panel)) ColorUtil.mix(panel, Color.BLACK, 0.22) else ColorUtil.mix(panel, Color.BLACK, 0.035)
        }
    }
}

object TimeFormat {
    fun format(seconds: Double, total: Double = seconds): String {
        val s = seconds.coerceAtLeast(0.0)
        val minutes = (s / 60).toInt()
        return if (total < 60) {
            "%d:%05.2f".format(java.util.Locale.ROOT, minutes, s - minutes * 60)
        } else {
            "%d:%02d".format(java.util.Locale.ROOT, minutes, (s - minutes * 60).toInt())
        }
    }
}
