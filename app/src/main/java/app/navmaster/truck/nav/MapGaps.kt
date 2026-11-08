package app.navmaster.truck.nav

import android.util.Log
import app.navmaster.truck.routing.RouteAnalysis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * The junctions of the driver's routes where OpenStreetMap lacks what the junction view and the
 * lane guidance need, kept on the tablet so they can be added to the map: at the exits and forks
 * where the route has an instruction, the direction sign of the road taken (destination tags)
 * and the lanes (turn:lanes, or lane counts that explain the junction). Each one with the link
 * that opens it in the map's editor. A junction found complete on a later route (the map fixed
 * and the maps on the tablet updated) leaves the list.
 */
object MapGaps {
  private const val TAG = "NmMapGaps"
  /** At most this many junctions are kept (the most recently driven). */
  private const val MAX = 400

  @Serializable
  data class Gap(
      val lat: Double,
      val lon: Double,
      /** "Uscita" or "Bivio". */
      val kind: String,
      /** The road arriving and the road taken as the graph names them ("A4 › SS45bis"). */
      val road: String,
      /** No direction sign for the road taken. */
      val noSign: Boolean,
      /** No lanes known: no arrows painted on the map and lane counts that do not explain the junction. */
      val noLanes: Boolean,
      /** The OSM way arriving (turn:lanes go on it) and the first way of the road taken (destination goes on it). */
      val approachWay: Long,
      val takenWay: Long,
      val firstSeen: String,
      val lastSeen: String,
      val times: Int = 1,
  ) {
    /** The junction's place, about 10 m: the same junction on another route. */
    val key: String get() = keyOf(lat, lon)
    val missing: String
      get() = listOfNotNull(if (noSign) "cartello" else null, if (noLanes) "corsie" else null).joinToString(" e ")
    /** The junction in OpenStreetMap's editor, on the way where the missing tags go. */
    val editUrl: String
      get() {
        val way = if (noLanes && approachWay > 0) approachWay else takenWay
        val sel = if (way > 0) "&way=$way" else ""
        return "https://www.openstreetmap.org/edit?editor=id$sel#map=19/${fmt(lat)}/${fmt(lon)}"
      }
  }

  private fun fmt(v: Double) = String.format(Locale.ROOT, "%.6f", v)
  internal fun keyOf(lat: Double, lon: Double) = String.format(Locale.ROOT, "%.4f,%.4f", lat, lon)

  private val json = Json { ignoreUnknownKeys = true }
  private val _gaps = MutableStateFlow<List<Gap>>(emptyList())
  /** The junctions to complete, the most recently driven first. */
  val gaps: StateFlow<List<Gap>> = _gaps.asStateFlow()
  private var file: File? = null
  private var gpxFile: File? = null

  /** Where the list is kept ([dir], private) and where its GPX copy is written ([exportDir], readable over the cable). */
  fun init(dir: File, exportDir: File?) {
    file = File(dir, "buchi_mappa.json")
    gpxFile = exportDir?.let { File(it, "buchi-mappa.gpx") }
    _gaps.value = runCatching { json.decodeFromString(ListSerializer(Gap.serializer()), file!!.readText()) }.getOrDefault(emptyList())
  }

