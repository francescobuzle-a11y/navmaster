package app.navmaster.truck.limits

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading, merging and drawing the alerts of the map (Alerts, AlertGroup) and the merge of the cameras of a route. */
class AlertsTest {

  private fun pack(lat: Double, lon: Double, grp: String, type: String, kind: String, name: String? = null, speed: Double = 0.0,
                   road: String? = null, dir: String? = null) =
      Alerts.fromPack(lat, lon, grp, type, kind, name, speed, road, dir, "IT")!!

  @Test
  fun groupsFromTheWordsOfEachList() {
    // checked data (build_limits.py / census_it.py)
    assertEquals(AlertGroup.FISSO, Alerts.groupOf("speed_camera", null))
    assertEquals(AlertGroup.FISSO, Alerts.groupOf("speed_camera", "non nel censimento del ministero"))
    assertEquals(AlertGroup.SEMAFORO, Alerts.groupOf("speed_camera", "semaforo rosso"))
    assertEquals(AlertGroup.TUTOR, Alerts.groupOf("speed_camera", "controllo della velocità media"))
    assertEquals(AlertGroup.TUTOR, Alerts.groupOf("average_speed", null))
    // the imported list (CameraImport: kinds from the names of the file)
    assertEquals(AlertGroup.MOBILE, Alerts.groupOf("speed_camera_mobile", "Autovelox mobile"))
    assertEquals(AlertGroup.ZTL, Alerts.groupOf("access", "Zona a traffico limitato"))
    assertNull("a ban of the map is not an alert", Alerts.groupOf("access", null))
    assertEquals(AlertGroup.CINTURE, Alerts.groupOf("speed_camera", "Camera cintura"))
    assertEquals(AlertGroup.LEZ, Alerts.groupOf("low_emission_zone", "Zona ambientale"))
    assertEquals(AlertGroup.CAMION, Alerts.groupOf("truck_control", null))
    assertEquals(AlertGroup.DOSSO, Alerts.groupOf("speed_bump", "Dosso"))
    assertEquals(AlertGroup.DOSSO, Alerts.groupOf("danger", "Strada dissestata"))
    assertEquals(AlertGroup.PERICOLO, Alerts.groupOf("danger", "Curva pericolosa"))
    assertNull(Alerts.groupOf("maxheight", null))
  }

  @Test
  fun packRowsKeepTypeSpeedRoadAndDirection() {
    val f = pack(45.0, 10.0, "fisso", "FT", "speed_camera", speed = 50.0, road = "Via Prova, SS10", dir = "4")
    assertEquals(AlertGroup.FISSO, f.group)
    assertEquals("Autovelox fisso", f.title)
    assertEquals(50, f.speed)
    assertEquals("4", f.dirCode)
    assertTrue("a code with no known meaning is not shown", f.details().none { it.first == "Direzione" })
    assertTrue(f.details().contains("Limite" to "50 km/h"))
    assertEquals("Autovelox in galleria", pack(45.0, 10.0, "fisso", "U", "speed_camera").title)
    assertEquals("Curva pericolosa", pack(45.0, 10.0, "pericolo", "CC", "danger", "Curva pericolosa").title)
    // a speed outside 5–150 is a service code, not a limit
    assertEquals(0, pack(45.0, 10.0, "mobile", "C", "speed_camera_mobile", speed = 524288.0).speed)
  }

  @Test
  fun theSameCameraInTwoListsIsOneCompletedByBoth() {
    val fromPack = pack(45.15, 10.38, "fisso", "FT", "speed_camera", speed = 50.0, road = "SS10", dir = "1")
    // the checked data: 12 m away, with the direction it looks at and the census check, no speed
    val fromData = Alerts.fromLimit(45.15008, 10.38006, "speed_camera", null, 0.0, "90", "doppio", AlertSource.DATA)!!
    val merged = Alerts.merge(listOf(listOf(fromPack), listOf(fromData)))
    assertEquals(1, merged.size)
    val m = merged[0]
    assertEquals(50, m.speed)
    assertEquals("SS10", m.road)
    assertEquals("90", m.facing)
    assertEquals("doppio", m.ver)
    assertEquals(setOf(AlertSource.PACK, AlertSource.DATA), m.sources)
    assertTrue(m.details().any { it.first == "Inquadra" && it.second.contains("ovest") })
  }

  @Test
  fun aPreciseGroupWinsOverAPlainFixedCamera() {
    val plain = Alerts.fromLimit(45.0, 10.0, "speed_camera", null, 0.0, null, "osm", AlertSource.DATA)!!
    val red = pack(45.0001, 10.0, "semaforo", "S", "speed_camera", "semaforo rosso")
    val merged = Alerts.merge(listOf(listOf(plain), listOf(red)))
    assertEquals(1, merged.size)
    assertEquals(AlertGroup.SEMAFORO, merged[0].group)
    assertEquals("Semaforo rosso", merged[0].title)
  }

