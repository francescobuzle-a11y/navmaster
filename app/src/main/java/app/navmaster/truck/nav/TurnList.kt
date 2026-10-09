package app.navmaster.truck.nav

import app.navmaster.truck.routing.RouteAnalysis
import uniffi.ferrostar.Route

/** One manoeuvre of the route for the list before departure: where, what to do, which way. */
data class TurnItem(
    /** Metres from the start. */
    val atM: Double,
    val text: String,
    /** Ferrostar's manoeuvre type and modifier names ("OFF_RAMP", "SLIGHT_RIGHT"…), for the arrow. */
    val type: String?,
    val modifier: String?,
    /** Exit number ("12", "7a"), when the sign has one. */
    val exit: String?,
    /** A motorway junction, an exit, a ramp, a fork or a merge: what the driver has to know first. */
    val junction: Boolean,
)

/**
 * The route as a list of turns and junctions (Garmin's "list of turns"), in good Italian, and the
 * chain of the main roads it uses ("A4 → A1 → A14"), so the driver sees at once where it goes.
 */
object TurnList {
  /** The chained second manoeuvre of the route's text ("Poi svolta a sinistra"): not part of this one. */
  private val THEN = Regex("^(poi|quindi|subito dopo|then|and then)\\b", RegexOption.IGNORE_CASE)
  private val DISTANCE = Regex("^(tra|fra|in|entro)\\s+[\\d.,]+\\s*(metri|metro|chilometri|chilometro|km|m)\\b[,]?\\s*", RegexOption.IGNORE_CASE)
  private val JUNCTION_TYPES = listOf("RAMP", "FORK", "MERGE")
  private val EURO_REF = Regex("^E ?\\d+$")

  fun of(route: Route): List<TurnItem> {
    val out = mutableListOf<TurnItem>()
    var at = 0.0
    for (st in route.steps) {
      at += st.distance
      // the instructions of a step are about the manoeuvre at its end
      val p = st.visualInstructions.firstOrNull()?.primaryContent
      val type = p?.maneuverType?.name
      val modifier = p?.maneuverModifier?.name
      val spoken = st.spokenInstructions.minByOrNull { it.triggerDistanceBeforeManeuver }?.text?.let { clean(it) }
      val text = when {
        type?.uppercase()?.contains("ARRIVE") == true -> "Arrivo a destinazione"
        spoken != null -> SpeechIt.maneuver(spoken, type, modifier) ?: continue
        p != null && p.text.isNotBlank() && type?.uppercase()?.let { it.contains("CONTINUE") || it.contains("NEW_NAME") } != true -> p.text.trim()
        else -> continue
      }
      val exit = p?.exitNumbers?.firstOrNull()?.takeIf { it.isNotBlank() }
      val t = type?.uppercase() ?: ""
      val junction = exit != null || JUNCTION_TYPES.any { it in t } ||
          Regex("\\b(uscita|svincolo|autostrada|raccordo|tangenziale|esci)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
      // the same manoeuvre twice in a row (a step split in two): once
      if (out.lastOrNull()?.let { it.text == text && at - it.atM < 50 } == true) continue
      out += TurnItem(at, text, type, modifier, exit, junction)
    }
    return out
  }

  /** The main roads in order, by their number (A1, SS16). */
  fun roads(a: RouteAnalysis, max: Int = 8): List<String> {
    val runs = mutableListOf<Pair<String, Double>>()
    for (e in a.edges.sortedBy { it.startM }) {
      if (!e.isMajor && e.roadClass != "secondary") continue
      // the national number (A1, SS65): the European one (E35) is not what the signs show first
      val ref = e.refs.firstOrNull { !EURO_REF.matches(it) }?.replace(" ", "") ?: continue
      val len = e.endM - e.startM
      val last = runs.lastOrNull()
      if (last != null && last.first == ref) runs[runs.lastIndex] = ref to last.second + len else runs += ref to len
    }
    val out = mutableListOf<String>()
    for ((ref, len) in runs) if (len >= 2000 && out.lastOrNull() != ref) out += ref
    if (out.size <= max) return out
    return out.take(max - 1) + "…" + out.last()
  }

  private fun clean(text: String): String? {
    val sentences = text.trim().split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    val first = sentences.filterNot { THEN.containsMatchIn(it) }.joinToString(" ").replace(DISTANCE, "").trim().trimEnd('.', ' ')
    if (first.isBlank()) return null
    return first.replaceFirstChar { it.uppercase() }
  }
}
