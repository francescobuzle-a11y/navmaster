package app.navmaster.truck.nav

/**
 * Which lanes to keep for the next manoeuvre, said plainly: the lane guidance that goes with the
 * driver on motorways and on roads with several lanes (from 2 km before a motorway exit, a fork or
 * a merge, from 400 m before a junction in town where only some lanes go the right way).
 *
 * Where the lanes come from, most precise first:
 *  1. the route's own lanes (OpenStreetMap turn:lanes, read by Valhalla): exactly the lanes of the
 *     junction and which ones go the route's way;
 *  2. at motorway exits, forks and merges without turn:lanes: the number of lanes of the road from
 *     the map and the lanes of the road taken, the exit on its own side (as it is built);
 *  3. nothing certain: no lanes drawn, only the side to keep (never lanes made up).
 *
 * Pure Kotlin, tested on the computer.
 */
object LanePlan {
  /** A lane of the route ([dirs] as Valhalla writes them: "straight", "slight right"…). */
  data class In(val dirs: List<String>, val active: Boolean, val activeDir: String?)

  /** A lane to draw: its arrow, whether to take it, and whether it is the lane of a merge. */
  data class Lane(val arrow: String, val take: Boolean, val merge: Boolean = false)

  enum class Kind { EXIT, FORK, MERGE, JUNCTION }

  data class Plan(
      val lanes: List<Lane>,
      val kind: Kind,
      /** Side of the manoeuvre: -1 left, 1 right, 0 straight on. */
      val side: Int,
      /** True when the lanes are the route's own (turn:lanes), false when worked out from the map. */
      val fromRoute: Boolean,
      /** What to keep, in a few words for the screen ("Corsia di destra"). */
      val hint: String,
      /** The same for the voice, or null when nothing needs saying (two lanes, an obvious exit). */
      val say: String?,
  ) {
    val label: String
      get() = when (kind) {
        Kind.EXIT -> if (side < 0) "USCITA A SINISTRA" else "USCITA A DESTRA"
        Kind.FORK -> when {
          side < 0 -> "BIVIO: TIENI LA SINISTRA"
          side > 0 -> "BIVIO: TIENI LA DESTRA"
          else -> "BIVIO"
        }
        Kind.MERGE -> "IMMISSIONE"
        Kind.JUNCTION -> "INCROCIO"
      }
  }

  private fun sideOf(modifier: String): Int {
    val m = modifier.uppercase()
    return when {
      "LEFT" in m -> -1
      "RIGHT" in m -> 1
      else -> 0
    }
  }

