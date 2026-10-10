package com.carlolius.karoomapsbridge.mobile

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Status screen: list of points waiting for the Karoo, server on/off, and how to use it. */
class MainActivity : Activity() {

    private lateinit var server: TextView
    private lateinit var toggle: Button
    private lateinit var listTitle: TextView
    private lateinit var list: LinearLayout
    private lateinit var clearAll: Button
    private lateinit var unrecognizedBox: LinearLayout
    private lateinit var unrecognizedText: TextView
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (16 * resources.displayMetrics.density).toInt()

        server = TextView(this).apply { textSize = 15f }
        toggle = Button(this).apply {
            setOnClickListener {
                if (PointServerService.running) PointServerService.stop(this@MainActivity)
                else PointServerService.start(this@MainActivity)
                it.postDelayed({ refresh() }, 300)
            }
        }
        listTitle = TextView(this).apply {
            textSize = 18f
            setPadding(0, pad, 0, pad / 2)
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clearAll = Button(this).apply {
            text = getString(R.string.clear_all)
            setOnClickListener {
                PointStore.clear(this@MainActivity)
                afterListChange()
            }
        }
        unrecognizedText = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 13f
        }
        unrecognizedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.unrecognized_title)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(unrecognizedText)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(this@MainActivity).apply {
                    text = getString(R.string.copy)
                    setOnClickListener {
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("shared", PointStore.lastUnrecognized(this@MainActivity) ?: ""))
                    }
                })
                addView(Button(this@MainActivity).apply {
                    text = getString(R.string.dismiss)
                    setOnClickListener {
                        PointStore.setLastUnrecognized(this@MainActivity, null)
                        refresh()
                    }
                })
            })
        }
        val help = TextView(this).apply {
            text = getString(R.string.help)
            setPadding(0, pad, 0, 0)
        }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(server)
                addView(toggle)
                addView(listTitle)
                addView(list)
                addView(clearAll)
                addView(unrecognizedBox)
                addView(help)
            })
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        if (PointServerService.expired()) PointServerService.stop(this) // alarm came late
        refresh()
    }

    private fun afterListChange() {
        PointServerService.refresh(this)
        refresh()
    }

    private fun refresh() {
        server.text = if (PointServerService.running) {
            getString(R.string.notif_server_on, PointServerService.localAddresses().joinToString(", ").ifEmpty { "-" }, PointServerService.PORT) +
                "\n" + getString(R.string.notif_stops_at, PointServerService.formatTime(this, PointServerService.stopAt))
        } else getString(R.string.status_server_off)
        toggle.text = getString(if (PointServerService.running) R.string.action_stop else R.string.action_start)

        val points = PointStore.all(this)
        listTitle.text = if (points.isEmpty()) getString(R.string.notif_no_point) else getString(R.string.status_points, points.size)
        clearAll.visibility = if (points.size > 1) View.VISIBLE else View.GONE
        list.removeAllViews()
        points.forEach { list.addView(row(it), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }

        val unrecognized = PointStore.lastUnrecognized(this)
        unrecognizedBox.visibility = if (unrecognized != null) View.VISIBLE else View.GONE
        unrecognizedText.text = unrecognized ?: ""
    }

    /** Tap the text to check the point on a map, ✕ to remove it. */
    private fun row(p: PendingPoint) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (80 * resources.displayMetrics.density).toInt()
        setPadding(0, pad / 2, 0, pad / 2)
        val texts = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad / 4, pad / 2, pad / 4)
            addView(TextView(this@MainActivity).apply {
                text = p.name ?: PointServerService.formatCoords(p)
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setLineSpacing(0f, 1.15f)
                setPadding(0, 0, 0, pad / 4)
            })
            addView(TextView(this@MainActivity).apply {
                text = "${PointServerService.formatCoords(p)} · ${p.source} · ${DateUtils.getRelativeTimeSpanString(p.timestamp)}"
                textSize = 14f
                setLineSpacing(0f, 1.15f)
            })
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${p.lat},${p.lng}")))
            }
        }
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(this@MainActivity).apply {
            text = "✕"
            minWidth = 0
            minimumWidth = (56 * resources.displayMetrics.density).toInt()
            minHeight = (56 * resources.displayMetrics.density).toInt()
            contentDescription = getString(R.string.remove_point)
            setOnClickListener {
                PointStore.remove(this@MainActivity, p.id)
                afterListChange()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}
