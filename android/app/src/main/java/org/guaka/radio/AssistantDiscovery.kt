package org.guaka.radio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import java.util.ArrayDeque
import kotlin.coroutines.resume

internal data class DiscoveredAssistant(val name: String, val url: String, val serverId: String)

private const val ASSISTANT_SERVICE_TYPE = "_mass._tcp."
private const val DISCOVERY_TIMEOUT_MS = 8_000L
private const val MAX_DISCOVERED_SERVICES = 32

/** Discovers local Music Assistant broadcasts. Results are candidates only; callers must authenticate explicitly. */
internal suspend fun discoverLocalAssistants(context: Context): List<DiscoveredAssistant> =
    withContext(Dispatchers.Main.immediate) {
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return@withContext emptyList()

        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val pending = ArrayDeque<NsdServiceInfo>()
            val queuedNames = mutableSetOf<String>()
            val activeNames = mutableSetOf<String>()
            val resolvedByName = linkedMapOf<String, DiscoveredAssistant>()
            var discoveryStarted = false
            var isResolving = false
            var finished = false

            lateinit var discoveryListener: NsdManager.DiscoveryListener

            fun cleanup() {
                handler.removeCallbacksAndMessages(null)
                if (discoveryStarted) {
                    discoveryStarted = false
                    runCatching { manager.stopServiceDiscovery(discoveryListener) }
                }
            }

            fun finish() {
                if (finished) return
                finished = true
                cleanup()
                if (continuation.isActive) continuation.resume(resolvedByName.values.distinctBy { it.serverId })
            }

            lateinit var resolveNext: () -> Unit
            resolveNext = {
                if (!finished && !isResolving && pending.isNotEmpty()) {
                    val candidate = pending.removeFirst()
                    isResolving = true
                    runCatching { manager.resolveService(candidate, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            handler.post {
                                if (!finished) {
                                    isResolving = false
                                    resolveNext()
                                }
                            }
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            handler.post {
                                if (!finished) {
                                    isResolving = false
                                    val resolvedName = serviceInfo.serviceName.orEmpty()
                                    parseDiscoveredAssistant(
                                        resolvedName,
                                        serviceInfo.serviceType.orEmpty(),
                                        serviceInfo.host,
                                        serviceInfo.port,
                                        serviceInfo.attributes,
                                    )?.takeIf { resolvedName in activeNames }?.let { assistant ->
                                        val serviceName = serviceInfo.serviceName.orEmpty()
                                        if (resolvedByName.size < MAX_DISCOVERED_SERVICES || serviceName in resolvedByName) {
                                            resolvedByName[serviceName] = assistant
                                        }
                                    }
                                    resolveNext()
                                }
                            }
                        }
                    }) }.onFailure {
                        isResolving = false
                        resolveNext()
                    }
                }
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    handler.post {
                        if (finished) runCatching { manager.stopServiceDiscovery(this) }
                        else discoveryStarted = true
                    }
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    handler.post {
                        if (finished || !isMusicAssistantServiceType(serviceInfo.serviceType.orEmpty())) return@post
                        val name = serviceInfo.serviceName.orEmpty()
                        if (name.isBlank() || name in activeNames ||
                            (name !in queuedNames && queuedNames.size >= MAX_DISCOVERED_SERVICES)
                        ) return@post
                        queuedNames.add(name)
                        activeNames.add(name)
                        pending.addLast(serviceInfo)
                        resolveNext()
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    handler.post {
                        if (!finished) {
                            val name = serviceInfo.serviceName.orEmpty()
                            activeNames.remove(name)
                            pending.removeAll { it.serviceName == name }
                            resolvedByName.remove(name)
                        }
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    handler.post { discoveryStarted = false }
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    handler.post { finish() }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    handler.post { discoveryStarted = false }
                }
            }

            continuation.invokeOnCancellation {
                handler.post {
                    if (!finished) {
                        finished = true
                        cleanup()
                    }
                }
            }
            handler.postDelayed({ finish() }, DISCOVERY_TIMEOUT_MS)
            runCatching { manager.discoverServices(ASSISTANT_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
                .onFailure { finish() }
        }
    }

internal fun parseDiscoveredAssistant(
    name: String,
    serviceType: String,
    host: InetAddress?,
    port: Int,
    attributes: Map<String, ByteArray>?,
): DiscoveredAssistant? {
    if (!isMusicAssistantServiceType(serviceType) || host == null || port !in 1..65535) return null
    val serverId = attributes?.get("server_id")?.toString(Charsets.UTF_8)?.trim().orEmpty()
    if (serverId.isBlank()) return null
    val rawHost = host.hostAddress ?: return null
    val urlHost = if (':' in rawHost) "[${rawHost.replace("%", "%25")}]" else rawHost
    val url = runCatching { localAssistantUrl("http://$urlHost:$port") }.getOrNull() ?: return null
    val label = attributes?.get("name")?.toString(Charsets.UTF_8)?.take(120)
        ?.filterNot(Char::isISOControl)?.takeIf(String::isNotBlank) ?: name.ifBlank { "Music Assistant" }
    return DiscoveredAssistant(label, url, serverId)
}

private fun isMusicAssistantServiceType(serviceType: String): Boolean =
    serviceType.equals("_mass._tcp.", ignoreCase = true) ||
        serviceType.equals("_mass._tcp.local.", ignoreCase = true)
