package com.example.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class GenericProgressTest {
    @Test
    fun outOfOrderSourceCallbacksCannotMoveProgressBackwards() {
        val initial = GenericTestState(running = true, total = 5, message = "FETCHING")
        val laterCallback = initial.withSourceProgress(3)
        val delayedCallback = laterCallback.withSourceProgress(1)

        assertEquals(3, delayedCallback.completed)
        assertEquals(initial.running, delayedCallback.running)
        assertEquals(initial.message, delayedCallback.message)
    }

    @Test
    fun sourceProgressStaysWithinDeclaredTotal() {
        val initial = GenericTestState(total = 5)
        assertEquals(0, initial.withSourceProgress(-1).completed)
        assertEquals(5, initial.withSourceProgress(6).completed)
    }
}
