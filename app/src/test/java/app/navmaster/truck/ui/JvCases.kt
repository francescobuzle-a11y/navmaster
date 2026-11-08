package app.navmaster.truck.ui

import app.navmaster.truck.data.RoadsIndex
import app.navmaster.truck.nav.LaneModel
import app.navmaster.truck.nav.LanePlan
import app.navmaster.truck.routing.EdgeInfo
import app.navmaster.truck.routing.EdgeSign
import app.navmaster.truck.routing.LaneDest
import uniffi.ferrostar.GeographicCoordinate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Three made-up junctions for the tests of the junction view (a motorway exit with its signs, a
 * motorway fork known from the lane counts with signs over the lanes, a junction in town with
 * painted arrows and no sign on the map), with their roads and their route.
 */
internal object JvCases {
  const val lat0 = 45.5
  const val lon0 = 10.2
  val kx = 111_320.0 * cos(Math.toRadians(lat0))

  /** A point [e] metres east and [n] metres north of the origin. */
  fun at(e: Double, n: Double) = GeographicCoordinate(lat0 + n / 110_540.0, lon0 + e / kx)

  fun line(vararg en: Pair<Double, Double>) = en.map { at(it.first, it.second) }

  fun road(osm: Long, hw: String, oneway: Int, lanes: Int?, pts: List<GeographicCoordinate>, lanesF: Int? = null, lanesB: Int? = null, bridge: Boolean = false) =
      RoadsIndex.Road(osm, hw, null, null, null, null, oneway, lanes, lanesF, lanesB, null, null, null, false, false, false, bridge, null, null, pts)

  fun edge(start: Double, end: Double, cls: String, use: String, lanes: Int, way: Long) =
      EdgeInfo(start, end, cls, use, false, "paved", lanes, way, null, "IT", false, false, null, null)

  /** The route along [pts] (metres along it from its first point). */
  fun route(pts: List<GeographicCoordinate>, edges: List<EdgeInfo>): JvRoute {
    val xy = pts.map { doubleArrayOf((it.lng - lon0) * kx, (it.lat - lat0) * 110_540.0) }
    val cum = DoubleArray(pts.size)
    for (i in 1 until pts.size) cum[i] = cum[i - 1] + sqrt((xy[i][0] - xy[i - 1][0]).let { it * it } + (xy[i][1] - xy[i - 1][1]).let { it * it })
    val length = cum.last()
    val pointAt = { m: Double ->
      val d = m.coerceIn(0.0, length)
      var i = 1
      while (i < cum.size - 1 && cum[i] < d) i++
      val t = if (cum[i] > cum[i - 1]) (d - cum[i - 1]) / (cum[i] - cum[i - 1]) else 0.0
      GeographicCoordinate(pts[i - 1].lat + (pts[i].lat - pts[i - 1].lat) * t, pts[i - 1].lng + (pts[i].lng - pts[i - 1].lng) * t)
    }
    return JvRoute(Any(), length, pointAt, { m -> edges.firstOrNull { m >= it.startM && m <= it.endM } }, edges)
  }

  /** A junction to draw: its scene, the roads of the graph around it, where the vehicle is, whether the map has its signs. */
  class Case(val name: String, val scene: JunctionScene, val roads: List<RoadsIndex.Road>, val vehAlong: Double, val signFromMap: Boolean)

