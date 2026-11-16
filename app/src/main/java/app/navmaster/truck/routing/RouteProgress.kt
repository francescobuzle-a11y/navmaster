package app.navmaster.truck.routing

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * How far the search of the routes on screen has gone, for the bar of the routes panel. The engine
 * cannot say beforehand how long a search takes, so the bar joins two things:
 *  - the phases really done, from the engine's own messages (countries of the trip, crossings looked
 *    for and chosen, route found, alternatives) and from the app (analysis of the route): each one
 *    moves the bar at least to its point;
 *  - the time expected from the distance as the crow flies and the searches before (milliseconds per
 *    km, learnt on this tablet): the bar moves on by itself meanwhile, slower and slower, never to the end
 *    before the result.
 */
object RouteProgress {
  data class State(
      /** What is being done now, said for the driver. */
      val phase: String,
      /** The point the phases done have reached (0…1). */
      val floor: Float,
      val startedAt: Long,
      val expectedMs: Long,
      val done: Boolean = false,
  ) {
    /** The bar at [now] (SystemClock.elapsedRealtime). */
    fun fraction(now: Long = SystemClock.elapsedRealtime()): Float {
      if (done) return 1f
      val t = (now - startedAt).toFloat() / expectedMs.coerceAtLeast(500)
      // up to 85 % at the time expected, then slower and slower toward 97 %
      val byTime = if (t <= 1f) 0.85f * t else 0.85f + 0.12f * (1f - kotlin.math.exp(-(t - 1f) * 1.5f))
      return maxOf(floor, byTime).coerceIn(0f, 0.97f)
    }
  }

  private val _state = MutableStateFlow<State?>(null)
  val state: StateFlow<State?> = _state.asStateFlow()

  /** Ids of the countries and their names (Italia, Austria…), for the messages. */
  @Volatile var names: Map<String, String> = emptyMap()

  private var prefs: android.content.SharedPreferences? = null
  /** Milliseconds per km as the crow flies of the searches so far (first guess: the tablet, 2026-11-16, 600 km in 17 s). */
  private var msPerKm = 30.0
  private var crowKm = 0.0

  fun init(context: Context) {
    prefs = context.getSharedPreferences("route_progress", Context.MODE_PRIVATE)
    msPerKm = prefs?.getFloat("ms_per_km", 30f)?.toDouble() ?: 30.0
  }

  /** A search of routes starts, [crow] km as the crow flies along its points. */
  fun begin(crow: Double) {
    crowKm = crow
    val expected = (crow * msPerKm).toLong().coerceIn(1_500L, 120_000L)
    _state.value = State("Calcolo il percorso…", 0.02f, SystemClock.elapsedRealtime(), expected)
  }

  /** A phase reached ([at]: the bar's least point for it). */
  fun phase(text: String, at: Float) {
    _state.update { s -> if (s == null || s.done) s else s.copy(phase = text, floor = maxOf(s.floor, at)) }
  }

  /** The main route is there: what it took teaches the next expectation. */
  fun routeFound() {
    val s = _state.value ?: return
    if (s.done) return
    val ms = SystemClock.elapsedRealtime() - s.startedAt
    if (crowKm > 20) {
      // a slow average (one odd search does not swing it), within sensible bounds
      msPerKm = (msPerKm * 0.7 + (ms / crowKm) * 0.3).coerceIn(3.0, 200.0)
      prefs?.edit()?.putFloat("ms_per_km", msPerKm.toFloat())?.apply()
    }
    phase("Percorso trovato: controllo limiti, pedaggi, svincoli e strade strette…", 0.88f)
  }

  /** The routes are on screen. */
  fun end() {
    _state.update { it?.copy(phase = "Fatto", done = true) }
  }

  /** The search was stopped or failed. */
  fun clear() {
    _state.value = null
  }

  /** A message of the engine (CrossBorderDirections, alternatives): the phase it means, if any. */
  fun onEngineLog(line: String) {
    if (_state.value?.done != false) return
    val l = line.trim()
    fun name(id: String) = names[id] ?: id.replaceFirstChar { it.uppercase() }
    when {
      l.startsWith("punti nei grafi:") -> {
        val ids = l.substringAfter(":").split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.size > 1) phase("Viaggio tra Paesi: ${ids.joinToString(" → ") { name(it) }}", 0.08f)
      }
      l.startsWith("evita come penalità in:") -> phase("Controllo dove pedaggi o autostrade sono inevitabili…", 0.06f)
      l.startsWith("sequenze ") -> phase("Cerco i valichi di confine migliori…", 0.12f)
      l.contains(" confine ") && l.contains("candidati") -> {
        // "italy-austria-slovakia confine italy/austria: 16 candidati in 5 luoghi"
        val pair = l.substringAfter(" confine ").substringBefore(":").split('/')
        val n = l.substringAfter(": ").substringBefore(" candidati")
        if (pair.size == 2) phase("Valichi ${name(pair[0])} – ${name(pair[1])}: $n da confrontare", 0.2f)
      }
      l.startsWith("due valichi insieme") || l.startsWith("valico scelto") -> phase("Valichi scelti: calcolo i tratti nei Paesi…", 0.55f)
      // after the first choice of the crossings the other sequences of countries are weighed: said so
      l.startsWith("pesi ") -> phase(if ((_state.value?.floor ?: 0f) >= 0.55f) "Provo anche il passaggio per altri Paesi…" else "Confronto i percorsi per i valichi…", 0.45f)
      l.startsWith("viaggio tra Paesi:") -> phase("Percorso trovato: unisco i tratti dei Paesi…", 0.8f)
      l.startsWith("alternativa ") -> phase("Alternativa trovata, ne cerco un'altra…", 0.85f)
    }
  }
}
