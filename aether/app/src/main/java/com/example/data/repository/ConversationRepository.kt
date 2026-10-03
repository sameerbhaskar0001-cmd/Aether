package com.example.data.repository

import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.MessageDao
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.Sender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ConversationRepository {
    fun getAllConversations(): Flow<List<Conversation>>
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>>
    suspend fun getConversation(id: String): Conversation?
    suspend fun createConversation(conversation: Conversation)
    suspend fun saveMessage(conversationId: String, message: Message, isIncognito: Boolean)
    suspend fun deleteConversation(conversationId: String)
    suspend fun updateConversationTitle(conversationId: String, title: String)
}

class ConversationRepositoryImpl(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : ConversationRepository {

    override fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversationsFlow().map { entities ->
            entities.map { entity ->
                Conversation(
                    id = entity.id,
                    title = entity.title,
                    lastUpdated = entity.lastUpdated,
                    isIncognito = entity.isIncognito
                )
            }
        }
    }

    override fun getMessagesForConversation(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesForConversationFlow(conversationId).map { entities ->
            entities.map { entity ->
                Message(
                    id = entity.id,
                    content = entity.content,
                    sender = Sender.valueOf(entity.sender),
                    timestamp = entity.timestamp,
                    status = MessageStatus.valueOf(entity.status)
                )
            }
        }
    }

    override suspend fun getConversation(id: String): Conversation? {
        val entity = conversationDao.getConversationById(id) ?: return null
        return Conversation(
            id = entity.id,
            title = entity.title,
            lastUpdated = entity.lastUpdated,
            isIncognito = entity.isIncognito
        )
    }

    override suspend fun createConversation(conversation: Conversation) {
        if (!conversation.isIncognito) {
            conversationDao.insertConversation(
                ConversationEntity(
                    id = conversation.id,
                    title = conversation.title,
                    lastUpdated = conversation.lastUpdated,
                    isIncognito = false
                )
            )
        }
    }

    override suspend fun saveMessage(conversationId: String, message: Message, isIncognito: Boolean) {
        if (!isIncognito) {
            // First ensure conversation exists or update its last updated timestamp
            val existing = conversationDao.getConversationById(conversationId)
            if (existing == null) {
                // Determine a nice default title
                val title = if (message.sender == Sender.USER) {
                    val preview = message.content.take(30)
                    if (message.content.length > 30) "$preview..." else preview
                } else {
                    "New Conversation"
                }
                conversationDao.insertConversation(
                    ConversationEntity(
                        id = conversationId,
                        title = title,
                        lastUpdated = message.timestamp,
                        isIncognito = false
                    )
                )
            } else {
                conversationDao.updateLastUpdated(conversationId, message.timestamp)
                // If title is default, update it with the first user message content
                if (existing.title == "New Conversation" && message.sender == Sender.USER) {
                    val preview = message.content.take(30)
                    val newTitle = if (message.content.length > 30) "$preview..." else preview
                    conversationDao.updateTitle(conversationId, newTitle)
                }
            }

            // Save the actual message
            messageDao.insertMessage(
                MessageEntity(
                    id = message.id,
                    conversationId = conversationId,
                    content = message.content,
                    sender = message.sender.name,
                    timestamp = message.timestamp,
                    status = message.status.name
                )
            )
        }
    }

    override suspend fun deleteConversation(conversationId: String) {
        messageDao.deleteMessagesForConversation(conversationId)
        conversationDao.deleteConversationById(conversationId)
    }

    override suspend fun updateConversationTitle(conversationId: String, title: String) {
        conversationDao.updateTitle(conversationId, title)
    }
}