  /** Motorway A4 eastbound, 3 lanes, exit 5 on the right (the right lane, "through;slight_right"). */
  fun exitCase(): Case {
    val ramp = listOf(0.0 to 0.0, 25.0 to -2.0, 50.0 to -6.0, 75.0 to -12.0, 100.0 to -20.0, 130.0 to -32.0, 160.0 to -46.0,
        200.0 to -67.0, 250.0 to -95.0, 300.0 to -122.0, 360.0 to -150.0)
    val pts = line(-900.0 to 0.0, *ramp.toTypedArray())
    val r = route(pts, emptyList()).let { r0 ->
      route(pts, listOf(edge(0.0, 900.0, "motorway", "", 3, 1), edge(900.0, r0.length, "motorway", "ramp", 1, 2)))
    }
    val taken = EdgeSign(listOf("5"), listOf("SS45bis"), listOf("Brescia Centro", "Montichiari"), emptyList())
    val other = EdgeSign(emptyList(), listOf("A4"), listOf("Venezia", "Verona"), emptyList())
    val j = LaneModel.Junction(
        900.0, LanePlan.Kind.EXIT, 1,
        listOf(LanePlan.Lane("straight", false), LanePlan.Lane("straight", false),
            LanePlan.Lane("slight right", true, arrows = listOf("straight", "slight right"))),
        true,
        listOf(LaneModel.Road(22.0, true, 1, "motorway", true, taken, 2), LaneModel.Road(0.0, false, 2, "motorway", false, other, 4)),
        taken, other, null, 1, 2, 1, "motorway", false)
    val roads = listOf(
        road(1, "motorway", 1, 3, line(-1500.0 to 0.0, 0.0 to 0.0)),
        road(4, "motorway", 1, 2, line(0.0 to 0.0, 400.0 to 3.0, 900.0 to 10.0)),
        road(2, "motorway_link", 1, 1, line(*ramp.toTypedArray())),
        road(3, "motorway", 1, 3, line(900.0 to 30.0, -1500.0 to 30.0)),
        road(5, "tertiary", 0, 2, line(380.0 to 400.0, 370.0 to -400.0), bridge = true),
        road(6, "unclassified", 0, null, line(360.0 to -150.0, 420.0 to -170.0, 520.0 to -175.0)),
    )
    return Case("uscita", JunctionScene(j, 120.0, 1500.0, r, 780.0, 900.0, "IT", 2), roads, 780.0, true)
  }

  /** A4 → A21 fork: 4 lanes, the right two to Piacenza (counts 4 = 2 + 2), signs over the lanes. */
  fun forkCase(): Case {
    val right = listOf(0.0 to 0.0, 60.0 to -3.0, 120.0 to -10.0, 200.0 to -24.0, 300.0 to -45.0, 420.0 to -72.0)
    val pts = line(-900.0 to 0.0, *right.toTypedArray())
    val r = route(pts, emptyList()).let { r0 ->
      route(pts, listOf(edge(0.0, 900.0, "motorway", "", 4, 11), edge(900.0, r0.length, "motorway", "", 2, 12)))
    }
    val toPc = LaneDest(listOf("Piacenza", "Cremona"), listOf("A21"), null)
    val toMi = LaneDest(listOf("Milano"), listOf("A4"), null)
    val j = LaneModel.Junction(
        900.0, LanePlan.Kind.FORK, 1,
        (0 until 4).map { LanePlan.Lane("straight", it >= 2, painted = false) },
        false,
        listOf(LaneModel.Road(10.0, true, 2, "motorway", false, null, 12), LaneModel.Road(-3.0, false, 2, "motorway", false, null, 13)),
        null, null, listOf(toMi, toMi, toPc, toPc), 2, 2, 11, "motorway", false)
    val roads = listOf(
        road(11, "motorway", 1, 4, line(-1500.0 to 0.0, 0.0 to 0.0)),
        road(12, "motorway", 1, 2, line(*right.toTypedArray())),
        road(13, "motorway", 1, 2, line(0.0 to 0.0, 200.0 to 8.0, 500.0 to 30.0)),
        road(14, "motorway", 1, 4, line(600.0 to 40.0, -1500.0 to 32.0)),
    )
    return Case("bivio", JunctionScene(j, 240.0, 1500.0, r, 660.0, 900.0, "IT", 3), roads, 790.0, true)
  }

