package ke.sokobei.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Guards the cleartext policy: release forbids it everywhere; debug and staging allow it only for two dev hosts. */
class NetworkSecurityConfigTest {
    private val xmlDir = listOf("src/main/res/xml", "android/src/main/res/xml").map(::File).first { it.isDirectory }
    private fun parse(name: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xmlDir, name)).documentElement

    @Test fun `release forbids cleartext for every host`() {
        val root = parse("network_security_config_release.xml")
        val base = root.getElementsByTagName("base-config").item(0)
        assertEquals("false", base.attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
        assertEquals("release config must not whitelist any domain", 0, root.getElementsByTagName("domain-config").length)
        assertFalse(File(xmlDir, "network_security_config_release.xml").readText().contains("cleartextTrafficPermitted=\"true\""))
    }

    @Test fun `dev config (debug and staging) allows cleartext only for localhost and 10_0_2_2`() {
        val root = parse("network_security_config_dev.xml")
        val base = root.getElementsByTagName("base-config").item(0)
        assertEquals("false", base.attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
        val configs = root.getElementsByTagName("domain-config")
        assertEquals(1, configs.length)
        assertEquals("true", configs.item(0).attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
        val domains = root.getElementsByTagName("domain")
        val hosts = (0 until domains.length).map { domains.item(it).textContent.trim() }.toSet()
        assertEquals(setOf("localhost", "10.0.2.2"), hosts)
        (0 until domains.length).forEach { assertEquals("false", domains.item(it).attributes.getNamedItem("includeSubdomains").nodeValue) }
    }

    @Test fun `manifest takes the config and cleartext flag from build type placeholders`() {
        val manifest = listOf("src/main/AndroidManifest.xml", "android/src/main/AndroidManifest.xml").map(::File).first { it.isFile }.readText()
        assertTrue(manifest.contains("android:networkSecurityConfig=\"\${networkSecurityConfig}\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"\${usesCleartext}\""))
    }
}