  @Test
  fun differentKindsOrFarApartStayApart() {
    val cam = pack(45.0, 10.0, "fisso", "F", "speed_camera")
    val bump = pack(45.0, 10.0, "dosso", "R", "speed_bump")
    val mobile = pack(45.0, 10.0, "mobile", "C", "speed_camera_mobile")
    val far = pack(45.0006, 10.0, "fisso", "F", "speed_camera") // 67 m north
    val merged = Alerts.merge(listOf(listOf(cam, bump, mobile), listOf(far)))
    assertEquals(4, merged.size)
    // a point on the edge of a grid cell finds its double in the next cell
    val a = pack(45.00099, 10.0, "fisso", "F", "speed_camera")
    val b = pack(45.00101, 10.0, "fisso", "F", "speed_camera")
    assertEquals(1, Alerts.merge(listOf(listOf(a), listOf(b))).size)
  }

  @Test
  fun unconfirmedCamerasOnlyWhenWantedOrConfirmedByThePack() {
    val doubtful = Alerts.fromLimit(45.0, 10.0, "speed_camera", "non nel censimento del ministero", 0.0, null, "dubbio", AlertSource.DATA)!!
    assertFalse(doubtful.visible(unverified = false))
    assertTrue(doubtful.visible(unverified = true))
    assertTrue(doubtful.details().contains("Verifica" to "non nel censimento del ministero"))
    val confirmed = Alerts.merge(listOf(listOf(doubtful), listOf(pack(45.0, 10.0001, "fisso", "F", "speed_camera"))))
    assertEquals(1, confirmed.size)
    assertTrue(confirmed[0].visible(unverified = false))
  }

  @Test
  fun theMapShowsTheChosenGroupsAndNotTheCamerasDriversSayAreGone() {
    val all = listOf(
        pack(45.0, 10.0, "fisso", "F", "speed_camera"),
        pack(45.1, 10.0, "dosso", "R", "speed_bump"),
        pack(45.2, 10.0, "ztl", "AR", "ztl", "Varco ZTL"),
    )
    assertEquals(listOf(AlertGroup.FISSO, AlertGroup.ZTL), Alerts.shown(all, setOf("fisso", "ztl"), false).map { it.group })
    // drivers said the camera is gone (an answer 10 m away)
    assertEquals(listOf(AlertGroup.ZTL), Alerts.shown(all, setOf("fisso", "ztl"), false, listOf(45.00009 to 10.0)).map { it.group })
  }

  @Test
  fun geoJsonForTheMap() {
    val pts = listOf(
        pack(45.123456789, -3.5, "fisso", "F", "speed_camera", speed = 50.0),
        pack(-0.0000001, 10.0, "pericolo", "TC", "danger", "Tornanti"),
    )
    val sb = StringBuilder()
    Alerts.writeGeoJson(pts, listOf(0, 1), sb)
    val root = Json.parseToJsonElement(sb.toString()).jsonObject
    val features = root["features"]!!.jsonArray
    assertEquals(2, features.size)
    val f0 = features[0].jsonObject
    val coords = f0["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertEquals(listOf("-3.500000", "45.123457"), coords)
    val props = f0["properties"] as JsonObject
    assertEquals("fisso", props["g"]!!.jsonPrimitive.content)
    assertEquals("50", props["v"]!!.jsonPrimitive.content)
    assertEquals("0", props["i"]!!.jsonPrimitive.content)
    assertEquals("", features[1].jsonObject["properties"]!!.jsonObject["v"]!!.jsonPrimitive.content)
    assertEquals("0.000000", Alerts.coord(-0.0000001))
    // an empty list is still a valid collection
    val empty = StringBuilder().also { Alerts.writeGeoJson(pts, emptyList(), it) }.toString()
    assertEquals(0, Json.parseToJsonElement(empty).jsonObject["features"]!!.jsonArray.size)
  }

  @Test
  fun aRouteCameraIsCompletedByThePackAndConfirmedWhenTheDataPutItAside() {
    fun cam(value: Double, name: String?, ver: String?) = RouteLimit("speed_camera", value, null, name, null, 100.0, 45.0, 10.0, false, null, ver)
    // the census put it aside; the pack has it with its speed
    val m = LimitsIndex.mergeCamera(cam(0.0, "non nel censimento del ministero", "dubbio"), cam(50.0, null, "radarbot"))
    assertEquals(50.0, m.value, 0.0)
    assertEquals("Radarbot", m.confirmedBy)
    assertEquals("non nel censimento del ministero", m.name)
    assertTrue(m.check!!.contains("anche in Radarbot"))
    // a plain camera learns from the pack that it is a red-light camera, a confirmed one is not "confirmed by" again
    val r = LimitsIndex.mergeCamera(cam(0.0, null, "doppio"), cam(0.0, "semaforo rosso", "radarbot"))
    assertEquals("semaforo rosso", r.cameraWhat)
    assertNull(r.confirmedBy)
    assertEquals("da Radarbot", cam(0.0, null, "radarbot").check)
  }
}
