package dk.adam.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.net.Uri
import android.provider.Settings
import android.util.DisplayMetrics

/**
 * A home-screen tile: an installed app ([pkg]), a TV input to switch to ([inputId]),
 * or, with neither, the system Settings. [iconRes] is shown when there's no app icon:
 * Settings' gear, or the store icon of an app that isn't installed yet.
 */
class TileApp(val label: String, val pkg: String? = null, val inputId: String? = null, val iconRes: Int = 0) {
    /** The [Store] key for this destination, or null for Settings. */
    val visitKey: String? get() = if (inputId != null) Store.HDMI else pkg
}

// HDMI 2, where HDMI-CEC reports the Chromecast. The port-level input survives CEC re-addressing.
val CHROMECAST = TileApp(
    "Chromecast",
    inputId = "com.sony.dtv.tvinput.external/.ExternalTvInputService/HW3",
    iconRes = R.drawable.ic_cast,
)

// Package names checked on the TV (YouTube, Netflix) and on Google Play (Spotify, DRTV).
val TILE_APPS = listOf(
    TileApp("YouTube", "com.google.android.youtube.tv"),
    TileApp("Netflix", "com.netflix.ninja"),
    TileApp("Spotify", "com.spotify.tv.android", iconRes = R.drawable.app_spotify),
    TileApp("DRTV", "dk.dr.tvplayer", iconRes = R.drawable.app_drtv),
    TileApp("Settings", iconRes = R.drawable.ic_settings),
)

object Apps {
    /** Opens an app by package, or switches to the Chromecast input for [Store.HDMI]. */
    fun launchKey(context: Context, key: String) =
        launch(context, if (key == Store.HDMI) CHROMECAST else TileApp(key, key))

    fun launch(context: Context, app: TileApp) {
        val pm = context.packageManager
        val intent = when {
            app.inputId != null ->
                Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(app.inputId))
            app.pkg != null ->
                pm.getLeanbackLaunchIntentForPackage(app.pkg) ?: pm.getLaunchIntentForPackage(app.pkg)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        if (intent != null) {
            app.visitKey?.let { Store(context).recordVisit(it) }
            start(context, intent)
        } else if (app.pkg != null) {
            if (!start(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.pkg}")))) {
                start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${app.pkg}")))
            }
        }
    }

    /** The app's icon at the highest density it ships, or null when it isn't installed. */
    fun icon(context: Context, pkg: String): Drawable? {
        val pm = context.packageManager
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            val res = pm.getResourcesForApplication(info)
            val sharp = if (info.icon != 0) {
                try {
                    @Suppress("DEPRECATION")
                    res.getDrawableForDensity(info.icon, DisplayMetrics.DENSITY_XXXHIGH)
                } catch (e: Exception) {
                    null
                }
            } else null
            sharp ?: pm.getApplicationIcon(info)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
