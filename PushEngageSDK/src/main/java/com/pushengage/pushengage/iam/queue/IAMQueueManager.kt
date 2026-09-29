package com.pushengage.pushengage.iam.queue

import com.pushengage.pushengage.iam.model.IAMMessage
import com.pushengage.pushengage.helper.PELogger
import java.util.Comparator
import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Interface for receiving notifications about new messages
 */
internal interface IAMMessageListener {
    /**
     * Called when a new message is ready to be displayed
     * @param message The message to display, or null if no message is available
     */
    fun onMessageAvailable(message: IAMMessage?)
}

/**
 * Manager class for handling the queue of in-app messages
 * Implements priority-based ordering and manages display timing
 */
internal class IAMQueueManager {
    // Message comparator for priority queue (sorts by priority - lower values first)
    private val messageComparator =
        Comparator<IAMMessage> { msg1, msg2 -> msg1.priority.compareTo(msg2.priority) }
    
    // Use a PriorityQueue with an explicit comparator for pre-API 24 compatibility
    private val messageQueue = PriorityQueue<IAMMessage>(11, messageComparator)
    
    // Lock to ensure thread safety for queue operations
    private val queueLock = ReentrantLock()
    
    // Queue state
    private var isPaused = false
    
    // Current message being displayed
    private var currentMessage: IAMMessage? = null
    
    // List of message listeners
    private val messageListeners = mutableListOf<IAMMessageListener>()
    
    /**
     * Registers a listener for message notifications
     * @param listener The listener to register
     */
    fun addMessageListener(listener: IAMMessageListener) {
        queueLock.withLock {
            if (!messageListeners.contains(listener)) {
                messageListeners.add(listener)
                PELogger.debug("Added message listener, total listeners: ${messageListeners.size}")
                
                // Immediately notify of current message if there is one
                if (currentMessage != null) {
                    listener.onMessageAvailable(currentMessage)
                }
            }
        }
    }
    
    /**
     * Unregisters a listener
     * @param listener The listener to unregister
     */
    fun removeMessageListener(listener: IAMMessageListener) {
        queueLock.withLock {
            messageListeners.remove(listener)
            PELogger.debug("Removed message listener, remaining listeners: ${messageListeners.size}")
        }
    }
    
    /**
     * Notifies all listeners about a message
     * @param message The message to notify about, or null if no message is available
     */
    private fun notifyListeners(message: IAMMessage?) {
        // Make a copy of the listeners to avoid concurrent modification
        val listeners = queueLock.withLock { 
            PELogger.debug("Notifying ${messageListeners.size} listeners about message: ${message?.id ?: "null"}")
            messageListeners.toList() 
        }
        
        // Notify each listener outside the lock
        for (listener in listeners) {
            try {
                PELogger.debug("Notifying listener ${listener.javaClass.simpleName} about message: ${message?.id ?: "null"}")
                listener.onMessageAvailable(message)
            } catch (e: Exception) {
                PELogger.error("Error notifying listener: ${e.message}", e)
            }
        }
    }
    
    /**
     * Enqueues a single message without triggering processing. Caller must hold
     * [queueLock] and is responsible for processing the queue once afterwards.
     * @return True if the message was enqueued, false if a message with the same
     *         id is already queued or currently being displayed.
     */
    private fun enqueue(message: IAMMessage): Boolean {
        // Skip if this id is already pending in the queue or already on screen.
        // Checking currentMessage prevents re-queuing a campaign that is being
        // displayed, which would otherwise show back-to-back duplicates.
        if (currentMessage?.id == message.id) {
            PELogger.debug("Message ${message.id} is currently displayed, skipping")
            return false
        }
        if (messageQueue.any { it.id == message.id }) {
            PELogger.debug("Message ${message.id} already in queue, skipping")
            return false
        }

        messageQueue.add(message)
        PELogger.debug("Added message ${message.id} to queue, queue size now: ${messageQueue.size}")
        return true
    }

    /**
     * Adds a single message to the queue and processes the queue.
     * @return True if the message was added.
     */
    fun addMessage(message: IAMMessage): Boolean {
        queueLock.withLock {
            val added = enqueue(message)
            if (added && !isPaused && currentMessage == null) {
                processQueue()
            }
            return added
        }
    }