  /**
   * The plan for a manoeuvre: [type]/[modifier] of the instruction ("OFF_RAMP"/"SLIGHT_RIGHT"),
   * [lanes] of the route at the junction, [roadLanes] of the road before it (map), [outLanes] of
   * the road taken, [motorway] when on (or entering) a motorway. Null when there is nothing to show.
   */
  fun of(lanes: List<In>, type: String, modifier: String, roadLanes: Int?, outLanes: Int?, motorway: Boolean): Plan? {
    val t = type.uppercase().replace("_", "")
    if ("ARRIVE" in t || "DEPART" in t || "ROUNDABOUT" in t || "ROTARY" in t) return null
    val side = sideOf(modifier)
    val kind = when {
      "OFFRAMP" in t -> Kind.EXIT
      "ONRAMP" in t -> if (motorway) Kind.EXIT else Kind.FORK
      "FORK" in t -> Kind.FORK
      "MERGE" in t -> Kind.MERGE
      else -> Kind.JUNCTION
    }
    // 1. the lanes of the route, when only some of them go the right way
    val n0 = lanes.size
    val taken0 = lanes.count { it.active }
    if (n0 >= 2 && taken0 in 1 until n0 && n0 <= 8) {
      val out = lanes.map { l ->
        val arrow = (if (l.active) l.activeDir ?: l.dirs.firstOrNull() else l.dirs.firstOrNull()) ?: "straight"
        Lane(arrow.lowercase().replace('_', ' '), l.active)
      }
      return Plan(out, kind, side, true, hint(out), say(out, kind))
    }
    if (kind == Kind.JUNCTION) return null
    // 2. motorway exits, forks and merges: the lanes of the map
    val n = roadLanes?.takeIf { it in 2..6 }
    if (kind == Kind.MERGE) {
      // joining a motorway from its slip road: the slip road ends on the side opposite to the
      // modifier ("merge slightly left" = the ramp is on the right) into the outer lane
      val rampSide = if (side == 0) 1 else -side
      val main = (outLanes?.takeIf { it in 1..6 } ?: n)?.coerceIn(1, 6)
      if (main == null) {
        return Plan(emptyList(), kind, side, false, if (rampSide > 0) "Entra nella corsia di destra" else "Entra nella corsia di sinistra", null)
      }
      val lanesM = (0 until main).map { i ->
        val outer = if (rampSide > 0) i == main - 1 else i == 0
        Lane("straight", outer)
      }
      val ramp = Lane(if (rampSide > 0) "slight left" else "slight right", true, merge = true)
      val all = if (rampSide > 0) lanesM + ramp else listOf(ramp) + lanesM
      return Plan(all, kind, side, false, if (rampSide > 0) "Entra nella corsia di destra" else "Entra nella corsia di sinistra", null)
    }
    if (side == 0) return null
    val sideWord = if (side > 0) "destra" else "sinistra"
    if (n == null) {
      // the number of lanes is not known: only the side, no lanes made up
      return Plan(emptyList(), kind, side, false, "Tieni la $sideWord", null)
    }
    // the exit leaves from the outer lane (two when the ramp has two lanes and the road at least three)
    val k = (outLanes ?: 1).coerceIn(1, if (n >= 3) 2 else 1)
    val arrowOut = if (side > 0) "slight right" else "slight left"
    val out = (0 until n).map { i ->
      val take = if (side > 0) i >= n - k else i < k
      Lane(if (take) arrowOut else "straight", take)
    }
    return Plan(out, kind, side, false, hint(out), say(out, kind))
  }

  /** "Corsia di destra", "Le 2 corsie di sinistra", "Corsia centrale", "Corsie centrali". */
  fun hint(lanes: List<Lane>): String {
    val n = lanes.size
    val idx = lanes.indices.filter { lanes[it].take }
    if (idx.isEmpty() || idx.size == n) return ""
    val k = idx.size
    val contiguous = idx.last() - idx.first() + 1 == k
    return when {
      !contiguous -> "Corsie evidenziate"
      idx.last() == n - 1 -> if (k == 1) "Corsia di destra" else "Le $k corsie di destra"
      idx.first() == 0 -> if (k == 1) "Corsia di sinistra" else "Le $k corsie di sinistra"
      else -> if (k == 1) "Corsia centrale" else "Corsie centrali"
    }
  }

  /**
   * What the voice adds after the manoeuvre, only where a lane can be missed: three lanes or more,
   * or the lanes to take are not the obvious ones (the exit side). Null when it goes without saying.
   */
  fun say(lanes: List<Lane>, kind: Kind): String? {
    val n = lanes.count { !it.merge }
    val idx = lanes.indices.filter { lanes[it].take && !lanes[it].merge }
    if (idx.isEmpty() || idx.size == n) return null
    val k = idx.size
    val contiguous = idx.last() - idx.first() + 1 == k
    if (!contiguous) return null
    val right = idx.last() == n - 1
    val left = idx.first() == 0
    if (n <= 2 && (kind == Kind.EXIT || kind == Kind.FORK)) return null
    return when {
      right -> if (k == 1) "usa la corsia di destra" else "usa le $k corsie di destra"
      left -> if (k == 1) "usa la corsia di sinistra" else "usa le $k corsie di sinistra"
      else -> if (k == 1) "usa la corsia centrale" else "usa le corsie centrali"
    }
  }
}
