package app.navmaster.truck.ui

import android.content.Context
import app.navmaster.truck.data.InstalledRegion
import java.io.File
import java.time.LocalTime

/** Loads the NavMaster style from the assets and points it at the region's offline map. */
object MapStyles {
  fun isNight(now: LocalTime = LocalTime.now()): Boolean = now.hour >= 20 || now.hour < 7

  fun styleUri(context: Context, region: InstalledRegion?, night: Boolean): String {
    val out = File(context.filesDir, if (night) "style-night.json" else "style-day.json")
    val json =
        if (region == null || !region.map.exists()) {
          EMPTY.replace("{BG}", if (night) "#1B1F24" else "#F2EFE9")
        } else {
          val name = if (night) "style/style-night.json" else "style/style-day.json"
          context.assets.open(name).bufferedReader().use { it.readText() }.replace("{PMTILES}", region.map.absolutePath)
        }
    if (!out.exists() || out.readText() != json) out.writeText(json)
    return "file://" + out.absolutePath
  }

  private const val EMPTY =
      """{"version":8,"name":"NavMaster vuota","sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"{BG}"}}]}"""
}
