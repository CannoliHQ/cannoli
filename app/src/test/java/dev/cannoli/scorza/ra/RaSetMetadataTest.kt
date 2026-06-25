package dev.cannoli.scorza.ra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaSetMetadataTest {
    @Test fun parse_sumsAcrossSets() {
        val body = """
            {"Success":true,"Title":"Super Metroid","Sets":[
              {"Achievements":[{"Points":5},{"Points":10}]},
              {"Achievements":[{"Points":1}]}
            ]}
        """.trimIndent()
        val p = RaSetMetadata.parse(body)!!
        assertEquals("Super Metroid", p.title)
        assertEquals(3, p.count)
        assertEquals(16, p.points)
    }

    @Test fun parse_emptySets_zeroCount() {
        val p = RaSetMetadata.parse("""{"Success":true,"Title":"X","Sets":[]}""")!!
        assertEquals(0, p.count)
        assertEquals(0, p.points)
    }

    @Test fun parse_notSuccess_null() {
        assertNull(RaSetMetadata.parse("""{"Success":false}"""))
    }

    @Test fun parse_malformed_null() {
        assertNull(RaSetMetadata.parse("not json"))
    }
}
