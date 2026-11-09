package com.example.hinditv

import android.view.KeyEvent

/** Returns 0-9 for a number key (main row or numpad), otherwise -1. */
fun digitOf(keyCode: Int): Int = when (keyCode) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
    else -> -1
}
