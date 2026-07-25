package dev.zero.inkchat.ui.chatlist

import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.zero.inkchat.data.provider.openrouter.OpenRouterProvider
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.zero.inkchat.App
import dev.zero.inkchat.R
import dev.zero.inkchat.data.db.ConversationEntity
import dev.zero.inkchat.databinding.ActivityChatListBinding
import dev.zero.inkchat.ui.chat.ChatActivity
import dev.zero.inkchat.ui.common.PromptDialog
import dev.zero.inkchat.ui.common.TwoLine
import dev.zero.inkchat.ui.eink.EinkRefresh
import dev.zero.inkchat.ui.settings.SettingsActivity
import kotlinx.coroutines.launch

/**
 * Home screen: conversation list paginated by 20, create,
 * long-press to rename/delete, access to Settings.
 */
class ChatListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatListBinding
    private val graph get() = (application as App).graph

    private var conversations: List<ConversationEntity> = emptyList()
    private var searchQuery: String = ""
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnNewConversation.setOnClickListener {
            lifecycleScope.launch {
                val conversation = graph.chatRepository.createConversation()
                startActivity(ChatActivity.intent(this@ChatListActivity, conversation.id))
            }
        }
        binding.btnSettings.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        binding.btnProvider.setOnClickListener { showProviderPicker() }
        binding.searchInput.setOnFocusChangeListener { view, hasFocus ->
            (view as EditText).isCursorVisible = hasFocus
        }
        binding.searchInput.doAfterTextChanged {
            searchQuery = it?.toString().orEmpty()
            page = 0
            renderPage()
        }
        binding.btnPrevPage.setOnClickListener {
            if (page > 0) { page--; renderPage() }
        }
        binding.btnNextPage.setOnClickListener {
            if (page < pageCount - 1) { page++; renderPage() }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.database.conversationDao().observeAll().collect { list ->
                    conversations = list
                    page = page.coerceIn(0, pageCount - 1)
                    renderPage()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderProvider()
        EinkRefresh.fullRefresh(binding.root)
    }

    private fun renderProvider() {
        val provider = graph.providerRegistry.get(
            graph.securePrefs.activeProviderId ?: OpenRouterProvider.ID
        ) ?: graph.providerRegistry.all.first()
        binding.btnProvider.text = getString(R.string.new_conversations_provider, provider.displayName)
    }

    private fun showProviderPicker() {
        val providers = graph.providerRegistry.all
        AlertDialog.Builder(this, R.style.Theme_InkChat_Dialog)
            .setTitle(R.string.default_provider_title)
            .setItems(providers.map { it.displayName }.toTypedArray()) { _, which ->
                graph.securePrefs.activeProviderId = providers[which].id
                renderProvider()
            }
            .show()
    }

    private val filteredConversations: List<ConversationEntity>
        get() = if (searchQuery.isBlank()) {
            conversations
        } else {
            conversations.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.modelId.contains(searchQuery, ignoreCase = true)
            }
        }

    private val pageCount: Int
        get() {
            val count = filteredConversations.size
            return if (count == 0) 1 else (count + PAGE_SIZE - 1) / PAGE_SIZE
        }

    private fun renderPage() {
        val filtered = filteredConversations
        binding.emptyState.isVisible = filtered.isEmpty()
        binding.emptyState.text = if (searchQuery.isNotBlank()) {
            getString(R.string.no_search_results)
        } else {
            getString(R.string.empty_conversations)
        }
        binding.paginationBar.isVisible = filtered.size > PAGE_SIZE
        binding.listContainer.removeAllViews()
        filtered.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEach { conversation ->
            binding.listContainer.addView(conversationView(conversation))
        }
        binding.lblPage.text = getString(R.string.page_indicator, page + 1, pageCount)
        binding.btnPrevPage.isEnabled = page > 0
        binding.btnNextPage.isEnabled = page < pageCount - 1
    }

    private fun conversationView(conversation: ConversationEntity): TextView {
        val view = TextView(this)
        val relativeDate = DateUtils.getRelativeTimeSpanString(
            conversation.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        )
        // Two-line row: bold title, smaller metadata.
        view.text = TwoLine.of(conversation.title, "${conversation.modelId} · $relativeDate")
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        // Flat row with inversion when pressed (white text on black).
        view.setTextColor(getColorStateList(R.color.flat_button_text))
        view.setLineSpacing(0f, 1.3f)
        view.setBackgroundResource(R.drawable.bg_list_item)
        view.setPadding(dp(16), dp(14), dp(16), dp(14))
        view.minHeight = dp(56)
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        view.setOnClickListener {
            startActivity(ChatActivity.intent(this, conversation.id))
        }
        view.setOnLongClickListener {
            showItemMenu(conversation)
            true
        }
        return view
    }

    private fun showItemMenu(conversation: ConversationEntity) {
        AlertDialog.Builder(this, R.style.Theme_InkChat_Dialog)
            .setTitle(conversation.title)
            .setItems(arrayOf(getString(R.string.rename), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> promptRename(conversation)
                    1 -> confirmDelete(conversation)
                }
            }
            .show()
    }

    private fun promptRename(conversation: ConversationEntity) {
        PromptDialog.show(this, getString(R.string.rename), conversation.title) { title ->
            lifecycleScope.launch {
                graph.database.conversationDao()
                    .rename(conversation.id, title, System.currentTimeMillis())
            }
        }
    }

    private fun confirmDelete(conversation: ConversationEntity) {
        AlertDialog.Builder(this, R.style.Theme_InkChat_Dialog)
            .setTitle(conversation.title)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    graph.database.conversationDao().delete(conversation.id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE_SIZE = 20
    }
}
