package com.sharefiles.app.core

import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Measures the network path with data held in memory, so storage speed plays no part. Several
 * streams run at once, the way large downloads do, because one TCP stream does not fill fast Wi-Fi.
 */
class SpeedTest(private val api: ShareApi, private val streams: Int = STREAMS) {

    /** Returns bytes per second. [onSpeed] gets live readings. */
    suspend fun download(onSpeed: (Double) -> Unit): Double {
        val clock = Clock(onSpeed)
        coroutineScope {
            repeat(streams) {
                launch {
                    api.stream("/v1/speedtest/download?bytes=$MAX_BYTES") { response ->
                        val input = response.body.byteStream()
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (!clock.finished) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            clock.add(read)
                        }
                    }
                }
            }
        }
        return clock.average()
    }

    suspend fun upload(onSpeed: (Double) -> Unit): Double {
        val clock = Clock(onSpeed)
        coroutineScope {
            repeat(streams) {
                launch {
                    api.postRaw("/v1/speedtest/upload", object : RequestBody() {
                        override fun contentType(): MediaType = BinaryType
                        override fun contentLength(): Long = -1

                        override fun writeTo(sink: BufferedSink) {
                            val block = Random.nextBytes(BUFFER_SIZE)
                            while (!clock.finished) {
                                sink.write(block)
                                sink.flush()
                                clock.add(block.size)
                            }
                        }
                    })
                }
            }
        }
        return clock.average()
    }

    /** Shared by all streams. Skips the first second of TCP ramp-up when averaging. */
    private class Clock(private val onSpeed: (Double) -> Unit) {
        private val started = System.nanoTime()
        private val meter = SpeedMeter()
        private var total = 0L
        private var measuredBytes = 0L
        private var measuredFrom = -1.0
        private var lastReport = 0.0

        private fun elapsed() = (System.nanoTime() - started) / 1e9

        val finished: Boolean get() = elapsed() >= DURATION_SECONDS

        fun add(bytes: Int) {
            val report = synchronized(this) {
                total += bytes
                val now = elapsed()
                if (now >= RAMP_SECONDS) {
                    if (measuredFrom < 0) measuredFrom = now else measuredBytes += bytes
                }
                if (now - lastReport < REPORT_SECONDS) return
                lastReport = now
                meter.sample(total, now)
            }
            onSpeed(report)
        }

        @Synchronized
        fun average(): Double {
            val seconds = elapsed() - measuredFrom
            return if (measuredFrom < 0 || seconds <= 0) 0.0 else measuredBytes / seconds
        }
    }

    private companion object {
        const val STREAMS = DOWNLOAD_PARTS
        const val MAX_BYTES = 4L * 1024 * 1024 * 1024
        const val DURATION_SECONDS = 7.0
        const val RAMP_SECONDS = 1.0
        const val REPORT_SECONDS = 0.2
    }
}
