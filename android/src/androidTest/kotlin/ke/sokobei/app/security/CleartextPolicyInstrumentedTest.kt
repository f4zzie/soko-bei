package ke.sokobei.app.security

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import ke.sokobei.app.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Proves on a real device runtime that the debug build's network security config is the dev one. */
@RunWith(AndroidJUnit4::class)
class CleartextPolicyInstrumentedTest {
    @Test fun debug_build_permits_cleartext_only_for_dev_hosts() {
        assertTrue("these tests target the debug build", BuildConfig.DEBUG)
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted("localhost"))
        assertTrue(policy.isCleartextTrafficPermitted("10.0.2.2"))
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
        assertFalse(policy.isCleartextTrafficPermitted("10.0.2.3"))
        assertFalse(policy.isCleartextTrafficPermitted("sub.localhost"))
    }
}
