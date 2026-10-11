package app.navmaster.truck.live

import app.navmaster.truck.BuildConfig
import app.navmaster.truck.settings.Settings

/**
 * The keys of the services: the ones typed on the tablet by the owner (Settings › Chiavi dei
 * servizi, behind the owner's password, see ui.KeysScreen) first, else the ones built into the app
 * (app/build.gradle.kts, the repository's secrets). The emulator tests set them the same way.
 */
object ApiKeys {
  fun tomtom(s: Settings): String = s.tomtomKey.ifBlank { BuildConfig.TOMTOM_KEY }

  fun here(s: Settings): String = s.hereKey.ifBlank { BuildConfig.HERE_KEY }

  fun trafikverket(s: Settings): String = s.trafikverketKey.ifBlank { BuildConfig.TRAFIKVERKET_KEY }

  fun mapillary(s: Settings): String = s.mapillaryToken.ifBlank { BuildConfig.MAPILLARY_TOKEN }

  fun ors(s: Settings): String = s.orsKey.ifBlank { BuildConfig.ORS_KEY }
}
