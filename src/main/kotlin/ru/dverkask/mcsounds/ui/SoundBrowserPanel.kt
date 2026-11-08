package ru.dverkask.mcsounds.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.*
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import ru.dverkask.mcsounds.McSoundsBundle
import ru.dverkask.mcsounds.actions.FavoritesListener
import ru.dverkask.mcsounds.actions.McSoundsDataKeys
import ru.dverkask.mcsounds.audio.SoundPlayer
import ru.dverkask.mcsounds.audio.SoundPlayerListener
import ru.dverkask.mcsounds.catalog.CatalogStatus
import ru.dverkask.mcsounds.catalog.SoundCatalogListener
import ru.dverkask.mcsounds.catalog.SoundCatalogService
import ru.dverkask.mcsounds.model.SoundEvent
import ru.dverkask.mcsounds.settings.McSoundsSettings
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class SoundBrowserPanel(parent: Disposable) : SimpleToolWindowPanel(true, true), UiDataProvider, Disposable {

    private val catalogService = SoundCatalogService.getInstance()
    private val player = SoundPlayer.getInstance()
    private val settings = McSoundsSettings.getInstance()

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel)
    private val searchField = SearchTextField(true, "McSounds.Search")
    private val playerPanel: PlayerPanel
    private val treeExpander = DefaultTreeExpander(tree)
    private val searchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val selectAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private var query = SoundQuery("")
    private var suppressSelection = false
    private var builtFor: Any? = null
    private var builtOnce = false
    private var treeFiltered = false
    private var normalExpansion: Set<String> = emptySet()

    init {
        Disposer.register(parent, this)
        playerPanel = PlayerPanel(this)

        val toolbarGroup = ActionManager.getInstance().getAction("McSounds.Toolbar") as ActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("McSoundsToolbar", toolbarGroup, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        searchField.textEditor.emptyText.text = McSoundsBundle.message("search.placeholder")
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                searchAlarm.cancelAllRequests()
                searchAlarm.addRequest({ applyQuery(searchField.text) }, 120)
            }
        })
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        focusTree(); e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        searchAlarm.cancelAllRequests()
                        applyQuery(searchField.text)
                        if (selectedEvent() == null) selectFirstLeaf()
                        selectedEvent()?.let { player.open(it, play = true) }
                        e.consume()
                    }
                }
            }
        })
        val searchPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8, 6, 8)
            add(searchField, BorderLayout.CENTER)
        }

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = SoundTreeRenderer { query }
        tree.emptyText.text = McSoundsBundle.message("status.loading")
        TreeUtil.installActions(tree)
        PopupHandler.installPopupMenu(tree, "McSounds.TreePopup", "McSoundsTreePopup")
        tree.addTreeSelectionListener {
            if (suppressSelection) return@addTreeSelectionListener
            val event = selectedEvent() ?: return@addTreeSelectionListener
            if (event == player.event) return@addTreeSelectionListener
            selectAlarm.cancelAllRequests()
            selectAlarm.addRequest({
                if (selectedEvent() == event) player.open(event, play = settings.autoplay)
            }, 90)
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(e: MouseEvent): Boolean {
                val event = selectedEvent() ?: return false
                playOrReplay(event)
                return true
            }
        }.installOn(tree)
        tree.registerKeyboardAction({ selectedEvent()?.let { playOrReplay(it) } },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), WHEN_FOCUSED)
        tree.registerKeyboardAction({ player.togglePlayPause() },
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), WHEN_FOCUSED)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                val c = e.keyChar
                if (c.isLetterOrDigit() || c == '_' || c == '.') {
                    searchField.requestFocusInWindow()
                    searchField.text += c
                    e.consume()
                }
            }
        })

        val treeScroll = ScrollPaneFactory.createScrollPane(tree, true)
        val top = JPanel(BorderLayout()).apply {
            add(searchPanel, BorderLayout.NORTH)
            add(treeScroll, BorderLayout.CENTER)
        }

        val splitter = OnePixelSplitter(true, "McSounds.Splitter.v2", 0.68f).apply {
            firstComponent = top
            secondComponent = playerPanel
            setHonorComponentsMinimumSize(true)
        }
        setContent(splitter)

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(SoundCatalogListener.TOPIC, SoundCatalogListener { onCatalogStatus(it) })
        connection.subscribe(FavoritesListener.TOPIC, FavoritesListener { rebuild(keepSelection = true) })
        connection.subscribe(SoundPlayerListener.TOPIC, SoundPlayerListener { revealPlayerEvent() })

        onCatalogStatus(catalogService.status)
        catalogService.ensureLoaded()
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[McSoundsDataKeys.SOUND_EVENT] = selectedEvent()
        sink[PlatformDataKeys.TREE_EXPANDER] = treeExpander
    }

    override fun dispose() = Unit

    private fun onCatalogStatus(status: CatalogStatus) {
        val empty = tree.emptyText
        when (status) {
            CatalogStatus.Idle, is CatalogStatus.Loading -> {
                clearTree()
                empty.clear()
                empty.appendLine(McSoundsBundle.message("status.loading"), SimpleTextAttributes.REGULAR_ATTRIBUTES, null)
                val version = (status as? CatalogStatus.Loading)?.version
                empty.appendLine(
                    version?.let { McSoundsBundle.message("status.loading.version", it) }
                        ?: McSoundsBundle.message("status.loading.latest"),
                    SimpleTextAttributes.GRAYED_ATTRIBUTES, null,
                )
            }
            is CatalogStatus.Failed -> {
                clearTree()
                empty.clear()
                empty.appendLine(AllIcons.General.Warning, McSoundsBundle.message("status.failed"), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, null)
                empty.appendLine(status.message, SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
                empty.appendLine(McSoundsBundle.message("status.retry"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                    catalogService.reload(refreshManifest = true)
                }
            }
            is CatalogStatus.Ready -> {
                if (builtFor === status.catalog) return
                builtFor = status.catalog
                rebuild(keepSelection = true)
            }
        }
    }

    private fun applyQuery(text: String) {
        val newQuery = SoundQuery(text)
        if (newQuery.tokens == query.tokens) return
        query = newQuery
        rebuild(keepSelection = false)
    }

    private fun rebuild(keepSelection: Boolean) {
        val catalog = catalogService.catalog ?: return
        val previous = if (keepSelection) selectedEvent() else null
        if (!treeFiltered && builtOnce) normalExpansion = expandedKeys()

        val result = SoundTreeBuilder.build(catalog, query, settings.favorites())
        suppressSelection = true
        try {
            setRoot(result.root)
            builtOnce = true
            treeFiltered = !query.isEmpty
            if (query.isEmpty) {
                restoreExpansion(normalExpansion)
            } else if (result.matchCount <= 400) {
                TreeUtil.expandAll(tree)
            } else {
                expandCategories()
            }
            val target = previous?.let { findLeaf(it) }
            if (target != null) {
                TreeUtil.selectPath(tree, TreePath(target.path), false)
            } else if (!query.isEmpty && result.firstLeaf != null) {
                TreeUtil.selectPath(tree, TreePath(result.firstLeaf.path), false)
            }
        } finally {
            suppressSelection = false
        }

        tree.emptyText.clear()
        if (!query.isEmpty) {
            tree.emptyText.appendLine(McSoundsBundle.message("search.nothing", searchField.text.trim()))
            tree.emptyText.appendLine(McSoundsBundle.message("search.hint"), SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        }
    }

    private fun clearTree() {
        if (builtOnce && !treeFiltered) normalExpansion = expandedKeys()
        builtOnce = false
        builtFor = null
        setRoot(DefaultMutableTreeNode())
    }

    private fun setRoot(root: DefaultMutableTreeNode) {
        treeModel.setRoot(root)
        treeModel.reload()
    }

    private fun expandedKeys(): Set<String> {
        val keys = mutableSetOf<String>()
        val root = treeModel.root as DefaultMutableTreeNode
        for (node in root.depthFirstEnumeration()) {
            val path = TreePath((node as DefaultMutableTreeNode).path)
            if (node !== root && tree.isExpanded(path)) nodeKey(node)?.let { keys += it }
        }
        return keys
    }

    private fun restoreExpansion(keys: Set<String>) {
        val root = treeModel.root as DefaultMutableTreeNode
        val nodes = root.breadthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }
        for (node in nodes) {
            val key = nodeKey(node) ?: continue
            if (key in keys || (keys.isEmpty() && node is FavoritesNode)) tree.expandPath(TreePath(node.path))
        }
    }

    private fun nodeKey(node: DefaultMutableTreeNode): String? = when (node) {
        is FavoritesNode -> "*favorites"
        is CategoryNode -> node.category.name
        is GroupNode -> (node.parent as? CategoryNode)?.category?.name + "/" + node.name
        else -> null
    }

    private fun expandCategories() {
        val root = treeModel.root as DefaultMutableTreeNode
        for (i in 0 until root.childCount) tree.expandPath(TreePath((root.getChildAt(i) as DefaultMutableTreeNode).path))
    }

    private fun findLeaf(event: SoundEvent): SoundLeafNode? {
        val root = treeModel.root as DefaultMutableTreeNode
        return root.depthFirstEnumeration().asSequence()
            .filterIsInstance<SoundLeafNode>()
            .filter { it.event.key == event.key }
            .sortedBy { if (it.parent is FavoritesNode) 1 else 0 }
            .firstOrNull()
    }

    private fun selectFirstLeaf() {
        val root = treeModel.root as DefaultMutableTreeNode
        val leaf = root.depthFirstEnumeration().asSequence().filterIsInstance<SoundLeafNode>()
            .firstOrNull { it.parent !is FavoritesNode } ?: return
        TreeUtil.selectPath(tree, TreePath(leaf.path), false)
    }

    private fun selectedEvent(): SoundEvent? = (tree.lastSelectedPathComponent as? SoundLeafNode)?.event

    private fun playOrReplay(event: SoundEvent) {
        selectAlarm.cancelAllRequests()
        if (player.event == event && player.sound != null) player.replay() else player.open(event, play = true)
    }

    private fun focusTree() {
        if (selectedEvent() == null) selectFirstLeaf()
        tree.requestFocusInWindow()
    }

    private fun revealPlayerEvent() {
        val event = player.event ?: return
        if (!event.isVanilla) return
        val current = selectedEvent()
        if (current?.key == event.key) return
        if (!isShowing) return
        val leaf = findLeaf(event) ?: return
        suppressSelection = true
        try {
            TreeUtil.selectPath(tree, TreePath(leaf.path), true)
        } finally {
            suppressSelection = false
        }
    }
}
