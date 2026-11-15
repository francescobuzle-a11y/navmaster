package app.navmaster.truck.vehicle

import app.navmaster.truck.vehicle.SpeedRules.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The speed to respect (SpeedRules): the lowest of what the signs say, what the law says for the
 * vehicle's kind on that kind of road in that country, and the vehicle's own maximum. The cases are
 * the roads of the drivers' own trips (the A21 at Cremona, the SS10, the SS343) and the countries
 * of the tablet.
 */
class SpeedRulesTest {
  private val truck = VehicleProfile.defaults().first { it.id == "camion" }       // 40 t articulated, own maximum 80
  private val rigid10 = VehicleProfile.defaults().first { it.id == "motrice" }.copy(maxWeightT = 10.0, topSpeedKmh = 100)
  private val bus = VehicleProfile.defaults().first { it.id == "autobus" }
  private val camper = VehicleProfile.defaults().first { it.id == "camper" }

  private fun check(l: SpeedRules.Limit?, kmh: Int, why: Why) {
    assertEquals(l.toString(), kmh, l?.kmh)
    assertEquals(l.toString(), why, l?.why)
  }

  @Test
  fun theMotorwayOfTheScreenshot() {
    // the A21: the signs say 130, the law says 80 for a lorry over 12 t: 80, said to be for the vehicle, the sign's 130 kept beside it
    val l = SpeedRules.at(truck, "IT", "motorway", 130)
    check(l, 80, Why.LEGAL)
    assertEquals(130, l!!.signed)
    assertEquals(130, l.signedAbove)
    // the same without a number on the map: the law's 80 stands
    check(SpeedRules.at(truck, "IT", "motorway", null), 80, Why.LEGAL)
    // a slip road with no number: nothing is said (80 would be too high there)
    assertNull(SpeedRules.at(truck, "IT", "motorway", null, ramp = true))
    // a slip road signed 60: the sign
    check(SpeedRules.at(truck, "IT", "motorway", 60, ramp = true), 60, Why.SIGNED)
  }

  @Test
  fun italianRoadsOutsideMotorways() {
    // the SS10 signed 90: 70 for a lorry over 12 t, not the profile's 80
    check(SpeedRules.at(truck, "IT", "secondary", 90), 70, Why.LEGAL)
    // an expressway signed 110: 70
    check(SpeedRules.at(truck, "IT", "trunk", 110), 70, Why.LEGAL)
    // the SS343 signed 70: the sign, nothing to add
    val l = SpeedRules.at(truck, "IT", "primary", 70)
    check(l, 70, Why.SIGNED)
    assertNull(l!!.signedAbove)
    // in a town: the sign, 50 or less
    check(SpeedRules.at(truck, "IT", "secondary", 50), 50, Why.SIGNED)
    check(SpeedRules.at(truck, "IT", "residential", 30), 30, Why.SIGNED)
    // outside a motorway with no number on the map: nothing (it may be a town street)
    assertNull(SpeedRules.at(truck, "IT", "secondary", null))
    assertNull(SpeedRules.at(truck, "IT", "primary", null))
    // a lighter lorry (3.5 to 12 t): 100 on motorways (its own 100), 80 outside
    check(SpeedRules.at(rigid10, "IT", "motorway", 130), 100, Why.LEGAL)
    check(SpeedRules.at(rigid10, "IT", "secondary", 90), 80, Why.LEGAL)
  }

  @Test
  fun otherCountries() {
    check(SpeedRules.at(truck, "AT", "motorway", 130), 80, Why.LEGAL)
    check(SpeedRules.at(truck, "AT", "primary", 100), 70, Why.LEGAL)
    check(SpeedRules.at(truck, "DE", "motorway", 130), 80, Why.LEGAL)
    // outside towns in Germany: 60 over 7.5 t or with a trailer
    check(SpeedRules.at(truck, "DE", "secondary", 100), 60, Why.LEGAL)
    check(SpeedRules.at(rigid10.copy(maxWeightT = 7.0), "DE", "secondary", 100), 80, Why.LEGAL)
    // France allows 90 on motorways, the profile's own 80 stands: said to be the driver's own
    val fr = SpeedRules.at(truck, "FR", "motorway", 130)
    check(fr, 80, Why.OWN)
    assertEquals(90, fr!!.legal)
    // a country with no table: the signs and the profile's own maximum
    check(SpeedRules.at(truck, "SK", "motorway", 130), 80, Why.OWN)
    check(SpeedRules.at(truck, "SK", "primary", 90), 80, Why.OWN)
    check(SpeedRules.at(truck, null, "motorway", 130), 80, Why.OWN)
  }

  @Test
  fun vehiclesWithoutATable() {
    // a bus or a camper: the signs and the profile's own maximum
    check(SpeedRules.at(bus, "IT", "motorway", 130), 100, Why.OWN)
    check(SpeedRules.at(bus, "IT", "secondary", 90), 90, Why.SIGNED)
    check(SpeedRules.at(camper, "IT", "motorway", 130), 100, Why.OWN)
    // the profile's own maximum alone is not a limit to show
    assertNull(SpeedRules.at(bus, "IT", "secondary", null))
    // a road whose sign is lower than all: the sign, whatever the vehicle
    check(SpeedRules.at(truck, "IT", "motorway", 60), 60, Why.SIGNED)
  }

  @Test
  fun theThreeNumbersAreKept() {
    val l = SpeedRules.at(truck, "IT", "secondary", 90)!!
    assertEquals(90, l.signed)
    assertEquals(70, l.legal)
    assertEquals(80, l.own)
    assertEquals(90, l.signedAbove)
  }
}
