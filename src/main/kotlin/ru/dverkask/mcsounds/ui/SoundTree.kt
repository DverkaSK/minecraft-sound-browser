package ru.dverkask.mcsounds.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import ru.dverkask.mcsounds.McSoundsBundle
import ru.dverkask.mcsounds.McSoundsIcons
import ru.dverkask.mcsounds.model.SoundCatalog
import ru.dverkask.mcsounds.model.SoundCategory
import ru.dverkask.mcsounds.model.SoundEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class SoundQuery(raw: String) {
    val tokens: List<String> = raw.trim()
        .replace(PREFIX, "")
        .lowercase()
        .split(SEPARATORS)
        .filter { it.isNotEmpty() }
        .distinct()

    val isEmpty: Boolean get() = tokens.isEmpty()

    fun matches(event: SoundEvent): Boolean = tokens.all { event.key.contains(it) }

    fun highlights(text: String): List<IntRange> {
        if (isEmpty) return emptyList()
        val lower = text.lowercase()
        val ranges = mutableListOf<IntRange>()
        for (token in tokens) {
            var from = 0
            while (true) {
                val i = lower.indexOf(token, from)
                if (i < 0) break
                ranges += i until i + token.length
                from = i + 1
            }
        }
        if (ranges.isEmpty()) return ranges
        ranges.sortBy { it.first }
        val merged = mutableListOf(ranges.first())
        for (r in ranges.drop(1)) {
            val last = merged.last()
            val bridged = r.first == last.last + 2 && text[last.last + 1] in "._"
            if (r.first <= last.last + 1 || bridged) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
            else merged += r
        }
        return merged
    }

    companion object {
        private val PREFIX = Regex("^(minecraft:|[A-Za-z]*Sounds?(Events?)?\\.)")
        private val SEPARATORS = Regex("[\\s._:/]+")
    }
}

sealed class SoundTreeNode : DefaultMutableTreeNode() {
    var soundCount: Int = 0
}

class FavoritesNode : SoundTreeNode()
class CategoryNode(val category: SoundCategory) : SoundTreeNode()
class GroupNode(val name: String) : SoundTreeNode()
class SoundLeafNode(val event: SoundEvent, val label: String) : SoundTreeNode() {
    init { allowsChildren = false }
}

object SoundTreeBuilder {

    class Result(val root: DefaultMutableTreeNode, val matchCount: Int, val firstLeaf: SoundLeafNode?)

    fun build(catalog: SoundCatalog, query: SoundQuery, favorites: Set<String>): Result {
        val root = DefaultMutableTreeNode()
        val matched = catalog.events.filter { query.matches(it) }
        var firstLeaf: SoundLeafNode? = null

        val favoriteEvents = matched.filter { it.key in favorites }
        if (favoriteEvents.isNotEmpty()) {
            val fav = FavoritesNode()
            favoriteEvents.forEach { fav.add(SoundLeafNode(it, it.key)) }
            fav.soundCount = favoriteEvents.size
            root.add(fav)
        }

        val byCategory = matched.groupBy { it.category }
        for (category in SoundCategory.entries) {
            val events = byCategory[category] ?: continue
            val categoryNode = CategoryNode(category)
            categoryNode.soundCount = events.size

            val depth = if (category == SoundCategory.OTHER) 0 else 1
            val byGroup = events.groupBy { it.segments.getOrNull(depth)?.takeIf { _ -> it.segments.size > depth + 1 } }
            val groups = byGroup.keys.filterNotNull().sorted()
            val loose = mutableListOf<SoundLeafNode>()

            for (group in groups) {
                val groupEvents = byGroup.getValue(group)
                if (groupEvents.size == 1) {
                    val e = groupEvents.single()
                    loose += SoundLeafNode(e, e.segments.drop(depth).joinToString("."))
                    continue
                }
                val groupNode = GroupNode(group)
                groupNode.soundCount = groupEvents.size
                groupEvents.forEach { groupNode.add(SoundLeafNode(it, it.segments.drop(depth + 1).joinToString("."))) }
                categoryNode.add(groupNode)
            }
            byGroup[null]?.forEach { loose += SoundLeafNode(it, it.segments.drop(depth).joinToString(".")) }

            val children = (0 until categoryNode.childCount).map { categoryNode.getChildAt(it) as SoundTreeNode } + loose
            categoryNode.removeAllChildren()
            children.sortedBy { sortKey(it) }.forEach { categoryNode.add(it) }
            root.add(categoryNode)
        }

        firstLeaf = findFirstLeaf(root, skipFavorites = true)
        return Result(root, matched.size, firstLeaf)
    }

    private fun sortKey(node: SoundTreeNode): String = when (node) {
        is GroupNode -> node.name
        is SoundLeafNode -> node.label
        else -> ""
    }

    private fun findFirstLeaf(node: DefaultMutableTreeNode, skipFavorites: Boolean): SoundLeafNode? {
        if (node is SoundLeafNode) return node
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            if (skipFavorites && child is FavoritesNode) continue
            findFirstLeaf(child, false)?.let { return it }
        }
        return null
    }
}

class SoundTreeRenderer(private val queryProvider: () -> SoundQuery) : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        val query = queryProvider()
        when (value) {
            is FavoritesNode -> {
                icon = AllIcons.Nodes.Favorite
                append(McSoundsBundle.message("tree.favorites"), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                appendCount(value.soundCount)
            }
            is CategoryNode -> {
                icon = McSoundsIcons.category(value.category)
                append(value.category.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                appendCount(value.soundCount)
            }
            is GroupNode -> {
                icon = McSoundsIcons.Group
                appendHighlighted(value.name, query, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                appendCount(value.soundCount)
            }
            is SoundLeafNode -> {
                icon = McSoundsIcons.Sound
                appendHighlighted(value.label, query, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val variants = value.event.files.size
                if (variants > 1) append("  ×$variants", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                toolTipText = value.event.key
            }
            else -> Unit
        }
    }

    private fun appendCount(count: Int) {
        append("  $count", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    private fun appendHighlighted(text: String, query: SoundQuery, base: SimpleTextAttributes) {
        val ranges = query.highlights(text)
        if (ranges.isEmpty()) {
            append(text, base)
            return
        }
        val match = SimpleTextAttributes(base.style or SimpleTextAttributes.STYLE_SEARCH_MATCH, base.fgColor)
        var pos = 0
        for (r in ranges) {
            if (r.first > pos) append(text.substring(pos, r.first), base)
            append(text.substring(r.first, r.last + 1), match)
            pos = r.last + 1
        }
        if (pos < text.length) append(text.substring(pos), base)
    }
}
