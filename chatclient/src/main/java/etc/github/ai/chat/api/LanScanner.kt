package etc.github.ai.chat.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * LAN discovery for the ET server (default port 8080).
 *
 * No location/permission needed: enumerate this device's own site-local IPv4
 * addresses via java.net.NetworkInterface, take each /24 (192.168.x.* /
 * 10.x.* / 172.16-31.x.*), and concurrently GET http://<ip>:8080/healthz.
 * 200 (ready) and 503 (loading) both count as "a server is there".
 */
object LanScanner {

    data class Found(val base: String, val ready: Boolean, val model: String)

    /** site-local /24 prefixes (e.g. "192.168.3") from this phone's NICs. */
    fun localPrefixes(): List<String> {
        val prefixes = LinkedHashSet<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.inetAddresses.toList().forEach { addr ->
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val parts = addr.hostAddress!!.split(".")
                        if (parts.size == 4) prefixes.add("${parts[0]}.${parts[1]}.${parts[2]}")
                    }
                }
            }
        }
        return prefixes.toList()
    }

    /**
     * Scan every host in the discovered /24 networks.
     * @param onProgress called on the main thread as (done, total).
     * @param isCancelled polled between probes; abort early when true.
     */
    suspend fun scan(
        onProgress: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean,
    ): List<Found> = withContext(Dispatchers.IO) {
        val targets = ArrayList<String>()
        for (p in localPrefixes()) {
            for (i in 1..254) targets.add("$p.$i")
        }
        if (targets.isEmpty()) {
            onProgress(0, 0)
            return@withContext emptyList<Found>()
        }
        val found = ArrayList<Found>()
        val gate = Semaphore(32)
        var done = 0
        coroutineScope {
            val jobs = targets.map { ip ->
                async {
                    if (isCancelled()) return@async
                    gate.acquire()
                    try {
                        val h = ServerApi.probeRaw("http://$ip:8080")
                        when (h) {
                            is ServerApi.Health.Ready ->
                                synchronized(found) { found.add(Found("http://$ip:8080", true, h.model)) }
                            ServerApi.Health.Starting ->
                                synchronized(found) { found.add(Found("http://$ip:8080", false, "")) }
                            else -> {}
                        }
                    } catch (_: Exception) {
                    } finally {
                        gate.release()
                        synchronized(this@withContext) { done++ }
                        onProgress(done, targets.size)
                    }
                }
            }
            // start a watcher that cancels everything when requested
            val watcher = launch {
                while (!isCancelled()) {
                    kotlinx.coroutines.delay(150)
                }
            }
            jobs.awaitAll()
            watcher.cancel()
        }
        found.sortedBy { it.base }
    }
}
