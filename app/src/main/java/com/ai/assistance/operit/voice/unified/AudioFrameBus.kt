package com.ai.assistance.operit.voice.unified

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out point for the single production PCM stream.
 *
 * The capture owner publishes each frame exactly once. Consumers must return quickly:
 * heavyweight work (ASR, embeddings, diagnostics persistence) belongs on their own worker.
 */
class AudioFrameBus {
    fun interface Consumer {
        fun onFrame(frame: FloatArray)
    }

    private val consumers = CopyOnWriteArrayList<Consumer>()

    fun subscribe(consumer: Consumer): AutoCloseable {
        consumers += consumer
        return AutoCloseable { consumers -= consumer }
    }

    fun publish(frame: FloatArray) {
        for (consumer in consumers) {
            consumer.onFrame(frame)
        }
    }

    val subscriberCount: Int
        get() = consumers.size
}
