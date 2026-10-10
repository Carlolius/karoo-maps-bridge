package com.carlolius.karoomapsbridge.karoo

import android.content.Intent
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import java.util.UUID

/**
 * Registers the "Fetch point from phone" bonus action (in-ride menu / assignable buttons).
 */
class BridgeExtension : KarooExtension(EXTENSION_ID, "1.0.0") {

    private lateinit var karooSystem: KarooSystemService

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(this)
        karooSystem.connect()
    }

    override fun onDestroy() {
        karooSystem.disconnect()
        super.onDestroy()
    }

    override fun onBonusAction(actionId: String) {
        if (actionId != ACTION_FETCH) return
        Thread {
            when (val result = PointClient.fetch(this)) {
                is PointClient.Result.Found ->
                    if (result.points.size == 1) {
                        karooSystem.dispatch(PointClient.pinDrop(result.points.first()))
                    } else {
                        // Several points: let the rider choose on the app screen
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_FETCH, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                    }
                PointClient.Result.NoPoint -> alert(getString(R.string.no_point))
                is PointClient.Result.Unreachable -> alert(getString(R.string.phone_unreachable), result.tried.joinToString(", "))
            }
        }.start()
    }

    private fun alert(title: String, detail: String? = null) {
        karooSystem.dispatch(
            InRideAlert(
                id = UUID.randomUUID().toString(),
                icon = R.drawable.ic_pin,
                title = title,
                detail = detail,
                autoDismissMs = 4000,
                backgroundColor = R.color.brand,
                textColor = R.color.white,
            ),
        )
    }

    companion object {
        const val EXTENSION_ID = "maps-bridge"
        const val ACTION_FETCH = "fetch-point"
    }
}
