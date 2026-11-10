package com.jossephus.chuchu.data.db

import com.jossephus.chuchu.model.PortForward
import com.jossephus.chuchu.model.PortForwardType
import org.junit.Assert.assertEquals
import org.junit.Test

class PortForwardConverterTest {
    private val converters = Converters()

    @Test
    fun portForwardsRoundTripThroughTheColumn() {
        val forwards =
            listOf(
                PortForward(type = PortForwardType.Local, localPort = 8080, remotePort = 3000),
                PortForward(type = PortForwardType.Remote, localPort = 8000, destinationHost = "10.0.2.2", remotePort = 9000),
            )

        assertEquals(forwards, converters.toPortForwards(converters.fromPortForwards(forwards)))
    }

    @Test
    fun migratedDefaultDecodesToNoForwards() {
        assertEquals(emptyList<PortForward>(), converters.toPortForwards("[]"))
    }

    @Test
    fun unreadableColumnDecodesToNoForwardsInsteadOfCrashing() {
        assertEquals(emptyList<PortForward>(), converters.toPortForwards("not json"))
        assertEquals(emptyList<PortForward>(), converters.toPortForwards(null))
    }
}
