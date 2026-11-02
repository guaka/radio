package org.guaka.radio
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.junit.Test
import org.junit.Assert.*
@RunWith(AndroidJUnit4::class)
class NostrCryptoDeviceTest {
    private val secret = "00".repeat(31) + "01"
    @Test fun nip44RoundTripsAndUsesFreshNonce() {
        val peer = NostrCrypto.publicKey(secret)
        val one = NostrCrypto.encrypt("favorite: radio:guaka", secret, peer)
        val two = NostrCrypto.encrypt("favorite: radio:guaka", secret, peer)

        assertEquals("favorite: radio:guaka", NostrCrypto.decrypt(one, secret, peer))
        assertNotEquals(one, two)
    }

}
