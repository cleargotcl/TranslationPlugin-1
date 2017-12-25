package cn.yiiguxing.plugin.translate.ui

import cn.yiiguxing.plugin.translate.*
import cn.yiiguxing.plugin.translate.model.QueryResult
import cn.yiiguxing.plugin.translate.trans.*
import cn.yiiguxing.plugin.translate.tts.TextToSpeech
import cn.yiiguxing.plugin.translate.util.invokeLater
import cn.yiiguxing.plugin.translate.util.isNullOrBlank
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.HyperlinkAdapter
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.util.ui.*
import java.awt.AWTEvent
import java.awt.Component.RIGHT_ALIGNMENT
import java.awt.Component.TOP_ALIGNMENT
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.MenuElement
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent

class TranslationBalloon(
        private val editor: Editor,
        private val caretRangeMarker: RangeMarker,
        private val text: String
) : View {

    private val project: Project? = editor.project
    private val presenter: Presenter = TranslationPresenter(this)
    private val settings: Settings = Settings.instance

    private val layout = FixedSizeCardLayout()
    private val contentPanel = JBPanel<JBPanel<*>>(layout)
    private val errorPane = JEditorPane()
    private val processPane = ProcessComponent("Querying...", JBUI.insets(INSETS))
    private val translationContentPane = NonOpaquePanel(FrameLayout())
    private val translationPane = BalloonTranslationPanel(settings)
    private val pinButton = ActionLink(icon = Icons.Pin) { showOnTranslationDialog(text) }

    private val balloon: Balloon
    private var targetLocation: RelativePoint? = null

    private var isShowing = false
    private var _disposed = false
    override val disposed get() = _disposed
    private var ttsDisposable: Disposable? = null

    private var lastMoveWasInsideBalloon = false
    private val eventListener = AWTEventListener {
        if (it is MouseEvent && it.id == MouseEvent.MOUSE_MOVED) {
            val inside = isInsideBalloon(RelativePoint(it))
            if (inside != lastMoveWasInsideBalloon) {
                lastMoveWasInsideBalloon = inside
                pinButton.isVisible = inside
            }
        }
    }

    init {
        initErrorPane()
        initTranslationContent()
        initContentPanel()

        balloon = createBalloon(contentPanel)
        initActions()

        updateCaretPosition()

        project?.let { Disposer.register(it, balloon) }
        Disposer.register(balloon, this)
        Disposer.register(this, processPane)
    }

    private fun initContentPanel() = contentPanel
            .withFont(UI.defaultFont)
            .andTransparent()
            .apply {
                add(CARD_PROCESSING, processPane)
                add(CARD_TRANSLATION, translationContentPane)
                add(CARD_ERROR, errorPane)
            }


    private fun initTranslationContent() = translationContentPane.apply {
        add(pinButton.apply {
            isVisible = false
            alignmentX = RIGHT_ALIGNMENT
            alignmentY = TOP_ALIGNMENT
        })
        add(translationPane.component.apply {
            border = JBEmptyBorder(16, 16, 10, 16)
        })
    }

    private fun initActions() = with(translationPane) {
        onRevalidate { balloon.revalidate() }
        onLanguageChanged { src, target -> presenter.translate(text) }
        onFixLanguage { presenter.translate(text) }
        onNewTranslate { showOnTranslationDialog(it) }
        onTextToSpeech { text, lang ->
            ttsDisposable = TextToSpeech.INSTANCE.speak(project, text, lang)
        }

        Toolkit.getDefaultToolkit().addAWTEventListener(eventListener, AWTEvent.MOUSE_MOTION_EVENT_MASK)
    }

    private fun initErrorPane() = errorPane.apply {
        contentType = "text/html"
        isEditable = false
        isOpaque = false
        editorKit = UI.errorHTMLKit
        border = JBEmptyBorder(INSETS)
        maximumSize = JBDimension(MAX_WIDTH, Int.MAX_VALUE)

        addHyperlinkListener(object : HyperlinkAdapter() {
            override fun hyperlinkActivated(hyperlinkEvent: HyperlinkEvent) {
                if (HTML_DESCRIPTION_SETTINGS == hyperlinkEvent.description) {
                    this@TranslationBalloon.hide()
                    OptionsConfigurable.showSettingsDialog(project)
                }
            }
        })
    }

    private fun isInsideBalloon(target: RelativePoint): Boolean {
        val cmp = target.originalComponent
        val content = contentPanel

        return when {
            cmp === pinButton -> true
            !cmp.isShowing -> true
            cmp is MenuElement -> false
            UIUtil.isDescendingFrom(cmp, content) -> true
            !content.isShowing -> false
            else -> {
                val point = target.screenPoint
                SwingUtilities.convertPointFromScreen(point, content)
                content.contains(point)
            }
        }
    }

    private fun updateCaretPosition() {
        with(caretRangeMarker) {
            if (isValid) {
                val offset = Math.round((startOffset + endOffset) / 2f)
                editor.apply {
                    val position = offsetToVisualPosition(offset)
                    putUserData<VisualPosition>(PopupFactoryImpl.ANCHOR_POPUP_POSITION, position)
                }
            }
        }
    }

    override fun dispose() {
        if (disposed) {
            return
        }

        _disposed = true
        isShowing = false

        balloon.hide()
        caretRangeMarker.dispose()
        ttsDisposable?.let { Disposer.dispose(it) }
        Toolkit.getDefaultToolkit().removeAWTEventListener(eventListener)

        println("Balloon disposed.")
    }

    fun hide() {
        if (!disposed) {
            Disposer.dispose(this)
        }
    }

    fun show() {
        check(!disposed) { "Balloon was disposed." }

        if (!isShowing) {
            isShowing = true

            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            showCard(CARD_PROCESSING, false)
            showBalloon()
            presenter.translate(text)
        }
    }

    private fun showCard(card: String, revalidate: Boolean = true) {
        layout.show(contentPanel, card)
        if (revalidate) {
            balloon.revalidate()
            // 大小还是没有调整好，再刷一次
            invokeLater { balloon.revalidate() }
        }
    }

    private fun showOnTranslationDialog(text: String?) {
        hide()
        val dialog = TranslationManager.instance.showDialog(editor.project)
        if (!text.isNullOrBlank()) {
            dialog.query(text)
        }
    }

    override fun showStartTranslate(query: String) {
        if (!disposed) {
            showCard(CARD_PROCESSING)
        }
    }

    private fun showBalloon() {
        val popupFactory = JBPopupFactory.getInstance()
        balloon.show(object : PositionTracker<Balloon>(editor.contentComponent) {
            override fun recalculateLocation(balloon: Balloon): RelativePoint? {
                if (targetLocation != null && !popupFactory.isBestPopupLocationVisible(editor)) {
                    return targetLocation
                }

                updateCaretPosition()

                val target = popupFactory.guessBestPopupLocation(editor)
                val visibleArea = editor.scrollingModel.visibleArea
                val point = Point(visibleArea.x, visibleArea.y)
                SwingUtilities.convertPointToScreen(point, component)

                val screenPoint = target.screenPoint
                val y = screenPoint.y - point.y
                if (targetLocation != null && y + balloon.preferredSize.getHeight() > visibleArea.height) {
                    //FIXME 只是判断垂直方向，没有判断水平方向，但水平方向问题不是很大。
                    //FIXME 垂直方向上也只是判断Balloon显示在下方的情况，还是有些小问题。
                    return targetLocation
                }

                targetLocation = RelativePoint(Point(screenPoint.x, screenPoint.y))
                return targetLocation
            }
        }, Balloon.Position.below)
    }

    override fun showResult(query: String, result: QueryResult) {
        if (disposed) {
            return
        }

        val dictionaries = listOf(
                Dict("动词", entries = listOf(
                        DictEntry("显示", listOf("display", "show", "demonstrate", "illustrate")),
                        DictEntry("陈列", listOf("display", "exhibit", "set out")),
                        DictEntry("展出", listOf("display", "exhibit", "be on show")),
                        DictEntry("展览", listOf("exhibit", "display")),
                        DictEntry("display", listOf("显示", "陈列", "展出", "展览")),
                        DictEntry("表现",
                                listOf("show", "express", "behave", "display", "represent", "manifest")),
                        DictEntry("陈设", listOf("display", "furnish", "set out")),
                        DictEntry("陈设2", listOf("display", "furnish", "set out"))
                )),
                Dict("名词", entries = listOf(
                        DictEntry("显示", listOf("display")),
                        DictEntry("表现", listOf("performance", "show", "expression", "manifestation",
                                "representation", "display")),
                        DictEntry("炫耀", listOf("display")),
                        DictEntry("橱窗", listOf("showcase", "show window", "display", "shopwindow",
                                "glass-fronted billboard")),
                        DictEntry("罗", listOf("silk", "net", "display", "shift"))
                ))
        )

        val trans = Translation(
                "If baby only wanted to, he could fly up to heaven this moment. It is not for nothing that he does not leave us.",
                "显示",
                Lang.ENGLISH,
                Lang.CHINESE,
                Symbol("dɪ'spleɪ", "xiǎn shì"),
                dictionaries
        )
        translationPane.apply {
            srcLang = Lang.AUTO
            setSupportedLanguages(Lang.values().asList(), Lang.values().asList())
            translation = trans
        }
        showCard(CARD_TRANSLATION)
    }

    override fun showError(query: String, error: String) {
        if (!disposed) {
            errorPane.text = error
            showCard(CARD_ERROR)
        }
    }

    companion object {

        private const val MAX_WIDTH = 600
        private const val INSETS = 20

        private const val CARD_PROCESSING = "processing"
        private const val CARD_ERROR = "error"
        private const val CARD_TRANSLATION = "translation"

        private fun createBalloon(content: JComponent): Balloon = JBPopupFactory
                .getInstance()
                .createDialogBalloonBuilder(content, null)
                .setHideOnClickOutside(true)
                .setShadow(true)
                .setHideOnKeyOutside(true)
                .setBlockClicksThroughBalloon(true)
                .setCloseButtonEnabled(false)
                .createBalloon()
    }
}