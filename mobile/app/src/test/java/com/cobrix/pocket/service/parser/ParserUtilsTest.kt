package com.cobrix.pocket.service.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParserUtilsTest {

    @Test
    fun testExtractReferenceIgnoresPhones() {
        // Colombia phone numbers shouldn't be picked as reference
        assertNull(ParserUtils.extractReference("Pago recibido de 3001234567"))
        assertNull(ParserUtils.extractReference("Pago recibido de 573109876543"))

        // Argentina phone numbers shouldn't be picked as reference
        assertNull(ParserUtils.extractReference("Pago de 1122512265"))
        assertNull(ParserUtils.extractReference("Pago de 1522512265"))
        assertNull(ParserUtils.extractReference("Transferencia de 5491122512265"))

        // Venezuela phone numbers shouldn't be picked as reference
        assertNull(ParserUtils.extractReference("Pago Movil 04141234567"))
        assertNull(ParserUtils.extractReference("Pago Movil 04241234567"))

        // Legitimate bank references should be extracted
        assertEquals("987654", ParserUtils.extractReference("Ref: 987654"))
        assertEquals("12345678", ParserUtils.extractReference("Operacion: 12345678"))
        assertEquals("8943", ParserUtils.extractReference("Comprobante: 8943"))
    }
}

