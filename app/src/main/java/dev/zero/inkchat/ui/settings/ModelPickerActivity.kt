package dev.zero.inkchat.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import dev.zero.inkchat.App
import dev.zero.inkchat.R
import dev.zero.inkchat.data.provider.openrouter.OpenRouterProvider
import dev.zero.inkchat.databinding.ActivityModelPickerBinding
import dev.zero.inkchat.domain.model.ModelInfo
import dev.zero.inkchat.ui.common.TwoLine
import dev.zero.inkchat.ui.eink.EinkRefresh
import kotlinx.coroutines.launch

/**
 * Model picker with search and 20-per-page pagination.
 * Without EXTRA_CONVERSATION_ID it picks the default model (Settings);
 * with it, it changes that conversation's model (affects only future messages).
 */
class ModelPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelPickerBinding
    private val graph get() = (application as App).graph
    private val conversationId: String? by lazy { intent.getStringExtra(EXTRA_CONVERSATION_ID) }

    private var models: List<ModelInfo> = emptyList()
    private var filtered: List<ModelInfo> = emptyList()
    private var page = 0
    private var providerId: String = OpenRouterProvider.ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRetryModels.setOnClickListener { load() }
        binding.btnPrevPage.setOnClickListener { if (page > 0) { page--; renderPage() } }
        binding.btnNextPage.setOnClickListener { if (page < pageCount - 1) { page++; renderPage() } }
        binding.inputSearch.setOnFocusChangeListener { view, hasFocus ->
            (view as android.widget.EditText).isCursorVisible = hasFocus
        }
        binding.inputSearch.doAfterTextChanged { applyFilter() }

        load()
    }

    override fun onResume() {
        super.onResume()
        EinkRefresh.fullRefresh(binding.root)
    }

    private val pageCount: Int
        get() = if (filtered.isEmpty()) 1 else (filtered.size + PAGE_SIZE - 1) / PAGE_SIZE

    private fun load() {
        binding.txtPickerStatus.isVisible = true
        binding.txtPickerStatus.text = getString(R.string.loading_models)
        binding.btnRetryModels.isVisible = false
        binding.modelsContainer.removeAllViews()

        lifecycleScope.launch {
            try {
                providerId = conversationId
                    ?.let { graph.database.conversationDao().getById(it)?.providerId }
                    ?: intent.getStringExtra(EXTRA_PROVIDER_ID)
                    ?: (graph.securePrefs.activeProviderId ?: OpenRouterProvider.ID)
                models = graph.providerRegistry.require(providerId).listModels()
                binding.txtPickerStatus.isVisible = false
                applyFilter()
            } catch (e: Exception) {
                binding.txtPickerStatus.text = e.message ?: getString(R.string.connection_failed, "")
                binding.btnRetryModels.isVisible = true
            }
        }
    }

    private fun applyFilter() {
        val query = binding.inputSearch.text.toString().trim().lowercase()
        filtered = if (query.isEmpty()) models else models.filter {
            it.id.lowercase().contains(query) || it.displayName.lowercase().contains(query)
        }
        page = 0
        renderPage()
    }

    private fun renderPage() {
        binding.modelsContainer.removeAllViews()
        val slice = filtered.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        if (models.isNotEmpty() && slice.isEmpty()) {
            binding.txtPickerStatus.isVisible = true
            binding.txtPickerStatus.text = getString(R.string.no_models)
        } else if (models.isNotEmpty()) {
            binding.txtPickerStatus.isVisible = false
        }
        slice.forEach { binding.modelsContainer.addView(modelView(it)) }
        binding.lblPage.text = getString(R.string.page_indicator, page + 1, pageCount)
        binding.btnPrevPage.isEnabled = page > 0
        binding.btnNextPage.isEnabled = page < pageCount - 1
    }

    private fun modelView(model: ModelInfo): TextView {
        val view = TextView(this)
        val context = model.contextLength?.let { " · ${it / 1000}k ctx" }.orEmpty()
        view.text = TwoLine.of(model.displayName, "${model.id}$context")
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        // Flat row with inversion when pressed, same as the main list.
        view.setTextColor(getColorStateList(R.color.flat_button_text))
        view.setLineSpacing(0f, 1.3f)
        view.setBackgroundResource(R.drawable.bg_list_item)
        view.setPadding(dp(16), dp(12), dp(16), dp(12))
        view.minHeight = dp(56)
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        view.setOnClickListener { select(model) }
        return view
    }

    private fun select(model: ModelInfo) {
        lifecycleScope.launch {
            val targetConversation = conversationId
            if (targetConversation != null) {
                graph.database.conversationDao()
                    .setModel(targetConversation, model.id, System.currentTimeMillis())
            } else {
                graph.securePrefs.setDefaultModel(providerId, model.id)
            }
            finish()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE_SIZE = 20
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val EXTRA_PROVIDER_ID = "provider_id"

        /**
         * @param conversationId when set, picks the model for that conversation.
         * @param providerId when set (and no conversationId), picks the default
         *   model for that specific provider; otherwise the active provider.
         */
        fun intent(context: Context, conversationId: String?, providerId: String? = null): Intent =
            Intent(context, ModelPickerActivity::class.java)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(EXTRA_PROVIDER_ID, providerId)
    }
}
