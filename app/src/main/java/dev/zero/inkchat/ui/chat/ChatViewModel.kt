package dev.zero.inkchat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.zero.inkchat.data.db.ConversationEntity
import dev.zero.inkchat.data.db.MessageEntity
import dev.zero.inkchat.domain.ChatRepository
import dev.zero.inkchat.i18n.Msg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val repository: ChatRepository,
    private val conversationId: String,
) : ViewModel() {

    data class ErrorUi(
        val message: String,
        /** Show the "Retry" button: it failed before persisting anything. */
        val canRetry: Boolean,
    )

    data class UiState(
        val conversation: ConversationEntity? = null,
        val messages: List<MessageEntity> = emptyList(),
        /** Partial text of the in-progress assistant message; null when idle. */
        val streamingText: String? = null,
        val generating: Boolean = false,
        val error: ErrorUi? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** E-ink full refresh signal: fired when each generation ends. */
    private val refreshChannel = Channel<Unit>(Channel.CONFLATED)
    val refreshEvents = refreshChannel.receiveAsFlow()

    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            _state.update { it.copy(conversation = repository.getConversation(conversationId)) }
        }
        viewModelScope.launch {
            repository.observeMessages(conversationId).collect { messages ->
                _state.update { it.copy(messages = messages) }
            }
        }
    }

    fun reloadConversation() {
        viewModelScope.launch {
            _state.update { it.copy(conversation = repository.getConversation(conversationId)) }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.generating) return
        generationJob = viewModelScope.launch {
            val conversation = repository.getConversation(conversationId) ?: return@launch
            repository.appendUserMessage(conversation, trimmed)
            // The title may have been auto-generated from the first message.
            _state.update { it.copy(conversation = repository.getConversation(conversationId)) }
            runGeneration(conversation)
        }
    }

    /** Manual retry after a recoverable error that left no partial reply. */
    fun retry() {
        if (_state.value.generating) return
        generationJob = viewModelScope.launch {
            val conversation = repository.getConversation(conversationId) ?: return@launch
            runGeneration(conversation)
        }
    }

    fun stop() {
        generationJob?.cancel()
    }

    private suspend fun runGeneration(conversation: ConversationEntity) {
        _state.update { it.copy(generating = true, error = null, streamingText = "") }
        var partial = ""
        var settled = false

        try {
            val history = repository.listMessages(conversation.id)
            repository.streamReply(conversation, history).coalesceForEink().collect { update ->
                when (update) {
                    is StreamUpdate.Progress -> {
                        partial = update.markdown
                        _state.update { it.copy(streamingText = update.markdown) }
                    }

                    is StreamUpdate.Finished -> {
                        settled = true
                        if (update.markdown.isNotBlank()) {
                            repository.saveAssistantMessage(
                                conversation.id, update.markdown, conversation.modelId,
                                update.inTokens, update.outTokens,
                            )
                        }
                        _state.update {
                            it.copy(
                                streamingText = null,
                                generating = false,
                                error = if (update.markdown.isBlank()) {
                                    ErrorUi(Msg.emptyResponse, canRetry = true)
                                } else null,
                            )
                        }
                        refreshChannel.trySend(Unit)
                    }

                    is StreamUpdate.Failed -> {
                        settled = true
                        val hasPartial = update.partialMarkdown.isNotBlank()
                        if (hasPartial) {
                            repository.saveAssistantMessage(
                                conversation.id, update.partialMarkdown, conversation.modelId, null, null,
                            )
                        }
                        _state.update {
                            it.copy(
                                streamingText = null,
                                generating = false,
                                error = ErrorUi(update.errorMessage, canRetry = update.recoverable && !hasPartial),
                            )
                        }
                        refreshChannel.trySend(Unit)
                    }
                }
            }
            // Stream ended without a terminal event (should not happen): keep the partial text.
            if (!settled) {
                if (partial.isNotBlank()) {
                    repository.saveAssistantMessage(conversation.id, partial, conversation.modelId, null, null)
                }
                _state.update {
                    it.copy(
                        streamingText = null,
                        generating = false,
                        error = ErrorUi(Msg.streamCut, canRetry = partial.isBlank()),
                    )
                }
                refreshChannel.trySend(Unit)
            }
        } catch (e: CancellationException) {
            // "Stop" button: persist whatever was generated so far (acceptance criterion 2).
            if (!settled && partial.isNotBlank()) {
                withContext(NonCancellable) {
                    repository.saveAssistantMessage(conversation.id, partial, conversation.modelId, null, null)
                }
            }
            _state.update { it.copy(streamingText = null, generating = false) }
            refreshChannel.trySend(Unit)
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    streamingText = null,
                    generating = false,
                    error = ErrorUi(e.message ?: Msg.unexpectedError, canRetry = partial.isBlank()),
                )
            }
            refreshChannel.trySend(Unit)
        }
    }

    companion object {
        fun factory(repository: ChatRepository, conversationId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(repository, conversationId) as T
            }
    }
}
