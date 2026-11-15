package app.navmaster.truck.core

import app.navmaster.truck.limits.AlertPack
import app.navmaster.truck.vehicle.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Speeds are km/h inside the app and shown in the driver's unit; the pack's limits for kinds of vehicles. */
class SpeedUnitTest {
  @Test
  fun milesAndKilometres() {
    // British signs in mph, kept in km/h, shown again in mph: the sign's own number
    for (mph in listOf(20, 30, 40, 50, 60, 70)) assertEquals(mph, SpeedUnit.show(SpeedUnit.fromMph(mph), inMph = true))
    assertEquals(48, SpeedUnit.fromMph(30))
    assertEquals(113, SpeedUnit.fromMph(70))
    assertEquals(81, SpeedUnit.show(130, inMph = true))
    assertEquals(130, SpeedUnit.show(130, inMph = false))
    assertEquals("mph", SpeedUnit.label(inMph = true))
    assertEquals("km/h", SpeedUnit.label(inMph = false))
    assertEquals("80", SpeedUnit.spoken(80, inMph = false))
    assertEquals("50 miglia orarie", SpeedUnit.spoken(80, inMph = true))
  }

  @Test
  fun vehicleLimitsOfThePack() {
    val truck = VehicleProfile.defaults().first { it.id == "camion" }
    val bus = VehicleProfile.defaults().first { it.id == "autobus" }
    val camper = VehicleProfile.defaults().first { it.id == "camper" }
    val veh = "hgv=80;van=70;bus=60"
    assertEquals(80, AlertPack.vehicleSpeed(veh, truck))
    assertEquals(60, AlertPack.vehicleSpeed(veh, bus))
    assertNull(AlertPack.vehicleSpeed(veh, camper))
    assertNull(AlertPack.vehicleSpeed(null, truck))
    assertNull(AlertPack.vehicleSpeed("van=70", truck))
    assertEquals(mapOf("hgv" to 80), AlertPack.vehicleSpeeds("hgv=80;bad;x=1000"))
  }
}
