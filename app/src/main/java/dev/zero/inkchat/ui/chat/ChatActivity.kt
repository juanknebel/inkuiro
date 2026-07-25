package dev.zero.inkchat.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.zero.inkchat.App
import dev.zero.inkchat.R
import dev.zero.inkchat.data.db.MessageEntity
import dev.zero.inkchat.databinding.ActivityChatBinding
import dev.zero.inkchat.domain.model.Role
import dev.zero.inkchat.ui.common.Markdown
import dev.zero.inkchat.ui.eink.EinkRefresh
import dev.zero.inkchat.ui.settings.ModelPickerActivity
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var markwon: Markwon

    private val conversationId: String by lazy {
        requireNotNull(intent.getStringExtra(EXTRA_CONVERSATION_ID)) { "Missing conversationId" }
    }

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.factory((application as App).graph.chatRepository, conversationId)
    }

    /** Ids already rendered, so only new views are appended instead of repainting everything. */
    private val renderedIds = mutableListOf<String>()
    private var streamingView: TextView? = null
    private var lastStreamingText: String? = null
    private var wasGenerating = false
    private var imeVisible = false

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
        binding.btnSend.setOnClickListener {
            if (viewModel.state.value.generating) {
                viewModel.stop()
            } else {
                val text = binding.input.text.toString()
                if (text.isNotBlank()) {
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

        // Generation start → fast mode on the message area; the return to
        // quality + full refresh is triggered by refreshEvents at the end.
        if (state.generating && !wasGenerating) {
            EinkRefresh.applyFastMode(binding.pager)
        }
        wasGenerating = state.generating

        renderMessages(state.messages)
        renderStreaming(state)

        binding.generatingLabel.isVisible = state.generating
        binding.btnSend.text = getString(if (state.generating) R.string.stop else R.string.send)

        binding.errorBanner.isVisible = state.error != null
        binding.errorBanner.text = state.error?.message
        binding.btnRetry.isVisible = state.error?.canRetry == true
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
        val view = newMessageView(isUser)
        if (isUser) {
            view.text = message.content
        } else {
            Markdown.render(markwon, view, message.content)
        }
        // The in-progress message (if any) always stays last.
        val index = streamingView?.let { binding.messagesContainer.indexOfChild(it) } ?: -1
        binding.messagesContainer.addView(view, index)
    }

    private fun newMessageView(isUser: Boolean): TextView {
        val view = TextView(this)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
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

    private fun scrollToBottom() {
        binding.pager.post { binding.pager.toBottom() }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_CONVERSATION_ID = "conversation_id"

        fun intent(context: Context, conversationId: String): Intent =
            Intent(context, ChatActivity::class.java).putExtra(EXTRA_CONVERSATION_ID, conversationId)
    }
}
