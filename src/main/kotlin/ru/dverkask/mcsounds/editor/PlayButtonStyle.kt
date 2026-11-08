package ru.dverkask.mcsounds.editor

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.paint.RectanglePainter2D
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import ru.dverkask.mcsounds.settings.McSoundsSettings
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.Icon
import javax.swing.JComponent

object PlayButtonStyle {

    const val GLYPH = 14

    val defaultAccent: Color = JBColor.namedColor("Component.focusColor", JBColor(0x3574F0, 0x548AF7))

    fun accent(): Color = McSoundsSettings.getInstance().playButtonColor ?: defaultAccent

    const val INLINE_GLYPH = 12

    fun buttonSize(lineHeight: Int): Dimension {
        val h = (lineHeight - JBUI.scale(4)).coerceAtLeast(JBUI.scale(INLINE_GLYPH + 2))
        return Dimension(JBUI.scale(INLINE_GLYPH + 9), h)
    }

    fun paintButton(
        g: Graphics2D, x: Int, y: Int, width: Int, height: Int,
        accent: Color, chipBackground: Color, foreground: Color, hovered: Boolean,
    ) {
        val g2 = g.create() as Graphics2D
        try {
            setup(g2)
            val chip = if (hovered) ColorUtil.mix(chipBackground, foreground, 0.10) else chipBackground
            g2.color = chip
            RectanglePainter2D.FILL.paint(
                g2, x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble(), JBUI.scale(8).toDouble(),
            )
            val size = JBUI.scale(INLINE_GLYPH).toDouble()
            paintGlyph(g2, x + (width - size) / 2 + size / 12, y + (height - size) / 2, size, accent, chip, hovered)
        } finally {
            g2.dispose()
        }
    }

    fun paintGlyph(g2: Graphics2D, x: Double, y: Double, size: Double, accent: Color, background: Color, strong: Boolean) {
        val shape = runShape(x, y, size)
        val dark = ColorUtil.isDark(background)
        val tint = when {
            strong -> if (dark) 0.42 else 0.22
            else -> if (dark) 0.22 else 0.08
        }
        g2.color = ColorUtil.mix(background, accent, tint)
        g2.fill(shape)
        g2.color = accent
        g2.stroke = BasicStroke(JBUIScale.scale(1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(shape)
    }

    private fun runShape(x: Double, y: Double, size: Double): Shape {
        val k = size / GLYPH
        fun px(v: Double) = x + v * k
        fun py(v: Double) = y + v * k
        return Path2D.Double().apply {
            moveTo(px(12.0), py(6.134))
            curveTo(px(12.6667), py(6.5189), px(12.6667), py(7.4811), px(12.0), py(7.866))
            lineTo(px(3.0), py(13.0622))
            curveTo(px(2.3333), py(13.4471), px(1.5), py(12.966), px(1.5), py(12.1962))
            lineTo(px(1.5), py(1.8039))
            curveTo(px(1.5), py(1.0341), px(2.3333), py(0.5529), px(3.0), py(0.9378))
            closePath()
        }
    }

    fun chipBackground(scheme: EditorColorsScheme): Color =
        scheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_DEFAULT)?.backgroundColor
            ?: ColorUtil.mix(scheme.defaultBackground, scheme.defaultForeground, 0.12)

    fun setup(g2: Graphics2D) {
        GraphicsUtil.setupAAPainting(g2)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    fun repaintEditors() {
        for (editor in EditorFactory.getInstance().allEditors) {
            editor.contentComponent.repaint()
            (editor as? EditorEx)?.gutterComponentEx?.repaint()
        }
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }
}

@Suppress("UnstableApiUsage")
class PlayChipPresentation(
    private val editor: Editor,
    private val onClick: (MouseEvent) -> Unit,
) : BasePresentation() {

    private var hovered = false

    private val button: Dimension get() = PlayButtonStyle.buttonSize(editor.lineHeight)
    private val leftGap: Int get() = JBUI.scale(3)
    private val rightGap: Int get() = JBUI.scale(1)

    override val width: Int get() = leftGap + button.width + rightGap
    override val height: Int get() = editor.lineHeight

    override fun paint(g: Graphics2D, attributes: TextAttributes) {
        val size = button
        val scheme = editor.colorsScheme
        PlayButtonStyle.paintButton(
            g, leftGap, (height - size.height) / 2, size.width, size.height,
            PlayButtonStyle.accent(), PlayButtonStyle.chipBackground(scheme), scheme.defaultForeground, hovered,
        )
    }

    override fun mouseClicked(event: MouseEvent, translated: Point) {
        if (event.button == MouseEvent.BUTTON1) onClick(event)
    }

    override fun mouseMoved(event: MouseEvent, translated: Point) = setHovered(true)

    override fun mouseExited() = setHovered(false)

    private fun setHovered(value: Boolean) {
        if (hovered == value) return
        hovered = value
        fireContentChanged(Rectangle(0, 0, width, height))
    }

    override fun toString(): String = "PlaySound"
}

object PlayGutterIcon : Icon {
    override fun getIconWidth(): Int = JBUI.scale(PlayButtonStyle.GLYPH)
    override fun getIconHeight(): Int = JBUI.scale(PlayButtonStyle.GLYPH)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            PlayButtonStyle.setup(g2)
            val background = c?.background ?: EditorColorsManager.getInstance().globalScheme.defaultBackground
            PlayButtonStyle.paintGlyph(
                g2, x.toDouble(), y.toDouble(), iconWidth.toDouble(), PlayButtonStyle.accent(), background, strong = false,
            )
        } finally {
            g2.dispose()
        }
    }
}

class PlayChipPreview(private val accent: () -> Color) : JComponent() {
    init {
        isOpaque = false
    }

    private val button: Dimension get() = PlayButtonStyle.buttonSize(JBUI.scale(22))

    override fun getPreferredSize(): Dimension = Dimension(button.width * 2 + JBUI.scale(6), button.height)

    override fun paintComponent(g: Graphics) {
        val size = button
        val y = (height - size.height) / 2
        val scheme = EditorColorsManager.getInstance().globalScheme
        val chip = PlayButtonStyle.chipBackground(scheme)
        val fg = scheme.defaultForeground
        PlayButtonStyle.paintButton(g as Graphics2D, 0, y, size.width, size.height, accent(), chip, fg, hovered = false)
        PlayButtonStyle.paintButton(g, size.width + JBUI.scale(6), y, size.width, size.height, accent(), chip, fg, hovered = true)
    }
}
