package org.guaka.radio

import android.content.Context
import android.media.MediaRouter
import java.io.Closeable

internal data class AudioOutputRoute(val displayName: String, val isBluetooth: Boolean)

/** Observes the selected system media-audio route without requesting Bluetooth permissions. */
internal class AudioOutputRouteObserver(
    context: Context,
    private val onRouteChanged: (AudioOutputRoute) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val router = appContext.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
    private var started = false

    private val callback = object : MediaRouter.SimpleCallback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteGrouped(router: MediaRouter, route: MediaRouter.RouteInfo, group: MediaRouter.RouteGroup, index: Int) = publish()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteSelected(router: MediaRouter, type: Int, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteUngrouped(router: MediaRouter, route: MediaRouter.RouteInfo, group: MediaRouter.RouteGroup) = publish()
        override fun onRouteUnselected(router: MediaRouter, type: Int, route: MediaRouter.RouteInfo) = publish()
    }

    fun start() {
        if (started) return
        started = true
        router?.addCallback(
            MediaRouter.ROUTE_TYPE_LIVE_AUDIO,
            callback,
            MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS,
        )
        publish()
    }

    private fun publish() {
        val route = router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        val isBluetooth = route?.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH
        onRouteChanged(formatAudioOutputRoute(isBluetooth, route?.getName(appContext)?.toString().orEmpty()))
    }

    override fun close() {
        if (!started) return
        started = false
        router?.removeCallback(callback)
    }
}

internal fun formatAudioOutputRoute(isBluetooth: Boolean, routeName: String): AudioOutputRoute =
    if (!isBluetooth) AudioOutputRoute("This phone", isBluetooth = false)
    else AudioOutputRoute(routeName.trim().ifEmpty { "Bluetooth audio" }, isBluetooth = true)
