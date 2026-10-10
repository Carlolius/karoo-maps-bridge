package com.carlolius.karoomapsbridge.karoo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.hammerhead.karooext.KarooSystemService

/**
 * App screen on the Karoo: asks the phone as soon as it opens and lists its points
 * (without opening any by itself), a big "fetch" button that opens a single point
 * directly, and the optional manual phone address.
 */
class MainActivity : Activity() {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var status: TextView
    private lateinit var update: TextView
    private lateinit var choices: LinearLayout
    private lateinit var hostInput: EditText
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        karooSystem = KarooSystemService(this)
        karooSystem.connect()

        pad = (12 * resources.displayMetrics.density).toInt()
        update = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, pad)
            visibility = android.view.View.GONE
        }
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, pad)
        }
        val fetch = Button(this).apply {
            text = getString(R.string.action_fetch)
            textSize = 20f
            minHeight = (72 * resources.displayMetrics.density).toInt()
            setOnClickListener { fetchPoints(openSingle = true) }
        }
        choices = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
        }
        val hostLabel = TextView(this).apply {
            text = getString(R.string.manual_host_label)
            setPadding(0, pad * 2, 0, 0)
        }
        hostInput = EditText(this).apply {
            hint = getString(R.string.manual_host_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(PointClient.manualHost(this@MainActivity) ?: "")
        }
        val save = Button(this).apply {
            text = getString(R.string.save)
            setOnClickListener {
                PointClient.setManualHost(this@MainActivity, hostInput.text.toString())
                showPhoneAddress()
            }
        }
        val forget = Button(this).apply {
            text = getString(R.string.forget_known)
            setOnClickListener {
                PointClient.forgetKnownHosts(this@MainActivity)
                showPhoneAddress()
            }
        }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                // Take the initial focus so the IP field (and its keyboard) doesn't
                isFocusableInTouchMode = true
                requestFocus()
                addView(update)
                addView(status)
                addView(fetch)
                addView(choices)
                addView(hostLabel)
                addView(hostInput)
                addView(save)
                addView(forget)
            })
        })
        showPhoneAddress()
        // Opened from the bonus action (several points) or by hand: either way, ask right away.
        fetchPoints(openSingle = false)
        checkForUpdate()
    }

    /** Sideloaded: tell the rider when GitHub has a newer version (at most one check a day). */
    private fun checkForUpdate() {
        Thread {
            val version = UpdateChecker.check(applicationContext) ?: return@Thread
            runOnUiThread {
                update.text = getString(R.string.update_available, version, UpdateChecker.currentVersion(this))
                update.visibility = android.view.View.VISIBLE
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FETCH, false)) fetchPoints(openSingle = false)
    }

    override fun onDestroy() {
        karooSystem.disconnect()
        super.onDestroy()
    }

    private fun showPhoneAddress() {
        val lines = mutableListOf<String>()
        PointClient.manualHost(this)?.let { lines += getString(R.string.phone_manual, it) }
        PointClient.gateway(this)?.let { lines += getString(R.string.phone_auto, it) }
        PointClient.knownHosts(this).takeIf { it.isNotEmpty() }?.let { lines += getString(R.string.phone_known, it.joinToString(", ")) }
        status.text = if (lines.isEmpty()) getString(R.string.no_network) else lines.joinToString("\n")
    }

    /** [openSingle]: with exactly one point, open it instead of listing it. */
    private fun fetchPoints(openSingle: Boolean) {
        status.text = getString(R.string.fetching)
        choices.removeAllViews()
        Thread {
            val result = PointClient.fetch(this)
            runOnUiThread {
                when (result) {
                    is PointClient.Result.Found ->
                        if (openSingle && result.points.size == 1) open(result.points.first())
                        else showChoices(result.points)
                    PointClient.Result.NoPoint -> status.text = getString(R.string.no_point)
                    is PointClient.Result.Unreachable -> status.text =
                        getString(R.string.phone_unreachable) + "\n" + getString(R.string.tried, result.tried.joinToString(", ").ifEmpty { "-" })
                }
            }
        }.start()
    }

    private fun showChoices(points: List<PointClient.Point>) {
        status.text = if (points.size == 1) getString(R.string.one_point) else getString(R.string.choose_point, points.size)
        points.forEach { p ->
            choices.addView(Button(this).apply {
                text = label(p)
                textSize = 17f
                isAllCaps = false
                minHeight = (60 * resources.displayMetrics.density).toInt()
                setOnClickListener { open(p) }
            })
        }
    }

    private fun open(p: PointClient.Point) {
        val sent = karooSystem.dispatch(PointClient.pinDrop(p))
        status.text = if (sent) getString(R.string.point_sent, label(p), p.source) else getString(R.string.karoo_not_connected)
    }

    private fun label(p: PointClient.Point) = p.name ?: PointClient.formatCoords(p.lat, p.lng)

    companion object {
        const val EXTRA_FETCH = "fetch"
    }
}