  /**
   * The exits and forks with an instruction on the route of [a]: each junction's place and what
   * the map lacks there (null when it has the sign and the lanes).
   */
  internal fun scan(a: RouteAnalysis, lht: Boolean, today: String): List<Pair<String, Gap?>> {
    val out = ArrayList<Pair<String, Gap?>>()
    val ms = a.maneuvers
    for ((i, mt) in ms.withIndex()) {
      val (m, type) = mt
      if (type.contains("ARRIVE") || type.contains("DEPART") || type.contains("ROUNDABOUT") || type.contains("ROTARY")) continue
      val j = LaneModel.at(a, m, lht) ?: continue
      if (j.kind != LanePlan.Kind.EXIT && j.kind != LanePlan.Kind.FORK) continue
      val pos = a.pointAt(j.atM)
      // the sign of the road taken: on its first way, over the lanes to take (a sign over the other
      // lanes only is not the road taken's), or on a later piece of it before the route's next
      // choice (as the junction view finds it)
      val next = ms.getOrNull(i + 1)?.first ?: Double.MAX_VALUE
      val until = j.atM + minOf(300.0, next - j.atM - 5)
      val overTaken = j.laneSigns?.let { ls -> ls.size == j.lanes.size && j.active.any { ls[it].towns.isNotEmpty() || ls[it].refs.isNotEmpty() } } == true
      val hasSign = j.sign?.isEmpty == false || overTaken ||
          a.edges.any { it.startM > j.atM - 5 && it.startM <= until && it.sign?.isEmpty == false }
      val noLanes = j.lanes.isEmpty()
      val key = keyOf(pos.lat, pos.lng)
      if (hasSign && !noLanes) { out += key to null; continue }
      val approach = a.edgeAt((j.atM - 3).coerceAtLeast(0.0))
      val taken = a.edgeAt((j.atM + 15).coerceAtMost(a.length))
      // the roads as the driver knows them: the last numbered or named one before the junction and
      // the first one after it, within 3 km (the slip roads have neither)
      fun label(e: app.navmaster.truck.routing.EdgeInfo) = e.refs.firstOrNull() ?: e.name?.takeIf { it.isNotBlank() }
      val before = a.edges.lastOrNull { it.endM <= j.atM + 1 && it.endM > j.atM - 3000 && label(it) != null }?.let { label(it) } ?: "strada"
      val after = a.edges.firstOrNull { it.startM >= j.atM - 1 && it.startM < j.atM + 3000 && label(it) != null }?.let { label(it) } ?: "strada"
      out += key to Gap(pos.lat, pos.lng, if (j.kind == LanePlan.Kind.EXIT) "Uscita" else "Bivio", "$before › $after",
          !hasSign, noLanes, approach?.wayId ?: 0, taken?.wayId ?: 0, today, today)
    }
    return out
  }

  /** The junctions of a route just started, added to the list (and the complete ones taken off it). */
  @Synchronized
  fun record(a: RouteAnalysis, lht: Boolean) {
    if (file == null || a.edges.isEmpty()) return
    val today = LocalDate.now().toString()
    val found = runCatching { scan(a, lht, today) }.onFailure { Log.w(TAG, "scan", it) }.getOrNull() ?: return
    val map = LinkedHashMap<String, Gap>()
    for (g in _gaps.value) map[g.key] = g
    for ((key, gap) in found) {
      if (gap == null) { map.remove(key); continue }
      val old = map[key]
      map[key] = if (old == null) gap else gap.copy(firstSeen = old.firstSeen, times = old.times + 1)
    }
    val list = map.values.sortedWith(compareByDescending<Gap> { it.lastSeen }.thenByDescending { it.times }).take(MAX)
    _gaps.value = list
    Log.i(TAG, "${found.count { it.second != null }} incroci senza dati su ${found.size} del percorso; ${list.size} nell'elenco")
    runCatching {
      file!!.writeText(json.encodeToString(ListSerializer(Gap.serializer()), list))
      gpxFile?.let { it.parentFile?.mkdirs(); it.writeText(gpx(list)) }
    }.onFailure { Log.w(TAG, "save", it) }
  }

  private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  /** The list as GPX waypoints (to load as a layer in the map's editor), each with its link. */
  fun gpx(list: List<Gap>): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<gpx version=\"1.1\" creator=\"NavMaster\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    for (g in list) {
      append("  <wpt lat=\"${fmt(g.lat)}\" lon=\"${fmt(g.lon)}\"><name>${esc(g.kind + " " + g.road)}</name>")
      append("<desc>${esc("Manca: " + g.missing + " (visto ${g.times} volte, l'ultima il ${g.lastSeen})")}</desc>")
      append("<link href=\"${esc(g.editUrl)}\"/></wpt>\n")
    }
    append("</gpx>\n")
  }

  /** The list as text to send: one junction per paragraph, with what is missing and its link. */
  fun text(list: List<Gap>): String = buildString {
    append("Incroci dei percorsi NavMaster da completare su OpenStreetMap (${list.size})\n\n")
    for (g in list) append("${g.kind} ${g.road}: manca ${g.missing}\n${g.editUrl}\n\n")
  }
}
