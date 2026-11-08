package com.example.hinditv

import android.os.Handler

/**
 * Handles typing channel numbers on the remote.
 * A single digit plays immediately, unless some two-digit number starts with that digit
 * (e.g. 1 and 12 both assigned) - then it waits briefly for a second digit. OK confirms at once.
 */
class NumberEntry(
    private val handler: Handler,
    private val assigned: () -> Set<Int>,
    private val onPending: (String) -> Unit,
    private val onResolve: (Int) -> Unit
) {
    private var buf = ""
    private val timeout = Runnable { commit() }

    val pending: Boolean get() = buf.isNotEmpty()

    fun digit(d: Int) {
        handler.removeCallbacks(timeout)
        if (buf.isEmpty() && d == 0) return
        buf += d.toString()
        if (buf.length >= 2) {
            commit()
            return
        }
        val first = d
        val hasLonger = assigned().any { it in 10..99 && it / 10 == first }
        if (hasLonger) {
            onPending(buf + "_")
            handler.postDelayed(timeout, 1200)
        } else {
            commit()
        }
    }

    fun commitNow() {
        if (buf.isNotEmpty()) commit()
    }

    fun cancel() {
        handler.removeCallbacks(timeout)
        buf = ""
    }

    private fun commit() {
        handler.removeCallbacks(timeout)
        val v = buf.toIntOrNull()
        buf = ""
        if (v != null) onResolve(v)
    }
}
