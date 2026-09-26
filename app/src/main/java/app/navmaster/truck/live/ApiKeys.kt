package app.navmaster.truck.live

import app.navmaster.truck.BuildConfig
import app.navmaster.truck.settings.Settings

/**
 * The keys of the services, built into the app (see app/build.gradle.kts): nothing to ask for or
 * type. A key set by the emulator tests (hidden settings) comes first.
 */
object ApiKeys {
  fun tomtom(s: Settings): String = s.tomtomKey.ifBlank { BuildConfig.TOMTOM_KEY }

  fun here(s: Settings): String = s.hereKey.ifBlank { BuildConfig.HERE_KEY }

  fun trafikverket(s: Settings): String = s.trafikverketKey.ifBlank { BuildConfig.TRAFIKVERKET_KEY }

  fun mapillary(s: Settings): String = s.mapillaryToken.ifBlank { BuildConfig.MAPILLARY_TOKEN }
}
