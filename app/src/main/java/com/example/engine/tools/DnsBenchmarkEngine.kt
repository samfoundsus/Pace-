package com.example.engine.tools

import com.example.data.constants.ToolsConstants
import com.example.engine.speedtest.SpeedMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class DnsResolverId {
    SYSTEM,
    CLOUDFLARE,
    GOOGLE,
    QUAD9,
}

data class DnsResolverSpec(
    val id: DnsResolverId,
    val serverIp: String?,
)

enum class DnsResolverStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    UNREACHABLE,
}

data class DnsResolverResult(
    val id: DnsResolverId,
    val serverIp: String?,
    val status: DnsResolverStatus,
    val medianMs: Double? = null,
    val completedQueries: Int = 0,
    val failedQueries: Int = 0,
    val totalQueries: Int = ToolsConstants.DNS_BENCHMARK_DOMAINS.size * ToolsConstants.DNS_BENCHMARK_ROUNDS,
)

sealed interface DnsBenchmarkEvent {
    data class ResolverStarted(
        val resolverId: DnsResolverId,
        val resolverIndex: Int,
        val totalResolvers: Int,
    ) : DnsBenchmarkEvent

    data class ResolverCompleted(
        val result: DnsResolverResult,
    ) : DnsBenchmarkEvent

    data object Finished : DnsBenchmarkEvent
}

/**
 * Benchmarks system DNS (via InetAddress.getByName) and public UDP port-53 DNS resolvers
 * sequentially on Dispatchers.IO. Sends real hand-written A-record UDP queries with a 2-second timeout.
 */
class DnsBenchmarkEngine(
    private val domains: List<String> = ToolsConstants.DNS_BENCHMARK_DOMAINS,
    private val rounds: Int = ToolsConstants.DNS_BENCHMARK_ROUNDS,
    private val timeoutMs: Int = ToolsConstants.DNS_QUERY_TIMEOUT_MS,
) {
    private val transactionCounter = AtomicInteger(0x1000)

    val resolvers: List<DnsResolverSpec> = listOf(
        DnsResolverSpec(id = DnsResolverId.SYSTEM, serverIp = null),
        DnsResolverSpec(id = DnsResolverId.CLOUDFLARE, serverIp = ToolsConstants.DNS_CLOUDFLARE_IP),
        DnsResolverSpec(id = DnsResolverId.GOOGLE, serverIp = ToolsConstants.DNS_GOOGLE_IP),
        DnsResolverSpec(id = DnsResolverId.QUAD9, serverIp = ToolsConstants.DNS_QUAD9_IP),
    )

    fun runBenchmark(): Flow<DnsBenchmarkEvent> = flow {
        val totalResolvers = resolvers.size
        resolvers.forEachIndexed { index, spec ->
            currentCoroutineContext().ensureActive()
            emit(
                DnsBenchmarkEvent.ResolverStarted(
                    resolverId = spec.id,
                    resolverIndex = index + 1,
                    totalResolvers = totalResolvers,
                ),
            )
            val result = benchmarkSingleResolver(spec)
            currentCoroutineContext().ensureActive()
            emit(DnsBenchmarkEvent.ResolverCompleted(result))
        }
        emit(DnsBenchmarkEvent.Finished)
    }.flowOn(Dispatchers.IO)

    private suspend fun benchmarkSingleResolver(spec: DnsResolverSpec): DnsResolverResult =
        withContext(Dispatchers.IO) {
            val totalQueries = domains.size * rounds
            val successfulSamplesMs = ArrayList<Double>(totalQueries)
            var failedQueries = 0

            val serverAddress = if (spec.serverIp != null) {
                runCatching { InetAddress.getByName(spec.serverIp) }.getOrNull()
            } else {
                null
            }

            if (spec.serverIp != null && serverAddress == null) {
                return@withContext DnsResolverResult(
                    id = spec.id,
                    serverIp = spec.serverIp,
                    status = DnsResolverStatus.UNREACHABLE,
                    medianMs = null,
                    completedQueries = 0,
                    failedQueries = totalQueries,
                    totalQueries = totalQueries,
                )
            }

            for (round in 1..rounds) {
                for (domain in domains) {
                    currentCoroutineContext().ensureActive()
                    val rttMs = if (serverAddress != null) {
                        queryUdpDnsMs(serverAddress = serverAddress, domain = domain)
                    } else {
                        querySystemDnsMs(domain = domain)
                    }

                    if (rttMs != null && rttMs.isFinite() && rttMs > 0.0) {
                        successfulSamplesMs.add(rttMs)
                    } else {
                        failedQueries++
                    }
                }
            }

            val medianMs = SpeedMath.calculateMedian(successfulSamplesMs)
            val isReachable = medianMs != null && medianMs.isFinite() && medianMs > 0.0

            DnsResolverResult(
                id = spec.id,
                serverIp = spec.serverIp,
                status = if (isReachable) DnsResolverStatus.COMPLETED else DnsResolverStatus.UNREACHABLE,
                medianMs = if (isReachable) medianMs else null,
                completedQueries = successfulSamplesMs.size,
                failedQueries = failedQueries,
                totalQueries = totalQueries,
            )
        }

    private suspend fun queryUdpDnsMs(
        serverAddress: InetAddress,
        domain: String,
    ): Double? {
        val txId = transactionCounter.incrementAndGet() and 0xFFFF
        val queryBytes = DnsPacketCodec.buildAQueryPacket(domain = domain, transactionId = txId)

        var socket: DatagramSocket? = null
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                runCatching { socket?.close() }
            }
        }

        return try {
            val udpSocket = DatagramSocket().also {
                socket = it
                it.soTimeout = timeoutMs
            }
            val sendPacket = DatagramPacket(
                queryBytes,
                queryBytes.size,
                serverAddress,
                ToolsConstants.DNS_UDP_PORT,
            )
            val receiveBuffer = ByteArray(512)
            val deadlineNanos = System.nanoTime() + (timeoutMs.toLong() * 1_000_000L)
            val startNanos = System.nanoTime()
            udpSocket.send(sendPacket)

            while (currentCoroutineContext().isActive) {
                val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).toInt()
                if (remainingMs <= 0) return null
                udpSocket.soTimeout = remainingMs

                val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                udpSocket.receive(receivePacket)
                val endNanos = System.nanoTime()

                if (DnsPacketCodec.isValidResponse(receiveBuffer, receivePacket.length, txId)) {
                    val elapsedMs = (endNanos - startNanos) / 1_000_000.0
                    return elapsedMs.takeIf { it.isFinite() && it > 0.0 }
                }
            }
            null
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("DNS query cancelled", e)
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            cancelHandle?.dispose()
            runCatching { socket?.close() }
        }
    }

    private suspend fun querySystemDnsMs(domain: String): Double? {
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(
            Callable<Double?> {
                val startNanos = System.nanoTime()
                InetAddress.getByName(domain)
                val endNanos = System.nanoTime()
                ((endNanos - startNanos) / 1_000_000.0).takeIf { it.isFinite() && it > 0.0 }
            },
        )
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                future.cancel(true)
            }
        }
        return try {
            future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (ce: CancellationException) {
            future.cancel(true)
            throw ce
        } catch (_: InterruptedException) {
            future.cancel(true)
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("System DNS query cancelled")
            }
            null
        } catch (_: Exception) {
            future.cancel(true)
            null
        } finally {
            cancelHandle?.dispose()
            executor.shutdownNow()
        }
    }
}
