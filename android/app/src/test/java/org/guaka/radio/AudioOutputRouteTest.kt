package org.guaka.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOutputRouteTest {
    @Test fun bluetoothRouteUsesTheSystemDeviceName() {
        assertEquals(AudioOutputRoute("Living Room Headphones", true),
            formatAudioOutputRoute(true, " Living Room Headphones "))
    }

    @Test fun unnamedBluetoothRouteHasUsefulFallback() {
        assertEquals(AudioOutputRoute("Bluetooth audio", true), formatAudioOutputRoute(true, "  "))
    }

    @Test fun wiredAndPhoneRoutesUsePhoneOutputFallback() {
        assertEquals(AudioOutputRoute("This phone", false), formatAudioOutputRoute(false, "Wired headphones"))
        assertEquals(AudioOutputRoute("This phone", false), formatAudioOutputRoute(false, "Phone speaker"))
    }
}
