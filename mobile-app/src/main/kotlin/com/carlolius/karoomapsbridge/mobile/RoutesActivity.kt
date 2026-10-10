package com.carlolius.karoomapsbridge.mobile

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.function.Consumer

/**
 * Bike route alternatives (BRouter) through the stops of a shared Google Maps directions link.
 * All of them are drawn on a map (Leaflet + OpenStreetMap, assets/routes_map.html), one colour
 * each; the list below uses the same colours. Each one can be sent to the Karoo (via the
 * Hammerhead Companion app, which uploads shared GPX files), viewed in a map app or shared.
 */
class RoutesActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var stops: List<MapsLinkParser.LatLng>
    private lateinit var destination: String
    private var origin: MapsLinkParser.LatLng? = null
    private val shown = mutableListOf<BRouter.Route>()
    private val rows = mutableListOf<View>()
    private val onMap = mutableListOf<CheckBox>()
    private lateinit var map: WebView
    private var mapReady = false
    private val pendingJs = mutableListOf<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (16 * resources.displayMetrics.density).toInt()

        val lats = intent.getDoubleArrayExtra(EXTRA_LATS) ?: doubleArrayOf()
        val lngs = intent.getDoubleArrayExtra(EXTRA_LNGS) ?: doubleArrayOf()
        stops = lats.indices.map { MapsLinkParser.LatLng(lats[it], lngs[it]) }
        destination = intent.getStringExtra(EXTRA_DESTINATION) ?: getString(R.string.route_default_name)
        if (intent.hasExtra(EXTRA_ORIGIN_LAT)) {
            origin = MapsLinkParser.LatLng(intent.getDoubleExtra(EXTRA_ORIGIN_LAT, 0.0), intent.getDoubleExtra(EXTRA_ORIGIN_LNG, 0.0))
        }

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, pad)
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = getString(R.string.routes_title, destination)
        map = createMap()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(map, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.45).toInt()))
            addView(ScrollView(this@RoutesActivity).apply {
                addView(LinearLayout(this@RoutesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, pad / 2, pad, pad)
                    addView(status)
                    addView(list)
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        when {
            stops.isEmpty() -> status.text = getString(R.string.error_no_location)
            origin != null -> compute()
            hasLocationPermission() -> locateThenCompute()
            else -> {
                status.text = getString(R.string.need_location)
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 0)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createMap() = WebView(this).apply {
        settings.javaScriptEnabled = true
        // OpenStreetMap's tile policy asks apps to identify themselves
        settings.userAgentString = settings.userAgentString + " karoo-maps-bridge/1.0"
        addJavascriptInterface(MapBridge(), "Bridge")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                mapReady = true
                pendingJs.forEach { view.evaluateJavascript(it, null) }
                pendingJs.clear()
            }
        }
        loadUrl("file:///android_asset/routes_map.html")
    }

    private fun js(code: String) {
        if (mapReady) map.evaluateJavascript(code, null) else pendingJs += code
    }

    /** Called from the map when a line is tapped. */
    private inner class MapBridge {
        @JavascriptInterface
        fun select(id: Int) = runOnUiThread { highlight(id, fromMap = true) }
    }

    private fun highlight(id: Int, fromMap: Boolean) {
        onMap.getOrNull(id)?.takeIf { !it.isChecked }?.isChecked = true // selecting a hidden route shows it
        rows.forEachIndexed { i, row -> row.setBackgroundColor(if (i == id) Color.argb(40, 0, 0, 0) else Color.TRANSPARENT) }
        if (fromMap) rows.getOrNull(id)?.let { row -> (list.parent.parent as? ScrollView)?.smoothScrollTo(0, list.top + row.top) }
        else js("select($id)")
    }

    override fun onDestroy() {
        worker.shutdownNow()
        map.destroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasLocationPermission()) locateThenCompute() else status.text = getString(R.string.no_location_permission)
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Origin was "your location": use where the phone is now. */
    @Suppress("MissingPermission")
    private fun locateThenCompute() {
        status.text = getString(R.string.locating)
        val lm = getSystemService(LocationManager::class.java)
        val recent = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }
            .maxByOrNull { it.time }
        if (recent != null) return useLocation(recent)

        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { lm.isProviderEnabled(it) }
            ?: return run { status.text = getString(R.string.no_location) }
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, null, mainExecutor, Consumer { loc ->
                if (loc != null) useLocation(loc) else status.text = getString(R.string.no_location)
            })
        } else {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, { useLocation(it) }, mainLooper)
        }
    }

    private fun useLocation(location: Location) {
        origin = MapsLinkParser.LatLng(location.latitude, location.longitude)
        compute()
    }

    private fun compute() {
        val points = listOf(origin!!) + stops
        stops.dropLast(1).forEach { js(String.format(Locale.ROOT, "addStop(%.6f,%.6f)", it.lat, it.lng)) }
        val total = BRouter.Profile.entries.size * BRouter.MAX_ALTERNATIVES
        status.text = getString(R.string.computing, 0, total)
        val server = AppSettings.brouterServer(this)
        worker.execute {
            var done = 0
            val accepted = mutableListOf<BRouter.Route>() // worker-side copy for the duplicate check
            for (profile in BRouter.Profile.entries) {
                for (alt in 0 until BRouter.MAX_ALTERNATIVES) {
                    val route = runCatching { BRouter.fetch(points, profile, alt, server) }
                        .onFailure { Log.w(TAG, "BRouter ${profile.id}/$alt failed", it) }.getOrNull()
                        ?.takeIf { r -> accepted.none { BRouter.nearDuplicate(it, r) } }
                    route?.let { accepted += it }
                    done++
                    runOnUiThread {
                        if (route != null) {
                            val id = shown.size
                            shown += route
                            val row = row(route, id)
                            rows += row
                            list.addView(row)
                            val coords = BRouter.thin(route.points).joinToString(",", "[", "]") {
                                String.format(Locale.ROOT, "[%.6f,%.6f]", it.lat, it.lng)
                            }
                            js("addRoute($id,'${color(id)}',$coords)")
                        }
                        status.text = if (done < total) getString(R.string.computing, done, total)
                        else if (shown.isEmpty()) getString(R.string.no_routes)
                        else getString(R.string.routes_ready, shown.size)
                    }
                    Thread.sleep(300) // be gentle with the free public server
                }
            }
        }
    }

    private fun profileName(p: BRouter.Profile) = getString(
        when (p) {
            BRouter.Profile.NORMAL -> R.string.profile_normal
            BRouter.Profile.FAST -> R.string.profile_fast
            BRouter.Profile.QUIET -> R.string.profile_quiet
        },
    )

    private fun label(r: BRouter.Route) = getString(R.string.route_option, profileName(r.profile), r.alternative + 1)

    private fun color(id: Int) = COLORS[id % COLORS.size]

    private fun row(r: BRouter.Route, id: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad / 2, pad / 2, 0, pad / 2)
        setOnClickListener { highlight(id, fromMap = false) }
        addView(LinearLayout(this@RoutesActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(View(this@RoutesActivity).apply { setBackgroundColor(Color.parseColor(color(id))) },
                LinearLayout.LayoutParams(pad * 2, pad).apply { setMargins(0, pad / 4, pad / 2, 0) })
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(this@RoutesActivity).apply {
                text = label(r)
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(CheckBox(this@RoutesActivity).apply {
                text = getString(R.string.on_map)
                isChecked = true
                setOnCheckedChangeListener { _, checked -> js("setVisible($id,$checked)") }
                onMap += this
            })
        })
        val km = String.format(Locale.getDefault(), "%.1f", r.info.lengthM / 1000.0)
        val minutes = (r.info.timeS + 30) / 60
        val time = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        addView(TextView(this@RoutesActivity).apply {
            text = getString(R.string.route_summary, km, r.info.ascendM, time)
            textSize = 15f
        })
        surfaceText(r.surface)?.let { surface ->
            addView(TextView(this@RoutesActivity).apply {
                text = surface
                textSize = 14f
            })
        }
        addView(LinearLayout(this@RoutesActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@RoutesActivity).apply {
                text = getString(R.string.send_to_karoo)
                setOnClickListener { sendToKaroo(r) }
            })

            addView(Button(this@RoutesActivity).apply {
                text = getString(R.string.view)
                setOnClickListener { view(r) }
            })
            addView(Button(this@RoutesActivity).apply {
                text = getString(R.string.share_gpx)
                setOnClickListener { share(r) }
            })
        })
    }

    /** "Asphalt 85 % · Cobbles 10 % · Unpaved 5 % · Cycle lane 47 %" (zero parts left out). */
    private fun surfaceText(s: BRouter.Surface): String? {
        if (s.totalM == 0) return null
        fun pct(m: Int) = Math.round(100.0 * m / s.totalM).toInt()
        val parts = listOf(
            R.string.surface_asphalt to s.asphaltM,
            R.string.surface_cobbles to s.cobblesM,
            R.string.surface_unpaved to s.unpavedM,
            R.string.surface_unknown to s.unknownM,
            R.string.surface_cycleway to s.cyclewayM,
        ).filter { pct(it.second) > 0 }.map { getString(it.first, pct(it.second)) }
        return parts.joinToString(" · ").ifEmpty { null }
    }

    private fun gpxFile(r: BRouter.Route): Uri {
        val dir = File(cacheDir, "routes").apply { mkdirs() }
        val safe = destination.replace(Regex("""[^\p{L}\p{N}]+"""), "-").trim('-').take(40).ifEmpty { "route" }
        val file = File(dir, "$safe-${r.profile.id}-${r.alternative + 1}.gpx")
        file.writeText(BRouter.toGpx(r, "$destination (${label(r)})"))
        return FileProvider.getUriForFile(this, "$packageName.files", file)
    }

    private fun view(r: BRouter.Route) {
        val uri = gpxFile(r)
        for (type in listOf(GPX_MIME, "application/octet-stream")) {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.view)))
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No app to view $type")
            }
        }
    }

    /** The Hammerhead Companion app uploads a shared GPX as a route to the Karoo. */
    private fun sendToKaroo(r: BRouter.Route) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(GPX_MIME)
            .setPackage(HAMMERHEAD_APP)
            .putExtra(Intent.EXTRA_STREAM, gpxFile(r))
            .putExtra(Intent.EXTRA_SUBJECT, "$destination (${label(r)})")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Hammerhead Companion not installed, falling back to the share menu")
            share(r)
        }
    }

    private fun share(r: BRouter.Route) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(GPX_MIME)
            .putExtra(Intent.EXTRA_STREAM, gpxFile(r))
            .putExtra(Intent.EXTRA_SUBJECT, "$destination (${label(r)})")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share_gpx)))
    }

    companion object {
        private const val TAG = "Routes"
        private const val GPX_MIME = "application/gpx+xml"
        private const val HAMMERHEAD_APP = "io.hammerhead.companionapp"
        private val COLORS = listOf(
            "#1565C0", "#E65100", "#2E7D32", "#6A1B9A", "#C62828", "#00838F",
            "#AD1457", "#827717", "#4E342E", "#283593", "#00695C", "#F9A825",
        )
        private const val EXTRA_LATS = "lats"
        private const val EXTRA_LNGS = "lngs"
        private const val EXTRA_DESTINATION = "destination"
        private const val EXTRA_ORIGIN_LAT = "originLat"
        private const val EXTRA_ORIGIN_LNG = "originLng"

        fun intent(context: Context, origin: MapsLinkParser.LatLng?, stops: List<PendingPoint>): Intent =
            Intent(context, RoutesActivity::class.java).apply {
                putExtra(EXTRA_LATS, stops.map { it.lat }.toDoubleArray())
                putExtra(EXTRA_LNGS, stops.map { it.lng }.toDoubleArray())
                // "2/2 · Name" -> "Name"
                stops.lastOrNull()?.name?.substringAfter(" · ")?.let { putExtra(EXTRA_DESTINATION, it) }
                origin?.let {
                    putExtra(EXTRA_ORIGIN_LAT, it.lat)
                    putExtra(EXTRA_ORIGIN_LNG, it.lng)
                }
            }
    }
}