  /**
   * In town: a primary road with three lanes our way ("left|through|through;right"), right turn
   * onto the SP12 "Via Roma"; no direction sign on the map (the sign is made from the road taken).
   */
  fun townCase(): Case {
    val pts = line(-400.0 to 0.0, 0.0 to 0.0, 0.0 to -300.0)
    val r = route(pts, listOf(edge(0.0, 400.0, "primary", "", 5, 21),
        edge(400.0, 700.0, "secondary", "", 2, 22).copy(name = "Via Roma", names = listOf("SP12", "Via Roma"))))
    val j = LaneModel.Junction(
        400.0, LanePlan.Kind.JUNCTION, 1,
        listOf(LanePlan.Lane("left", false), LanePlan.Lane("straight", false),
            LanePlan.Lane("right", true, arrows = listOf("straight", "right"))),
        true,
        listOf(LaneModel.Road(90.0, true, 2, "secondary", false, null, 22), LaneModel.Road(0.0, false, 4, "primary", false, null, 23),
            LaneModel.Road(-90.0, false, 2, "secondary", false, null, 24)),
        null, null, null, 2, 4, 21, "primary", false)
    val roads = listOf(
        road(21, "primary", 0, 5, line(-600.0 to 0.0, 0.0 to 0.0), lanesF = 3, lanesB = 2),
        road(22, "secondary", 0, 2, line(0.0 to 0.0, 0.0 to -400.0)),
        road(23, "primary", 0, 4, line(0.0 to 0.0, 500.0 to 0.0)),
        road(24, "secondary", 0, 2, line(0.0 to 0.0, 0.0 to 400.0)),
        road(25, "residential", 0, null, line(-200.0 to 0.0, -200.0 to 300.0)),
        road(26, "residential", 0, null, line(-120.0 to 0.0, -120.0 to -250.0)),
    )
    return Case("citta", JunctionScene(j, 80.0, 300.0, r, 320.0, 400.0, "IT", null), roads, 382.0, false)
  }

  /**
   * In town, a slight right turn (30°): a primary road with three lanes our way ("left|through|
   * slight_right"), onto the two-way SP12 "Via Roma"; no direction sign on the map.
   */
  fun slightCase(): Case {
    val a30 = Math.toRadians(30.0)
    val out = (1..12).map { k -> (k * 25.0 * kotlin.math.cos(a30)) to (-k * 25.0 * kotlin.math.sin(a30)) }
    val pts = line(-400.0 to 0.0, 0.0 to 0.0, *out.toTypedArray())
    val r0 = route(pts, emptyList())
    val r = route(pts, listOf(edge(0.0, 400.0, "primary", "", 3, 41),
        edge(400.0, r0.length, "secondary", "", 1, 42).copy(name = "Via Roma", names = listOf("SP12", "Via Roma"))))
    val j = LaneModel.Junction(
        400.0, LanePlan.Kind.JUNCTION, 1,
        listOf(LanePlan.Lane("left", false), LanePlan.Lane("straight", false), LanePlan.Lane("slight right", true)),
        true,
        listOf(LaneModel.Road(30.0, true, 2, "secondary", false, null, 42), LaneModel.Road(0.0, false, 2, "primary", false, null, 43),
            LaneModel.Road(-90.0, false, 1, "residential", false, null, 44)),
        null, null, null, 2, 2, 41, "primary", false)
    val roads = listOf(
        road(41, "primary", 0, 5, line(-600.0 to 0.0, 0.0 to 0.0), lanesF = 3, lanesB = 2),
        road(42, "secondary", 0, 2, line(0.0 to 0.0, *out.toTypedArray())),
        road(43, "primary", 0, 4, line(0.0 to 0.0, 500.0 to 0.0)),
        road(44, "residential", 0, null, line(0.0 to 0.0, 0.0 to 300.0)),
    )
    return Case("svolta_leggera", JunctionScene(j, 90.0, 300.0, r, 310.0, 400.0, "IT", null), roads, 375.0, false)
  }

