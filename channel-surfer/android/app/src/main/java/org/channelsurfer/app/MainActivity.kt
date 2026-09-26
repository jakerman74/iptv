package org.channelsurfer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("channels", MODE_PRIVATE) }
    private lateinit var player: ExoPlayer
    private lateinit var video: PlayerView
    private lateinit var source: EditText
    private lateinit var search: EditText
    private lateinit var category: Spinner
    private lateinit var status: TextView
    private lateinit var currentName: TextView
    private lateinit var favorite: Button
    private lateinit var favoritesOnly: CheckBox
    private lateinit var list: ListView
    private var rows = JSONArray()
    private var current: JSONObject? = null
    private var generation = 0
    private var request = 0
    private var timeout: Runnable? = null
    private var loading = false
    private var ready = false
    private var autoSkips = 0
    private var updatingCategories = false
    private val dark = Color.rgb(17, 21, 29)
    private val light = Color.rgb(238, 241, 247)
    private val accent = Color.rgb(115, 199, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = dark
        window.navigationBarColor = dark
        NativeEngine.restoreFavorites(prefs.getString("favorites", "[]") ?: "[]")
        NativeEngine.restoreHidden(prefs.getString("hidden", "[]") ?: "[]")
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    ready = true
                    autoSkips = 0
                    clearTimeout()
                    status.text = "Playing"
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                skipFailed("Stream unavailable")
            }
        })
        buildScreen()
        loadSource()
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + .5f).toInt()
    private fun text(label: String, size: Float = 16f): TextView =
        TextView(this).apply { text = label; textSize = size; setTextColor(light); setPadding(dp(10), dp(8), dp(10), dp(8)) }
    private fun button(label: String, click: () -> Unit): Button =
        Button(this).apply { text = label; isAllCaps = false; setOnClickListener { click() } }
    private fun horizontal(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weight(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(dark)
        }
        setContentView(root)
        root.addView(text("CHANNEL SURFER", 20f).apply { setTextColor(accent) })
        root.addView(button("Network privacy") {
            AlertDialog.Builder(this)
                .setTitle("Pi-hole + Cloudflare DNS")
                .setMessage("To hide streaming destinations from your ISP, turn on full WARP mode in Cloudflare's Android app before watching. DNS-only mode is insufficient. Cloudflare will handle your traffic. For home DNS blocking, use Pi-hole through your router with cloudflared to Cloudflare DNS over HTTPS. Consumer WARP generally uses its own DNS, so Pi-hole may be bypassed while WARP is on. This player cannot enable or verify a device VPN.")
                .setPositiveButton("OK", null).show()
        })
        val sourceRow = horizontal()
        source = EditText(this).apply {
            setSingleLine(true)
            setTextColor(light); setHintTextColor(Color.GRAY)
            hint = "M3U playlist URL"
            setText(prefs.getString("source", DEFAULT_URL))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        sourceRow.addView(source, weight())
        sourceRow.addView(button("Load") { loadSource() })
        root.addView(sourceRow)
        video = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = true
            setBackgroundColor(Color.BLACK)
        }
        root.addView(video, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)))
        currentName = text("Choose a channel", 19f)
        root.addView(currentName)
        val controls = horizontal()
        controls.addView(button("◀ Prev") { navigate(-1) }, weight())
        controls.addView(button("Surprise") { selectJson(NativeEngine.random(current?.optInt("id") ?: -1, query(), group(), favoritesOnly.isChecked)) }, weight())
        controls.addView(button("Next ▶") { navigate(1) }, weight())
        root.addView(controls)
        val curate = horizontal()
        curate.addView(button("Hide channel") {
            current?.let {
                NativeEngine.hide(it.optInt("id"))
                prefs.edit().putString("hidden", NativeEngine.hidden()).apply()
                current = null
                refresh()
                navigate(1)
            }
        }, weight())
        curate.addView(button("Share lineup") {
            val m3u = NativeEngine.exportVisible(query(), group(), favoritesOnly.isChecked)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Channel Surfer lineup.m3u")
                putExtra(Intent.EXTRA_TEXT, m3u)
            }, "Export current lineup"))
        }, weight())
        root.addView(curate)
        root.addView(button("Restore hidden channels") {
            NativeEngine.restoreHidden("[]")
            prefs.edit().putString("hidden", "[]").apply()
            refresh()
        })
        val filter = horizontal()
        search = EditText(this).apply {
            hint = "Search channels"
            setSingleLine(true)
            setTextColor(light); setHintTextColor(Color.GRAY)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        filter.addView(search, weight())
        favorite = button("☆ Save") { current?.let {
            NativeEngine.toggleFavorite(it.optInt("id"))
            prefs.edit().putString("favorites", NativeEngine.favorites()).apply()
            refresh()
            updateFavorite()
        } }
        filter.addView(favorite)
        root.addView(filter)
        val options = horizontal()
        category = Spinner(this)
        category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("All"))
        category.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingCategories) refresh()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        options.addView(category, weight())
        favoritesOnly = CheckBox(this).apply {
            text = "Favorites"
            setTextColor(light)
            setOnCheckedChangeListener { _, _ -> refresh() }
        }
        options.addView(favoritesOnly)
        root.addView(options)
        status = text("Loading playlist…", 13f).apply { setTextColor(accent) }
        root.addView(status)
        list = ListView(this).apply {
            dividerHeight = dp(1)
            setOnItemClickListener { _, _, position, _ -> play(rows.optJSONObject(position)) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun query() = search.text.toString().trim()
    private fun group() = category.selectedItem?.toString() ?: "All"
    private fun refresh() {
        if (!::list.isInitialized) return
        rows = JSONArray(NativeEngine.visible(query(), group(), favoritesOnly.isChecked))
        val names = (0 until rows.length()).map { i ->
            rows.getJSONObject(i).let { (if (it.optBoolean("favorite")) "★ " else "") + it.optString("name") + "  ·  " + it.optString("group") }
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        status.text = "${rows.length()} channels in this view"
    }
    private fun updateFavorite() {
        val id = current?.optInt("id") ?: return
        val refreshed = JSONArray(NativeEngine.visible("", "All", false))
        val match = (0 until refreshed.length()).map { refreshed.getJSONObject(it) }.firstOrNull { it.optInt("id") == id }
        if (match != null) {
            current = match
            favorite.text = if (match.optBoolean("favorite")) "★ Saved" else "☆ Save"
        }
    }
    private fun selectJson(raw: String) {
        if (raw != "null" && raw.isNotBlank()) play(JSONObject(raw))
        else status.text = "No channels match these filters"
    }
    private fun navigate(direction: Int) {
        selectJson(NativeEngine.navigate(current?.optInt("id") ?: -1, direction, query(), group(), favoritesOnly.isChecked))
    }
    private fun play(channel: JSONObject?) {
        if (channel == null) return
        current = channel
        generation++
        ready = false
        clearTimeout()
        currentName.text = channel.optString("name")
        favorite.text = if (channel.optBoolean("favorite")) "★ Saved" else "☆ Save"
        status.text = "Connecting…"
        player.setMediaItem(MediaItem.fromUri(channel.optString("url")))
        player.prepare()
        player.play()
        val attempt = generation
        timeout = Runnable {
            if (attempt == generation && !ready) skipFailed("Stream timed out")
        }.also { main.postDelayed(it, 15_000) }
    }
    private fun clearTimeout() { timeout?.let(main::removeCallbacks); timeout = null }
    private fun skipFailed(reason: String) {
        if (loading) return
        clearTimeout()
        if (autoSkips++ >= 5) {
            status.text = "$reason · paused after 6 unavailable channels"
            player.stop()
            return
        }
        status.text = "$reason · trying next channel"
        navigate(1)
    }
    private fun loadSource() {
        val address = source.text.toString().trim()
        if (!address.startsWith("https://")) {
            status.text = "Enter an HTTPS playlist URL"
            return
        }
        val ticket = ++request
        loading = true
        status.text = "Downloading playlist…"
        Thread {
            try {
                val connection = URL(address).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = true
                val content = try {
                    if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                    connection.inputStream.bufferedReader().use { it.readTextLimited(25_000_000) }
                } finally { connection.disconnect() }
                main.post {
                    if (ticket != request) return@post
                    loading = false
                    val count = NativeEngine.load(content)
                    if (count == 0) { status.text = "No playable HTTPS entries found"; return@post }
                    clearTimeout()
                    player.stop()
                    current = null
                    currentName.text = "Choose a channel"
                    prefs.edit().putString("source", address).apply()
                    updatingCategories = true
                    val groups = JSONArray(NativeEngine.categories())
                    category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                        (0 until groups.length()).map { groups.getString(it) })
                    category.setSelection(0)
                    updatingCategories = false
                    refresh()
                    status.text = "Loaded $count channels · tap one or press Surprise"
                }
            } catch (error: Exception) {
                main.post {
                    if (ticket == request) {
                        loading = false
                        status.text = "Playlist failed: ${error.message ?: "network error"}"
                    }
                }
            }
        }.start()
    }
    private fun java.io.BufferedReader.readTextLimited(limit: Int): String {
        val out = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            if (out.length + n > limit) error("Playlist exceeds 25 MB")
            out.append(buffer, 0, n)
        }
        return out.toString()
    }
    override fun onStop() { super.onStop(); player.pause() }
    override fun onDestroy() {
        request++
        clearTimeout()
        video.player = null
        player.release()
        super.onDestroy()
    }
    companion object { private const val DEFAULT_URL = "https://raw.githubusercontent.com/jakerman74/iptv/master/channel-surfer/lineup.m3u" }
}
