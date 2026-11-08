package app.navmaster.truck.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** The index of the weekly camera files: which countries, how big, which version. */
class CameraFilesTest {
  @Test
  fun readsTheIndex() {
    val text = """{"files":[
      {"name":"autovelox-italy.sqlite","size":2345678,"updated":"2026-11-09T03:41:12Z"},
      {"name":"autovelox-czech-republic.sqlite","size":120000,"updated":"2026-11-09T03:44:00Z"},
      {"name":"indice.json","size":300,"updated":"2026-11-09T03:50:00Z"},
      {"name":"autovelox-../x.sqlite","size":1,"updated":"2026-11-09T03:50:00Z"},
      {"name":"autovelox-austria.sqlite","size":0,"updated":"2026-11-09T03:50:00Z"}]}"""
    val e = CameraFiles.parseIndex(text)
    assertEquals(listOf("italy", "czech-republic"), e.map { it.country })
    assertEquals("20261109034112", e[0].stamp)
    assertEquals(2345678L, e[0].size)
    assertEquals(emptyList<CameraFiles.Entry>(), CameraFiles.parseIndex("not json"))
  }
}