  /**
   * A line driven from ([e0], [n0]) heading east, in steps of 10 m: each leg (metres, degrees
   * turned over it, right positive).
   */
  fun drive(e0: Double, n0: Double, vararg legs: Pair<Double, Double>): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>()
    var e = e0; var n = n0; var head = 0.0 // math angle, east = 0, counter-clockwise
    for ((len, turn) in legs) {
      val steps = maxOf(1, (len / 10).toInt())
      for (k in 0 until steps) {
        head -= Math.toRadians(turn / steps)
        e += 10 * kotlin.math.cos(head); n += 10 * kotlin.math.sin(head)
        out += e to n
      }
    }
    return out
  }

  /** A motorway exit on the right of [lanes] lanes onto a slip road driven along [ramp], the motorway going on east. */
  private fun motorwayExit(name: String, lanes: List<LanePlan.Lane>, ramp: List<Pair<Double, Double>>, taken: EdgeSign, other: EdgeSign,
                           kind: LanePlan.Kind = LanePlan.Kind.EXIT, laneSigns: List<LaneDest>? = null, nextSide: Int? = null, nextM: Double? = null,
                           vehicleLane: Int? = null): Case {
    val pts = line(-900.0 to 0.0, 0.0 to 0.0, *ramp.toTypedArray())
    val n = lanes.count { !it.added }
    val r = route(pts, emptyList()).let { r0 ->
      route(pts, listOf(edge(0.0, 900.0, "motorway", "", n, 51), edge(900.0, r0.length, "motorway", if (kind == LanePlan.Kind.EXIT) "ramp" else "", 1, 52)))
    }
    val j = LaneModel.Junction(
        900.0, kind, 1, lanes, lanes.any { it.painted },
        listOf(LaneModel.Road(18.0, true, 1, "motorway", kind == LanePlan.Kind.EXIT, taken, 52), LaneModel.Road(0.0, false, n, "motorway", false, other, 53)),
        taken, other, laneSigns, 1, n, 51, "motorway", false)
    val roads = listOf(
        road(51, "motorway", 1, n, line(-1500.0 to 0.0, 0.0 to 0.0)),
        road(52, "motorway_link", 1, 1, line(0.0 to 0.0, *ramp.toTypedArray())),
        road(53, "motorway", 1, n, line(0.0 to 0.0, 900.0 to 0.0)),
    )
    return Case(name, JunctionScene(j, 260.0, 1500.0, r, 640.0, 900.0, "IT", vehicleLane, nextSide = nextSide, nextM = nextM), roads, 640.0, true)
  }

  /**
   * A1 with two lanes, exit 12 on the right without turn:lanes: the slip road leaves from a lane
   * that opens before the exit (the counts: 2 before, 2 going on, 1 on the slip road), then curves
   * hard to the right (about 110°).
   */
  fun decelCase(): Case = motorwayExit("decelerazione",
      LaneModel.lanesFromCounts(2, 1, 2, 1, LanePlan.Kind.EXIT)!!,
      drive(0.0, 0.0, 40.0 to 8.0, 220.0 to 105.0),
      EdgeSign(listOf("12"), listOf("SS9"), listOf("Lodi", "Codogno"), emptyList()),
      EdgeSign(emptyList(), listOf("A1"), listOf("Bologna"), emptyList()), vehicleLane = 1)

  /** A cloverleaf: exit on the right of three lanes, the slip road looping 270° to the right. */
  fun loopCase(): Case = motorwayExit("ricciolo",
      LaneModel.lanesFromCounts(3, 1, 2, 1, LanePlan.Kind.EXIT)!!,
      drive(0.0, 0.0, 60.0 to 10.0, 330.0 to 260.0),
      EdgeSign(listOf("7"), listOf("SP415"), listOf("Crema"), emptyList()),
      EdgeSign(emptyList(), listOf("A35"), listOf("Milano"), emptyList()))

  /** An exit on the right whose slip road curves back to the left, across the motorway on a bridge. */
  fun backCase(): Case = motorwayExit("rampa_indietro",
      LaneModel.lanesFromCounts(3, 1, 2, 1, LanePlan.Kind.EXIT)!!,
      drive(0.0, 0.0, 80.0 to 15.0, 300.0 to -125.0),
      EdgeSign(listOf("4"), listOf("SS10"), listOf("Cremona"), emptyList()),
      EdgeSign(emptyList(), listOf("A21"), listOf("Brescia"), emptyList()))

  /**
   * A fork on the right of three lanes onto two (the middle lane goes both ways: counts 3 < 2 + 2),
   * the road taken dividing again 250 m on, the route keeping left there.
   */
  fun splitAgainCase(): Case = motorwayExit("doppio_bivio",
      LaneModel.lanesFromCounts(3, 2, 2, 1, LanePlan.Kind.FORK)!!,
      drive(0.0, 0.0, 300.0 to 20.0),
      EdgeSign(emptyList(), listOf("A21"), listOf("Piacenza", "Cremona"), emptyList()),
      EdgeSign(emptyList(), listOf("A4"), listOf("Milano"), emptyList()),
      kind = LanePlan.Kind.FORK, nextSide = -1, nextM = 250.0)

  /** A junction in town of the road arriving and three roads at [angles] (the taken one first) with its painted lanes. */
  private fun townJunction(name: String, lanes: List<LanePlan.Lane>, angles: List<Double>, out: List<Pair<Double, Double>>): Case {
    val pts = line(-400.0 to 0.0, 0.0 to 0.0, *out.toTypedArray())
    val r0 = route(pts, emptyList())
    val r = route(pts, listOf(edge(0.0, 400.0, "primary", "", lanes.size, 61),
        edge(400.0, r0.length, "secondary", "", 2, 62).copy(name = "Via Roma", names = listOf("SP12", "Via Roma"))))
    val side = if (angles[0] > 0) 1 else -1
    val roadsJ = angles.mapIndexed { i, a -> LaneModel.Road(a, i == 0, if (abs(a) < 25) 4 else 2, if (i == 0) "secondary" else "residential", false, null, 62L + i) }
    val j = LaneModel.Junction(400.0, LanePlan.Kind.JUNCTION, side, lanes, true, roadsJ, null, null, null, 2, 2, 61, "primary", false)
    val roads = listOf(road(61, "primary", 0, lanes.size + 2, line(-600.0 to 0.0, 0.0 to 0.0), lanesF = lanes.size, lanesB = 2),
        road(62, "secondary", 0, 2, line(0.0 to 0.0, *out.toTypedArray())))
    return Case(name, JunctionScene(j, 90.0, 300.0, r, 310.0, 400.0, "IT", null), roads, 375.0, false)
  }

  /** In town: an oblique left turn (55°) onto the SP12 "Via Roma", a road straight on, another one sharp to the right. */
  fun obliqueLeftCase(): Case = townJunction("obliqua_sinistra",
      listOf(LanePlan.Lane("slight left", true, arrows = listOf("slight left")), LanePlan.Lane("straight", false), LanePlan.Lane("straight", false)),
      listOf(-55.0, 0.0, 115.0), drive(0.0, 0.0, 30.0 to -55.0, 250.0 to 0.0))

  /** In town: a sharp right turn (135°) back onto the SP12 "Via Roma", roads straight on and to the left. */
  fun sharpRightCase(): Case = townJunction("stretta_destra",
      listOf(LanePlan.Lane("straight", false), LanePlan.Lane("straight", false), LanePlan.Lane("sharp right", true, arrows = listOf("sharp right"))),
      listOf(135.0, 0.0, -90.0), drive(0.0, 0.0, 30.0 to 135.0, 250.0 to 0.0))

  /** The junctions. */
  fun all(): List<Case> = listOf(exitCase(), forkCase(), townCase(), slightCase(), decelCase(), loopCase(), backCase(), splitAgainCase(),
      obliqueLeftCase(), sharpRightCase())
}
