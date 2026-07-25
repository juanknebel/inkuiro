package dev.zero.inkchat.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.zero.inkchat.App
import dev.zero.inkchat.R
import dev.zero.inkchat.data.db.MessageEntity
import dev.zero.inkchat.data.images.ImageStore
import dev.zero.inkchat.databinding.ActivityChatBinding
import dev.zero.inkchat.domain.model.Role
import dev.zero.inkchat.ui.common.Markdown
import dev.zero.inkchat.ui.common.PromptDialog
import dev.zero.inkchat.ui.eink.EinkRefresh
import dev.zero.inkchat.ui.settings.ModelPickerActivity
import io.noties.markwon.Markwon
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var markwon: Markwon
    private val graph get() = (application as App).graph

    private val conversationId: String by lazy {
        requireNotNull(intent.getStringExtra(EXTRA_CONVERSATION_ID)) { "Missing conversationId" }
    }

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.factory(graph.chatRepository, conversationId)
    }

    /** Ids already rendered, so only new views are appended instead of repainting everything. */
    private val renderedIds = mutableListOf<String>()
    private var lastRenderedFontSizeSp = -1
    private var streamingView: TextView? = null
    private var lastStreamingText: String? = null
    private var wasGenerating = false
    private var imeVisible = false
    private var lastRenderedAttachmentPath: String? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val path = withContext(Dispatchers.IO) { ImageStore.store(this@ChatActivity, uri) }
                viewModel.attachImage(path)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        markwon = Markdown.create(this)

        binding.btnBack.setOnClickListener { finish() }
        // Tapping the header opens the model picker for this conversation (plan §7.3).
        binding.headerInfo.setOnClickListener {
            startActivity(ModelPickerActivity.intent(this, conversationId))
        }
        binding.btnPageUp.setOnClickListener { binding.pager.pageUp() }
        binding.btnPageDown.setOnClickListener { binding.pager.pageDown() }
        binding.btnRetry.setOnClickListener { viewModel.retry() }
        binding.btnWebSearch.setOnClickListener { viewModel.toggleWebSearch() }
        binding.btnMore.setOnClickListener { showMoreActionsMenu() }
        binding.btnAttach.setOnClickListener { pickImage.launch("image/*") }
        binding.btnRemoveAttachment.setOnClickListener { viewModel.clearAttachedImage() }
        binding.btnSend.setOnClickListener {
            if (viewModel.state.value.generating) {
                viewModel.stop()
            } else {
                val text = binding.input.text.toString()
                if (text.isNotBlank() || viewModel.state.value.pendingImagePath != null) {
                    binding.input.setText("")
                    viewModel.send(text)
                }
            }
        }
        // Blinking cursor only while the input is focused (plan §7.1).
        binding.input.setOnFocusChangeListener { view, hasFocus ->
            (view as EditText).isCursorVisible = hasFocus
        }

        // Keyboard open → fast A2 mode; on close → full refresh (plan §7.3).
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val visible = insets.isVisible(WindowInsets.Type.ime())
            if (visible != imeVisible) {
                imeVisible = visible
                if (visible) {
                    EinkRefresh.applyFastMode(binding.root)
                } else {
                    EinkRefresh.applyQualityMode(binding.root)
                    EinkRefresh.fullRefresh(binding.root)
                }
            }
            view.onApplyWindowInsets(insets)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.refreshEvents.collect {
                        EinkRefresh.applyQualityMode(binding.root)
                        EinkRefresh.fullRefresh(binding.root)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The model may have changed in the picker.
        viewModel.reloadConversation()
        // The font size may have changed in Settings: force a full re-render
        // (renderMessages() otherwise skips it when the id list is unchanged).
        val currentFontSize = graph.securePrefs.messageFontSizeSp
        if (currentFontSize != lastRenderedFontSizeSp) {
            lastRenderedFontSizeSp = currentFontSize
            renderedIds.clear()
        }
        EinkRefresh.fullRefresh(binding.root)
    }

    /**
     * Palma physical button: when configured as a page turner it sends
     * PAGE_UP/PAGE_DOWN; volume keys are mapped as well.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_VOLUME_UP -> {
            binding.pager.pageUp()
            true
        }
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> {
            binding.pager.pageDown()
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun render(state: ChatViewModel.UiState) {
        state.conversation?.let {
            binding.txtTitle.text = it.title
            binding.txtModel.text = it.modelId
        }

        val providerId = state.conversation?.providerId
        val supportsWebSearch = providerId != null && graph.providerRegistry.get(providerId)?.supportsWebSearch == true
        binding.btnWebSearch.isVisible = supportsWebSearch
        if (supportsWebSearch) renderWebSearchToggle(state.webSearchEnabled)

        val hasTokens = state.tokenUsage.tokensIn > 0 || state.tokenUsage.tokensOut > 0
        binding.txtTokens.isVisible = hasTokens
        if (hasTokens) {
            binding.txtTokens.text = getString(
                R.string.token_usage_format,
                formatTokenCount(state.tokenUsage.tokensIn),
                formatTokenCount(state.tokenUsage.tokensOut),
            )
        }

        // Generation start → fast mode on the message area; the return to
        // quality + full refresh is triggered by refreshEvents at the end.
        if (state.generating && !wasGenerating) {
            EinkRefresh.applyFastMode(binding.pager)
        }
        wasGenerating = state.generating

        binding.btnMore.isVisible = state.messages.isNotEmpty() && !state.generating

        binding.attachmentPreview.isVisible = state.pendingImagePath != null
        if (state.pendingImagePath != null && state.pendingImagePath != lastRenderedAttachmentPath) {
            lastRenderedAttachmentPath = state.pendingImagePath
            binding.imgAttachmentPreview.setImageURI(Uri.fromFile(File(state.pendingImagePath)))
        }

        renderMessages(state.messages)
        renderStreaming(state)

        binding.generatingLabel.isVisible = state.generating
        binding.btnSend.text = getString(if (state.generating) R.string.stop else R.string.send)

        binding.errorBanner.isVisible = state.error != null
        binding.errorBanner.text = state.error?.message
        binding.btnRetry.isVisible = state.error?.canRetry == true
    }

    /** Persistent on/off look via inversion — Flat's pressed-state drawable is momentary, not sticky. */
    private fun renderWebSearchToggle(enabled: Boolean) {
        binding.btnWebSearch.contentDescription =
            getString(if (enabled) R.string.web_search_on else R.string.web_search_off)
        if (enabled) {
            binding.btnWebSearch.setBackgroundColor(getColor(R.color.ink_black))
            binding.btnWebSearch.setTextColor(getColor(R.color.ink_white))
        } else {
            binding.btnWebSearch.setBackgroundResource(R.drawable.bg_button_flat)
            binding.btnWebSearch.setTextColor(getColorStateList(R.color.flat_button_text))
        }
    }

    private fun showMoreActionsMenu() {
        val canRegenerate = viewModel.canRegenerate()
        val lastUserMessage = viewModel.lastUserMessage()
        val actions = buildList {
            if (canRegenerate) add(getString(R.string.regenerate_response) to { viewModel.regenerateLastResponse() })
            if (lastUserMessage != null) {
                add(getString(R.string.edit_last_message) to {
                    PromptDialog.show(this@ChatActivity, getString(R.string.edit_last_message), lastUserMessage.content) { newText ->
                        viewModel.editLastUserMessage(newText)
                    }
                })
            }
        }
        if (actions.isEmpty()) return
        AlertDialog.Builder(this, R.style.Theme_InkChat_Dialog)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun renderMessages(messages: List<MessageEntity>) {
        val ids = messages.map { it.id }
        if (ids == renderedIds) return

        val isAppendOnly = ids.size > renderedIds.size &&
            ids.subList(0, renderedIds.size) == renderedIds
        if (isAppendOnly) {
            messages.drop(renderedIds.size).forEach { addMessageView(it) }
        } else {
            binding.messagesContainer.removeAllViews()
            streamingView = null
            lastStreamingText = null
            messages.forEach { addMessageView(it) }
        }
        renderedIds.clear()
        renderedIds.addAll(ids)
        scrollToBottom()
    }

    private fun renderStreaming(state: ChatViewModel.UiState) {
        val text = state.streamingText
        if (text == null) {
            streamingView?.let { binding.messagesContainer.removeView(it) }
            streamingView = null
            lastStreamingText = null
            return
        }
        val view = streamingView ?: newMessageView(isUser = false).also {
            streamingView = it
            binding.messagesContainer.addView(it)
        }
        if (text != lastStreamingText) {
            lastStreamingText = text
            if (text.isNotEmpty()) {
                Markdown.render(markwon, view, text)
                scrollToBottom()
            }
        }
    }

    private fun addMessageView(message: MessageEntity) {
        val isUser = message.role == Role.USER.wire
        val index = streamingView?.let { binding.messagesContainer.indexOfChild(it) } ?: -1
        if (isUser && message.imagePath != null) {
            val container = newMessageContainer()
            container.addView(newImagePreview(message.imagePath))
            val textView = newMessageView(isUser = true)
            textView.text = message.content
            container.addView(textView)
            binding.messagesContainer.addView(container, index)
            return
        }
        val view = newMessageView(isUser)
        if (isUser) {
            view.text = message.content
        } else {
            Markdown.render(markwon, view, message.content)
        }
        binding.messagesContainer.addView(view, index)
    }

    private fun newMessageView(isUser: Boolean): TextView {
        val view = TextView(this)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, graph.securePrefs.messageFontSizeSp.toFloat())
        view.setTextColor(getColor(R.color.ink_black))
        view.setLineSpacing(0f, 1.4f)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.bottomMargin = dp(16)
        view.layoutParams = params
        if (isUser) {
            // Quote style: black bar on the left + bold, no box.
            view.typeface = android.graphics.Typeface.DEFAULT_BOLD
            view.setBackgroundResource(R.drawable.bg_quote_user)
            view.setPadding(dp(14), dp(2), dp(8), dp(2))
        }
        return view
    }

    private fun newMessageContainer(): LinearLayout {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.bottomMargin = dp(16)
        container.layoutParams = params
        return container
    }

    private fun newImagePreview(path: String): ImageView {
        val view = ImageView(this)
        view.setImageURI(Uri.fromFile(File(path)))
        view.scaleType = ImageView.ScaleType.FIT_START
        view.adjustViewBounds = true
        view.contentDescription = getString(R.string.attached_image)
        val params = LinearLayout.LayoutParams(dp(160), dp(160))
        params.bottomMargin = dp(4)
        view.layoutParams = params
        return view
    }

    private fun scrollToBottom() {
        binding.pager.post { binding.pager.toBottom() }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun formatTokenCount(n: Int): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
        n >= 1_000 -> "%.1fk".format(n / 1_000f)
        else -> n.toString()
    }

    companion object {
        private const val EXTRA_CONVERSATION_ID = "conversation_id"

        fun intent(context: Context, conversationId: String): Intent =
            Intent(context, ChatActivity::class.java).putExtra(EXTRA_CONVERSATION_ID, conversationId)
    }
}
