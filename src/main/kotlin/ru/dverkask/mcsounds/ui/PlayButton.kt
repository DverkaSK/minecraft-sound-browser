package ru.dverkask.mcsounds.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.*
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent

class PlayButton(private val diameter: Int = 34, private val onClick: () -> Unit) : JComponent() {

    var playing: Boolean = false
        set(value) { field = value; repaint() }

    private var hover = false
    private var pressed = false

    init {
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; pressed = false; repaint() }
            override fun mousePressed(e: MouseEvent) { if (isEnabled) { pressed = true; repaint() } }
            override fun mouseReleased(e: MouseEvent) {
                val fire = pressed && contains(e.point) && isEnabled
                pressed = false
                repaint()
                if (fire) onClick()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (isEnabled && (e.keyCode == KeyEvent.VK_SPACE || e.keyCode == KeyEvent.VK_ENTER)) {
                    onClick(); e.consume()
                }
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        repaint()
    }

    override fun getPreferredSize(): Dimension = JBUI.size(diameter, diameter)
    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val cx = width / 2.0
            val cy = height / 2.0
            val d = minOf(width, height) - 1.0
            val r = d / 2.0

            if (isFocusOwner) {
                g2.color = ColorUtil.withAlpha(WaveformView.ACCENT, 0.3)
                g2.fill(Ellipse2D.Double(cx - r - 0.5, cy - r - 0.5, d + 1, d + 1))
            }

            g2.color = when {
                !isEnabled -> DISABLED
                pressed -> ColorUtil.mix(WaveformView.ACCENT, Color.BLACK, 0.15)
                hover -> ColorUtil.mix(WaveformView.ACCENT, Color.WHITE, 0.12)
                else -> WaveformView.ACCENT
            }
            val inner = r - JBUIScale.scale(1f)
            g2.fill(Ellipse2D.Double(cx - inner, cy - inner, inner * 2, inner * 2))

            g2.color = if (isEnabled) Color.WHITE else UIUtil.getLabelDisabledForeground()
            if (playing) paintPause(g2, cx, cy, d) else paintPlay(g2, cx, cy, d)
        } finally {
            g2.dispose()
        }
    }

    private fun paintPause(g2: Graphics2D, cx: Double, cy: Double, d: Double) {
        val h = d * 0.36
        val barW = h * 0.32
        val gap = h * 0.30
        g2.fill(RoundRectangle2D.Double(cx - gap / 2 - barW, cy - h / 2, barW, h, barW, barW))
        g2.fill(RoundRectangle2D.Double(cx + gap / 2, cy - h / 2, barW, h, barW, barW))
    }

    private fun paintPlay(g2: Graphics2D, cx: Double, cy: Double, d: Double) {
        val h = d * 0.40
        val w = h * 0.88
        val corner = h * 0.16
        val left = cx - w * 0.40
        val path = Path2D.Double()
        val ax = left; val ay = cy - h / 2
        val bx = left + w; val by = cy
        val qx = left; val qy = cy + h / 2
        fun lerp(x1: Double, y1: Double, x2: Double, y2: Double, t: Double) = doubleArrayOf(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)
        val lenAB = Math.hypot(bx - ax, by - ay)
        val lenBC = Math.hypot(qx - bx, qy - by)
        val lenCA = Math.hypot(ax - qx, ay - qy)
        val a1 = lerp(ax, ay, bx, by, corner / lenAB)
        val b0 = lerp(ax, ay, bx, by, 1 - corner / lenAB)
        val b1 = lerp(bx, by, qx, qy, corner / lenBC)
        val c0 = lerp(bx, by, qx, qy, 1 - corner / lenBC)
        val c1 = lerp(qx, qy, ax, ay, corner / lenCA)
        val a0 = lerp(qx, qy, ax, ay, 1 - corner / lenCA)
        path.moveTo(a1[0], a1[1])
        path.lineTo(b0[0], b0[1])
        path.quadTo(bx, by, b1[0], b1[1])
        path.lineTo(c0[0], c0[1])
        path.quadTo(qx, qy, c1[0], c1[1])
        path.lineTo(a0[0], a0[1])
        path.quadTo(ax, ay, a1[0], a1[1])
        path.closePath()
        g2.fill(path)
    }

    override fun getAccessibleContext(): AccessibleContext {
        if (accessibleContext == null) {
            accessibleContext = object : AccessibleJComponent() {
                override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PUSH_BUTTON
                override fun getAccessibleName(): String? = toolTipText
            }
        }
        return accessibleContext
    }

    companion object {
        private val DISABLED: Color = JBColor(0xDFE1E5, 0x43454A)
    }
}
