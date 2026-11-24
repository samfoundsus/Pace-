package com.example.engine.speedtest

import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.Executors

/**
 * Offloads OkHttp call cancellation and connection pool eviction onto a dedicated background
 * daemon thread. Calling [OkHttpClient.connectionPool.evictAll] or [Call.cancel] synchronously
 * from the Android main thread can trigger SSLSocket.close() (TLS close_notify) on the main
 * thread and throw [android.os.NetworkOnMainThreadException].
 */
object OkHttpCleanup {

    private val cleanupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pace-okhttp-cleanup").apply {
            isDaemon = true
        }
    }

    fun cancelCallAsync(call: Call) {
        runCatching {
            cleanupExecutor.execute {
                runCatching {
                    call.cancel()
                }
            }
        }
    }

    fun cancelClientAsync(client: OkHttpClient) {
        runCatching {
            cleanupExecutor.execute {
                runCatching {
                    client.dispatcher.cancelAll()
                }
            }
        }
    }

    fun closeClientAsync(client: OkHttpClient, evictPool: Boolean = true) {
        runCatching {
            cleanupExecutor.execute {
                runCatching {
                    client.dispatcher.cancelAll()
                }
                if (evictPool) {
                    runCatching {
                        client.connectionPool.evictAll()
                    }
                }
            }
        }
    }
}
