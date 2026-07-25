package dev.zero.inkchat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.zero.inkchat.data.db.ConversationEntity
import dev.zero.inkchat.data.db.MessageEntity
import dev.zero.inkchat.data.db.TokenUsage
import dev.zero.inkchat.data.images.ImageStore
import dev.zero.inkchat.domain.ChatRepository
import dev.zero.inkchat.domain.model.Role
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
        /** Sticky until toggled off; only honored when the provider supports it. */
        val webSearchEnabled: Boolean = false,
        val tokenUsage: TokenUsage = TokenUsage(0, 0),
        /** Local path of an image picked for the next outgoing message, if any. */
        val pendingImagePath: String? = null,
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
        viewModelScope.launch {
            repository.observeTokenUsage(conversationId).collect { usage ->
                _state.update { it.copy(tokenUsage = usage) }
            }
        }
    }

    fun reloadConversation() {
        viewModelScope.launch {
            _state.update { it.copy(conversation = repository.getConversation(conversationId)) }
        }
    }

    fun toggleWebSearch() {
        _state.update { it.copy(webSearchEnabled = !it.webSearchEnabled) }
    }

    /** Called once the picked image has been downscaled and stored locally. */
    fun attachImage(path: String) {
        _state.value.pendingImagePath?.let { ImageStore.delete(it) }
        _state.update { it.copy(pendingImagePath = path) }
    }

    fun clearAttachedImage() {
        _state.value.pendingImagePath?.let { ImageStore.delete(it) }
        _state.update { it.copy(pendingImagePath = null) }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && _state.value.pendingImagePath == null) || _state.value.generating) return
        val imagePath = _state.value.pendingImagePath
        generationJob = viewModelScope.launch {
            val conversation = repository.getConversation(conversationId) ?: return@launch
            repository.appendUserMessage(conversation, trimmed, imagePath)
            _state.update { it.copy(pendingImagePath = null) }
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

    /** Deletes the last assistant reply and asks for a new one. Only valid when the last message is one. */
    fun regenerateLastResponse() {
        if (_state.value.generating) return
        val last = _state.value.messages.lastOrNull() ?: return
        if (last.role != Role.ASSISTANT.wire) return
        generationJob = viewModelScope.launch {
            val conversation = repository.getConversation(conversationId) ?: return@launch
            repository.deleteMessage(last.id)
            runGeneration(conversation)
        }
    }

    /**
     * Replaces the last user turn with [newText] and re-generates. Drops that
     * turn and whatever came after it (typically a single assistant reply, by
     * construction of a normal back-and-forth conversation).
     */
    fun editLastUserMessage(newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty() || _state.value.generating) return
        val messages = _state.value.messages
        val lastUserIndex = messages.indexOfLast { it.role == Role.USER.wire }
        if (lastUserIndex == -1) return
        val toDelete = messages.subList(lastUserIndex, messages.size)
        generationJob = viewModelScope.launch {
            val conversation = repository.getConversation(conversationId) ?: return@launch
            toDelete.forEach { repository.deleteMessage(it.id) }
            repository.appendUserMessage(conversation, trimmed)
            _state.update { it.copy(conversation = repository.getConversation(conversationId)) }
            runGeneration(conversation)
        }
    }

    /** The message a header "more actions" menu can currently act on, if any. */
    fun lastUserMessage(): MessageEntity? =
        _state.value.messages.lastOrNull { it.role == Role.USER.wire }

    fun canRegenerate(): Boolean =
        !_state.value.generating && _state.value.messages.lastOrNull()?.role == Role.ASSISTANT.wire

    private suspend fun runGeneration(conversation: ConversationEntity) {
        _state.update { it.copy(generating = true, error = null, streamingText = "") }
        var partial = ""
        var settled = false

        try {
            val history = repository.listMessages(conversation.id)
            val webSearch = _state.value.webSearchEnabled
            repository.streamReply(conversation, history, webSearch).coalesceForEink().collect { update ->
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
