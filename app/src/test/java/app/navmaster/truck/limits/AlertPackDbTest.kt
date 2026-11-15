package app.navmaster.truck.limits

import android.database.sqlite.SQLiteDatabase
import app.navmaster.truck.core.Geo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pack made by data/allerte.py, read as the tablet reads it: the fixture allerte-prova.sqlite is
 * the script's output for allerte-prova.csv (made-up points; to make it again:
 * `python3 data/allerte.py app/src/test/resources/allerte-prova.csv app/src/test/resources/allerte-prova.sqlite`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertPackDbTest {

  private fun open(): SQLiteDatabase {
    val src = File(javaClass.classLoader!!.getResource("allerte-prova.sqlite")!!.toURI())
    // a copy: the database is opened read-only, but SQLite may want to write next to it
    val f = File.createTempFile("allerte", ".sqlite").also { src.copyTo(it, overwrite = true); it.deleteOnExit() }
    return SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
  }

  @Test
  fun theScriptsOutputIsAPack() {
    open().use { db ->
      val info = AlertPack.readInfo(db)
      assertNotNull(info)
      // 11 rows: a double within 15 m, a town entrance and a row with its columns shifted are left out
      assertEquals(8, info!!.count)
      assertEquals(mapOf("IT" to 6, "AT" to 2), info.countries)
      assertEquals("Radarbot", info.source)
    }
  }

  @Test
  fun aDatabaseWithoutTheMarkIsNotAPack() {
    val f = File.createTempFile("altro", ".sqlite").also { it.delete(); it.deleteOnExit() }
    SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
      db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
      db.execSQL("INSERT INTO meta VALUES ('count', '5')")
      assertNull(AlertPack.readInfo(db))
    }
  }

  @Test
  fun pointsOfTheCountriesOnTheTablet() {
    open().use { db ->
      val it = AlertPack.points(db, listOf("it"))
      assertEquals(6, it.size)
      assertTrue(it.all { p -> p.country == "IT" && AlertSource.PACK in p.sources })
      val byGroup = it.associateBy { p -> p.group }
      assertEquals(50, byGroup.getValue(AlertGroup.FISSO).speed)
      assertEquals("Via Prova, SS10", byGroup.getValue(AlertGroup.FISSO).road)
      assertEquals(130, byGroup.getValue(AlertGroup.TUTOR).speed)
      assertEquals("Varco ZTL", byGroup.getValue(AlertGroup.ZTL).title)
      // a road name read as Latin-1 by the extraction is mended by the script
      assertEquals("Túnel di prova", byGroup.getValue(AlertGroup.PERICOLO).road)
      assertEquals(2, AlertPack.points(db, listOf("AT")).size)
      assertEquals(8, AlertPack.points(db, null).size)
      assertEquals(0, AlertPack.points(db, listOf("X'); DROP TABLE limits; --")).size)
    }
  }

  @Test
  fun theGuidanceFindsThemByTheCellsOfTheRoute() {
    open().use { db ->
      // the query of LimitsIndex.cameras on the cells of a route passing by the first camera
      val cell = Geo.cell(45.150000, 10.380000)
      db.rawQuery("SELECT DISTINCT l.kind, l.value, l.name, l.pts, l.ver FROM cells c JOIN limits l ON l.id = c.lid WHERE c.cell IN ($cell) " +
          "AND l.kind IN ('speed_camera','speed_camera_mobile','ztl','low_emission_zone','truck_control','speed_bump','danger')", null).use { c ->
        assertTrue(c.moveToFirst())
        assertEquals("speed_camera", c.getString(0))
        assertEquals(50.0, c.getDouble(1), 0.0)
        assertEquals("45.150000,10.380000", c.getString(3))
        assertEquals("radarbot", c.getString(4))
      }
    }
  }
}
