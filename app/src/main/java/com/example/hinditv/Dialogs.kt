package com.example.hinditv

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object Dialogs {

    /** Dialog to give a channel a number from 1 to 99. Works with the remote's number keys. */
    fun assignNumber(ctx: Context, ch: Channel, onDone: () -> Unit) {
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val input = EditText(ctx)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(2))
        input.hint = "1 - 99"
        input.textSize = 28f
        input.gravity = Gravity.CENTER
        input.setSingleLine(true)

        val box = FrameLayout(ctx)
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input)

        val cur = UserData.numberOf(ch.url)
        val msg = if (cur > 0) "Currently number $cur. Type a new number (1-99) with the remote, then choose Assign."
        else "Type a number (1-99) with the remote, then choose Assign."

        val dlg = AlertDialog.Builder(ctx)
            .setTitle("Number for " + ch.name)
            .setMessage(msg)
            .setView(box)
            .setPositiveButton("Assign") { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n == null || n < 1 || n > 99) {
                    Toast.makeText(ctx, "Please enter a number from 1 to 99", Toast.LENGTH_SHORT).show()
                } else {
                    doAssign(ctx, ch, n, onDone)
                }
            }
            .setNeutralButton("Next free") { _, _ ->
                val n = UserData.nextFree()
                if (n == 0) {
                    Toast.makeText(ctx, "All 99 numbers are already used", Toast.LENGTH_SHORT).show()
                } else {
                    doAssign(ctx, ch, n, onDone)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dlg.show()
    }

    private fun doAssign(ctx: Context, ch: Channel, n: Int, onDone: () -> Unit) {
        val existing = UserData.channelFor(n)
        if (existing != null && existing.url != ch.url) {
            AlertDialog.Builder(ctx)
                .setTitle("Number $n is already used")
                .setMessage(existing.name + " will lose number " + n + ". Replace it?")
                .setPositiveButton("Replace") { _, _ -> finishAssign(ctx, ch, n, onDone) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            finishAssign(ctx, ch, n, onDone)
        }
    }

    private fun finishAssign(ctx: Context, ch: Channel, n: Int, onDone: () -> Unit) {
        UserData.assign(n, ch)
        Toast.makeText(ctx, ch.name + " is now number " + n, Toast.LENGTH_LONG).show()
        onDone()
    }
}
