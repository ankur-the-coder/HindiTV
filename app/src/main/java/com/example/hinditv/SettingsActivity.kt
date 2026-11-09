package com.example.hinditv

import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SettingsActivity : AppCompatActivity() {

    private class Item(val title: String, val value: () -> String, val action: () -> Unit)

    private val qualityNames = arrayOf("Auto (best available)", "Highest (force)", "Max 1080p", "Max 720p")
    private val hdrNames = arrayOf("Auto (leave TV alone)", "Ask for HDR", "Force SDR (safe colours)")

    private lateinit var items: List<Item>
    private lateinit var adapter: SettingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        items = buildItems()
        adapter = SettingsAdapter()
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        list.adapter = adapter
    }

    private fun refresh() {
        adapter.notifyItemRangeChanged(0, items.size)
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun choose(title: String, options: Array<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, current) { d, which ->
                onPick(which)
                d.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggle(title: String, getter: () -> Boolean, setter: (Boolean) -> Unit): Item =
        Item(title, { onOff(getter()) }) {
            setter(!getter())
            refresh()
        }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onYes(); refresh() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun buildItems(): List<Item> {
        val list = ArrayList<Item>()

        list.add(Item("Playlist / country", { Repository.current().label }) {
            PlaylistPicker.show(this) { src ->
                Settings.source = src.key
                if (src.key == "custom" && Settings.customUrl.isBlank()) askCustomUrl()
                refresh()
            }
        })

        list.add(Item("Custom playlist URL", { Settings.customUrl.ifBlank { "Not set" } }) { askCustomUrl() })

        list.add(Item("Buffering", { arrayOf("Low", "Medium", "High")[Settings.buffering.coerceIn(0, 2)] }) {
            choose(
                "Buffering (low = fastest start, high = fewer stalls)",
                arrayOf("Low", "Medium", "High"), Settings.buffering
            ) { Settings.buffering = it }
        })

        list.add(toggle("Volume leveling", { Settings.leveling }, { Settings.leveling = it }))

        list.add(Item("Aspect ratio", { arrayOf("Fit", "Zoom", "Stretch")[Settings.aspect.coerceIn(0, 2)] }) {
            choose("Aspect ratio", arrayOf("Fit", "Zoom", "Stretch"), Settings.aspect) { Settings.aspect = it }
        })

        list.add(Item("Video quality", { qualityNames[Settings.quality.coerceIn(0, 3)] }) {
            choose(
                "Video quality (Auto = best the stream and TV allow)",
                qualityNames, Settings.quality
            ) { Settings.quality = it }
        })

        list.add(Item("HDR output", { hdrNames[Settings.hdrMode.coerceIn(0, 2)] }) {
            choose(
                "HDR output (if colours or brightness look wrong, try 'Force SDR')",
                hdrNames, Settings.hdrMode
            ) { Settings.hdrMode = it }
        })

        list.add(toggle("Match TV refresh rate (may blink)", { Settings.matchFps }, { Settings.matchFps = it }))

        list.add(toggle("Skip unavailable channels while zapping", { Settings.skipDead }, { Settings.skipDead = it }))
        list.add(toggle("Open last channel on start", { Settings.resumeLast }, { Settings.resumeLast = it }))
        list.add(toggle("Update playlist on launch", { Settings.autoUpdate }, { Settings.autoUpdate = it }))

        list.add(Item("Sort channels", { if (Settings.sort == 1) "A - Z" else "Playlist order" }) {
            choose("Sort channels", arrayOf("Playlist order", "A - Z"), Settings.sort) { Settings.sort = it }
        })

        list.add(toggle("Show channel logos", { Settings.logos }, { Settings.logos = it }))

        list.add(Item("Info bar timeout", { Settings.infoSeconds.toString() + " seconds" }) {
            val opts = intArrayOf(3, 5, 8, 12)
            choose(
                "Info bar timeout",
                opts.map { "$it seconds" }.toTypedArray(),
                opts.indexOf(Settings.infoSeconds).coerceAtLeast(0)
            ) { Settings.infoSeconds = opts[it] }
        })

        list.add(toggle("Technical info (resolution, decoder)", { Settings.techInfo }, { Settings.techInfo = it }))
        list.add(toggle("Relaxed SSL (fixes old TVs)", { Settings.relaxedSsl }, { Settings.relaxedSsl = it }))

        list.add(Item("Remote keys", { "Link keys to actions" }) { manageKeys() })

        list.add(Item("Channel numbers", { UserData.numberEntries().size.toString() + " assigned" }) { manageNumbers() })

        list.add(Item("Auto-number favorites", { "1, 2, 3 ..." }) {
            confirm("Auto-number favorites", "Give every favorite without a number the next free number (1-99)?") {
                val n = UserData.autoNumberFavorites()
                Toast.makeText(this, "$n favorites numbered", Toast.LENGTH_SHORT).show()
            }
        })

        list.add(Item("Clear all numbers", { "" }) {
            confirm("Clear all numbers", "Remove every channel number?") { UserData.clearNumbers() }
        })

        list.add(Item("Clear recently watched", { "" }) {
            confirm("Clear recently watched", "Empty the Recent list?") { UserData.clearRecent() }
        })

        list.add(Item("Clear favorites", { "" }) {
            confirm("Clear favorites", "Remove all favorites?") { UserData.clearFavs() }
        })

        list.add(Item("Remote guide", { "Hindi TV 0.3" }) { showGuide() })

        return list
    }

    private fun askCustomUrl() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this)
        input.setText(Settings.customUrl)
        input.hint = "https://example.com/list.m3u"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.setSingleLine(true)
        val box = FrameLayout(this)
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Custom playlist URL (.m3u)")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val u = input.text.toString().trim()
                Settings.customUrl = u
                if (u.isNotEmpty()) Settings.source = "custom"
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun manageNumbers() {
        val entries = UserData.numberEntries()
        if (entries.isEmpty()) {
            Toast.makeText(this, "No numbers assigned yet. Hold OK on a channel to assign one.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = entries.map { it.first.toString().padEnd(4) + it.second.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Channel numbers (select one to change or remove)")
            .setItems(labels) { _, which ->
                val e = entries[which]
                AlertDialog.Builder(this)
                    .setTitle(e.first.toString() + "  " + e.second.name)
                    .setItems(arrayOf("Change number", "Remove number " + e.first)) { _, choice ->
                        if (choice == 0) {
                            Dialogs.assignNumber(this, e.second) { refresh(); manageNumbers() }
                        } else {
                            UserData.removeNumber(e.first)
                            refresh()
                            manageNumbers()
                        }
                    }
                    .setNegativeButton("Back", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------- remote keys

    private fun manageKeys() {
        val acts = Act.values()
        val labels = ArrayList<String>()
        for (a in acts) labels.add(a.label + "   -   " + KeyMap.summary(a))
        labels.add("Reset all keys to default")
        AlertDialog.Builder(this)
            .setTitle("Remote keys (select an action, then press a key)")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == acts.size) {
                    confirm("Reset keys", "Restore the default remote key layout?") { KeyMap.reset() }
                } else {
                    learnKey(acts[which])
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun learnKey(a: Act) {
        AlertDialog.Builder(this)
            .setTitle(a.label)
            .setMessage(
                "Now press the remote key you want to use.\n\n" +
                    "Currently: " + KeyMap.summary(a) + "\n\n" +
                    "Arrow keys, OK, Back, Home, number keys and volume keys cannot be re-linked."
            )
            .setNeutralButton("Clear keys") { _, _ ->
                KeyMap.clear(a)
                refresh()
                manageKeys()
            }
            .setNegativeButton("Cancel") { _, _ -> manageKeys() }
            .setOnKeyListener { d, keyCode, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0 && !KeyMap.isReserved(keyCode)) {
                    KeyMap.set(keyCode, a)
                    d.dismiss()
                    Toast.makeText(this, KeyMap.label(keyCode) + " key  ->  " + a.label, Toast.LENGTH_LONG).show()
                    refresh()
                    manageKeys()
                    true
                } else {
                    false
                }
            }
            .show()
    }

    private fun showGuide() {
        fun k(a: Act) = KeyMap.summary(a)
        val text = "CHANNEL LIST\n" +
            "OK - play     Hold OK - menu (favorite, number)\n" +
            "0-9 - jump to a numbered channel\n" +
            "Hold 1-9 on a channel - give it that number\n" +
            "Search: " + k(Act.SEARCH) + "     Playlists: " + k(Act.PLAYLISTS) + "\n" +
            "HD filter: " + k(Act.HD_FILTER) + "     Favorite: " + k(Act.FAVORITE) + "\n\n" +
            "WHILE WATCHING\n" +
            "UP / DOWN or P+ / P- - next / previous channel\n" +
            "LEFT or OK - channel list     RIGHT - options\n" +
            "0-9 - jump to number (2 digits: press both, or press OK)\n" +
            "Hold 1-9 - give the current channel that number\n" +
            "Hold OK - favorite     BACK - close list / exit\n" +
            "Search: " + k(Act.SEARCH) + "     Previous channel: " + k(Act.PREV_CHANNEL) + "\n" +
            "Stream info: " + k(Act.STREAM_INFO) + "     Quality: " + k(Act.QUALITY) + "\n" +
            "Audio: " + k(Act.AUDIO) + "     Subtitles: " + k(Act.SUBTITLES) + "\n" +
            "Jump +10 / -10: " + k(Act.SKIP_FWD) + " / " + k(Act.SKIP_BACK) + "\n" +
            "Volume keys - system volume"
        AlertDialog.Builder(this)
            .setTitle("Remote guide")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    private inner class SettingsAdapter : RecyclerView.Adapter<SettingsAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.sTitle)
            val value: TextView = v.findViewById(R.id.sValue)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false)
            val h = VH(v)
            v.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) items[p].action()
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val it = items[position]
            h.title.text = it.title
            h.value.text = it.value()
        }

        override fun getItemCount() = items.size
    }
}
