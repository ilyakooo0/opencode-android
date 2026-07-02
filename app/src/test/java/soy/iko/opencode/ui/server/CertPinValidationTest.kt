package soy.iko.opencode.ui.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertPinValidationTest {

    @Test
    fun blankIsValid() {
        assertTrue(isValidCertPin(""))
        assertTrue(isValidCertPin("   "))
    }

    @Test
    fun wellFormedSinglePin() {
        assertTrue(isValidCertPin("sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
    }

    @Test
    fun whitespaceOrCommaSeparatedPins() {
        assertTrue(isValidCertPin("sha256/AAAA= sha256/BBBB="))
        assertTrue(isValidCertPin("sha256/AAAA=,sha256/BBBB="))
    }

    @Test
    fun acceptsSha1PinLikeOkHttp() {
        assertTrue(isValidCertPin("sha1/AAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        assertTrue(isValidCertPin("sha256/AAAA= sha1/BBBB="))
    }

    @Test
    fun rejectsMalformed() {
        assertFalse(isValidCertPin("not-a-pin"))
        assertFalse(isValidCertPin("md5/AAAA="))
        assertFalse(isValidCertPin("sha256/"))
        assertFalse(isValidCertPin("sha1/"))
    }
}