    /**
     * Adds multiple messages to the queue.
     *
     * All messages are enqueued first and the queue is processed exactly once
     * afterwards, so the highest-priority (lowest priority number) message across
     * the whole batch is chosen for display — not merely the first one inserted.
     * @param messages The messages to add
     * @return The number of messages actually added
     */
    fun addMessages(messages: List<IAMMessage>): Int {
        var addedCount = 0

        queueLock.withLock {
            for (message in messages) {
                if (enqueue(message)) {
                    addedCount++
                }
            }

            // Process once, after the whole batch is enqueued, so priority
            // ordering holds across the batch rather than favouring insertion order.
            if (addedCount > 0 && !isPaused && currentMessage == null) {
                processQueue()
            }
        }

        return addedCount
    }
    
    /**
     * Processes the next message in the queue
     * @return The next message to display, or null if no messages are available
     */
    private fun processQueue(): IAMMessage? {
        queueLock.withLock {
            // If paused or already displaying a message, do nothing
            if (isPaused) {
                PELogger.debug("Queue is paused, not processing messages")
                return null
            }
            
            if (currentMessage != null) {
                PELogger.debug("Already displaying a message (${currentMessage?.id}), not processing more messages")
                return null
            }
            
            // Get the next message
            val nextMessage = messageQueue.poll()
            if (nextMessage != null) {
                PELogger.debug("Processing next message: ${nextMessage.id}, position: ${nextMessage.position}, notifying ${messageListeners.size} listeners")
                currentMessage = nextMessage
                
                // Notify listeners about the new message
                notifyListeners(nextMessage)
            } else {
                PELogger.debug("No messages in queue to process")
            }
            
            return nextMessage
        }
    }
    
    /**
     * Pauses the queue processing
     * New messages can still be added, but won't be processed until resumed
     */
    fun pause() {
        queueLock.withLock {
            PELogger.debug("Queue paused")
            isPaused = true
        }
    }
    
    /**
     * Resumes queue processing
     * Will immediately process the next message if none is currently being displayed
     */
    fun resume() {
        queueLock.withLock {
            PELogger.debug("Queue resumed")
            isPaused = false
            
            // If no message is being displayed, process the queue
            if (currentMessage == null) {
                processQueue()
            }
        }
    }
    
    /**
     * Marks the current message as complete, allowing the next message to be processed
     */
    fun markCurrentMessageComplete() {
        queueLock.withLock {
            val hadCurrentMessage = currentMessage != null
            PELogger.debug("Marking current message complete: ${currentMessage?.id}")
            currentMessage = null

            // Only broadcast the "no message" event when an actual message was
            // dismissed — completing with nothing current must stay silent.
            if (hadCurrentMessage) {
                notifyListeners(null)
            }

            // Process the next message if not paused
            if (!isPaused) {
                processQueue()
            }
        }
    }
    
    /**
     * Clears all pending messages from the queue
     * Does not affect the currently displayed message
     */
    fun clearQueue() {
        queueLock.withLock {
            PELogger.debug("Clearing queue, ${messageQueue.size} messages removed")
            messageQueue.clear()
        }
    }
    
    /**
     * Gets the current queue size
     * @return The number of messages in the queue
     */
    fun getQueueSize(): Int {
        queueLock.withLock {
            return messageQueue.size
        }
    }
    
    /**
     * Removes a specific message from the queue if it exists
     * @param messageId The ID of the message to remove
     * @return True if the message was removed, false if not found
     */
    fun removeMessage(messageId: String): Boolean {
        queueLock.withLock {
            val message = messageQueue.find { it.id == messageId }
            return if (message != null) {
                messageQueue.remove(message)
                PELogger.debug("Removed message $messageId from queue")
                true
            } else {
                PELogger.debug("Message $messageId not found in queue")
                false
            }
        }
    }
    
    /**
     * Gets the current message being displayed, if any
     * @return The current message or null if none is being displayed
     */
    fun getCurrentMessage(): IAMMessage? {
        queueLock.withLock {
            return currentMessage
        }
    }
} 