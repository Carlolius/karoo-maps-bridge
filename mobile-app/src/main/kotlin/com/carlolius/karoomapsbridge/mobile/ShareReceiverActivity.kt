package com.carlolius.karoomapsbridge.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible target of "Share" (Google Maps and other map apps) and of geo: links.
 * Resolves the location(s), adds them to the list for the Karoo and makes sure the server is up.
 * For directions it asks first: send the stops, or compute a route ([RoutesActivity]).
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (shared.isNullOrBlank()) {
            toast(getString(R.string.error_no_location))
            finish()
            return
        }

        toast(getString(R.string.resolving))

        Thread {
            val result = PointResolver(applicationContext).resolve(shared)
            runOnUiThread {
                when (result) {
                    is PointResolver.Result.Ok ->
                        if (result.isRoute) askWhatToDo(result) else {
                            sendPoints(result)
                            finish()
                        }
                    is PointResolver.Result.Failed -> {
                        PointStore.setLastUnrecognized(this, shared)
                        toast(result.reason + "\n" + getString(R.string.see_app_for_details))
                        finish()
                    }
                }
            }
        }.start()
    }

    private fun askWhatToDo(result: PointResolver.Result.Ok) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(R.string.directions_title)
            .setItems(arrayOf(getString(R.string.send_stops, result.points.size), getString(R.string.compute_route))) { _, which ->
                if (which == 0) sendPoints(result) else startActivity(RoutesActivity.intent(this, result.origin, result.points))
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun sendPoints(result: PointResolver.Result.Ok) {
        PointServerService.start(this)
        PointStore.addAll(this, result.points)
        PointStore.setLastUnrecognized(this, null)
        PointServerService.refresh(this)
        toast(message(result))
    }

    private fun message(result: PointResolver.Result.Ok): String {
        if (result.isRoute) {
            return if (result.notFound.isEmpty()) getString(R.string.route_ready, result.points.size)
            else getString(R.string.route_ready_partial, result.points.size, result.notFound.joinToString(", "))
        }
        val point = result.points.first()
        val label = point.name ?: PointServerService.formatCoords(point)
        return getString(if (point.source == "approximate") R.string.point_ready_approx else R.string.point_ready, label)
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
}
