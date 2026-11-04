package com.sharefiles.app.core

import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** Measures the network path with data held in memory, so storage speed plays no part. */
class SpeedTest(private val api: ShareApi) {

    /** Returns bytes per second. [onSpeed] gets live readings. */
    suspend fun download(onSpeed: (Double) -> Unit): Double =
        api.stream("/v1/speedtest/download?bytes=$MAX_BYTES") { response ->
            val input = response.body.byteStream()
            val buffer = ByteArray(BUFFER_SIZE)
            val clock = Clock(onSpeed)
            while (!clock.finished) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                clock.add(read)
            }
            clock.average()
        }

    suspend fun upload(onSpeed: (Double) -> Unit): Double {
        val clock = Clock(onSpeed)
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
        return clock.average()
    }

    /** Skips the first second of TCP ramp-up when averaging. */
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
            total += bytes
            val now = elapsed()
            if (now >= RAMP_SECONDS) {
                if (measuredFrom < 0) measuredFrom = now else measuredBytes += bytes
            }
            if (now - lastReport >= REPORT_SECONDS) {
                lastReport = now
                onSpeed(meter.sample(total, now))
            }
        }

        fun average(): Double {
            val seconds = elapsed() - measuredFrom
            return if (measuredFrom < 0 || seconds <= 0) 0.0 else measuredBytes / seconds
        }
    }

    private companion object {
        const val MAX_BYTES = 4L * 1024 * 1024 * 1024
        const val DURATION_SECONDS = 7.0
        const val RAMP_SECONDS = 1.0
        const val REPORT_SECONDS = 0.2
    }
}
