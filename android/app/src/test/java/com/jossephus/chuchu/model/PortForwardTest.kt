package com.jossephus.chuchu.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortForwardTest {
    @Test
    fun localForwardListensOnThePhonePortAndTargetsTheRemotePort() {
        val forward = PortForward(type = PortForwardType.Local, localPort = 8080, remotePort = 3000)

        assertEquals(8080, forward.listenPort)
        assertEquals(3000, forward.targetPort)
        assertEquals("L 8080 → localhost:3000", forward.label)
    }

    @Test
    fun remoteForwardListensOnTheServerPortAndTargetsTheLocalPort() {
        val forward =
            PortForward(type = PortForwardType.Remote, localPort = 8000, destinationHost = "10.0.2.2", remotePort = 9000)

        assertEquals(9000, forward.listenPort)
        assertEquals(8000, forward.targetPort)
        assertEquals("R 9000 → 10.0.2.2:8000", forward.label)
    }

    @Test
    fun validForwardHasNoProblem() {
        assertNull(PortForward(localPort = 8080, remotePort = 80).problem(emptyList()))
    }

    @Test
    fun portsOutsideTheValidRangeAreRejected() {
        assertEquals(
            PortForwardProblem.InvalidLocalPort,
            PortForward(localPort = 0, remotePort = 80).problem(emptyList()),
        )
        assertEquals(
            PortForwardProblem.InvalidRemotePort,
            PortForward(localPort = 8080, remotePort = 70000).problem(emptyList()),
        )
    }

    @Test
    fun localForwardCannotListenOnAPrivilegedPhonePort() {
        assertEquals(
            PortForwardProblem.PrivilegedLocalPort,
            PortForward(localPort = 80, remotePort = 80).problem(emptyList()),
        )
    }

    @Test
    fun remoteForwardMayTargetAPrivilegedPhonePort() {
        assertNull(PortForward(type = PortForwardType.Remote, localPort = 80, remotePort = 9000).problem(emptyList()))
    }

    @Test
    fun blankDestinationHostIsRejected() {
        assertEquals(
            PortForwardProblem.MissingHost,
            PortForward(localPort = 8080, destinationHost = " ", remotePort = 80).problem(emptyList()),
        )
    }

    @Test
    fun twoForwardsOfTheSameTypeCannotListenOnTheSamePort() {
        val existing = listOf(PortForward(localPort = 8080, remotePort = 80))

        assertEquals(
            PortForwardProblem.DuplicateListenPort,
            PortForward(localPort = 8080, remotePort = 443).problem(existing),
        )
        assertNull(
            PortForward(type = PortForwardType.Remote, localPort = 3000, remotePort = 8080).problem(existing)
        )
    }
}
