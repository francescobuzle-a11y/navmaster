package app.navmaster.truck.search

/**
 * The rules that make the free search find what a driver types the way Google Maps does, with
 * nothing but the words: pure Kotlin, tested on the computer (src/test, MatchingTest).
 *
 *  - the legal forms and the fillers ("Corneliani Srl", "il Corneliani", "F.lli Rossi & C. snc")
 *    are left out of the search and of the comparison with the names;
 *  - a category word has its synonyms ("gommista": gomme, pneumatici, tyres...), so the label
 *    of the kind, the name and the Overture category all answer;
 *  - a town at the end of what was typed ("Esselunga Mantova", "Scania Cremona") is taken out
 *    and the places are looked for around it;
 *  - a word that is in no name is corrected to the nearest word of the index (one letter wrong,
 *    swapped or missing: "Cornelliani", "mantva");
 *  - the hits are ranked by how well the NAME matches, then by the distance.
 */
object Matching {
  /** Legal forms, articles, prepositions: never searched for, never required in the name. */
  val NOISE: Set<String> = setOf(
      "srl", "srls", "spa", "snc", "sas", "sapa", "scarl", "scpa", "scrl", "sc", "ss", "sa", "sas", "ltd", "gmbh", "ag", "sarl", "sl", "bv",
      "nv", "inc", "llc", "plc", "co", "societa", "soc", "coop", "cooperativa", "group", "gruppo", "holding", "italia", "italy",
      // "s.r.l." / "s.p.a." / "& c." after the normalisation
      "s", "r", "l", "p", "a", "n", "c", "e", "d",
      "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "di", "del", "dello", "della", "dei", "degli", "delle", "da", "dal",
      "dallo", "dalla", "dai", "dagli", "dalle", "ed", "the", "and", "of", "de", "der", "die", "das", "und", "le", "les", "des", "du",
  )

  /** Category words and their synonyms (all in the search form): any one of them answers. */
  private val SYNONYMS: List<Set<String>> = listOf(
      // never a brand ("Euromaster"): a brand is a name, the name decides
      setOf("gommista", "gomme", "gomma", "pneumatici", "pneumatico", "pneus", "tyres", "tyre", "tires", "tire", "vulcanizzatore"),
      setOf("officina", "autofficina", "meccanico", "meccanica", "riparazioni", "riparazione", "repair", "garage", "carrozzeria", "assistenza"),
      setOf("camion", "camions", "truck", "trucks", "autocarri", "autocarro", "industriali", "tir", "mezzi", "pesanti"),
      // the most telling first: a group is tried in this order, [expansions] stops at its cap
      setOf("autotrasporti", "trasporti", "logistica", "logistic", "logistics", "spedizioni", "autotrasporto", "trasporto", "transport",
          "corriere", "cargo", "trucking", "freight", "shipping", "transports", "spedizione", "spedizioniere", "corrieri", "courier"),
      setOf("magazzino", "magazzini", "deposito", "depositi", "warehouse"),
      setOf("distributore", "benzina", "benzinaio", "carburante", "carburanti", "gasolio", "diesel", "fuel", "stazione servizio", "rifornimento"),
      setOf("supermercato", "supermarket", "ipermercato", "iper", "market", "minimarket", "alimentari", "discount"),
      setOf("centro commerciale", "mall", "shopping", "galleria", "outlet"),
      setOf("ospedale", "hospital", "pronto soccorso", "clinica", "policlinico", "spedali"),
      setOf("stazione", "station", "fs", "ferroviaria", "treni", "trenitalia"),
      setOf("aeroporto", "airport", "aeroporti", "scalo"),
      setOf("concessionaria", "concessionario", "concessionarie", "dealer", "vendita", "rivenditore"),
      setOf("ricambi", "ricambio", "parts", "autoricambi", "accessori"),
      setOf("fiera", "fiere", "expo", "esposizione", "fieramilano"),
      setOf("revisioni", "revisione", "collaudo", "collaudi", "motorizzazione"),
      setOf("parcheggio", "parking", "sosta", "area sosta", "autoparco"),
      setOf("ristorante", "trattoria", "osteria", "pizzeria", "ristoro", "locanda"),
      setOf("albergo", "hotel", "motel", "alberghi"),
      setOf("fabbrica", "stabilimento", "industria", "industrie", "industriale", "manifattura", "produzione", "works", "acciaieria", "fonderia", "raffineria"),
      setOf("cantina", "cantine", "winery", "vini"),
      setOf("caseificio", "latteria", "formaggi", "caseifici"),
      setOf("fratelli", "flli", "f lli", "lli"),
      setOf("autolavaggio", "lavaggio", "car wash"),
      setOf("municipio", "comune", "townhall"),
      setOf("macchine agricole", "agricole", "agricola", "trattori", "agraria"),
      setOf("dogana", "customs", "doganale"),
      setOf("porto", "harbour", "port", "terminal", "banchina"),
  )

  private val synonymOf: Map<String, Set<String>> = HashMap<String, Set<String>>().also { m ->
    for (g in SYNONYMS) for (w in g) if (' ' !in w) m[w] = g
  }

  /** The words that count: without the legal forms and the fillers (all of them: the words themselves). */
  fun clean(tokens: List<String>): List<String> {
    // a single letter ("F.lli", "& C.", "S.p.A.") never counts
    val out = tokens.filter { it !in NOISE && !(it.length == 1 && it[0].isLetter()) }
    return if (out.isEmpty()) tokens else out
  }

  /** The synonyms of a word (itself first); only itself when it has none. */
  fun synonyms(token: String): List<String> {
    val g = synonymOf[token] ?: return listOf(token)
    return listOf(token) + g.filter { it != token && ' ' !in it }
  }

  /**
   * The words to search for when nothing answers all of them: one word less, a category word
   * first ("ies raffineria" -> "ies", "officina camion" -> "officina", then "camion"), else the
   * last one; nothing for a single word.
   */
  fun oneWordLess(tokens: List<String>): List<List<String>> {
    if (tokens.size < 2) return emptyList()
    val category = tokens.indices.filter { synonymOf.containsKey(tokens[it]) }
    // "officina camion": first without "camion", then without "officina"
    if (category.isNotEmpty()) return category.reversed().take(2).map { c -> tokens.filterIndexed { i, _ -> i != c } }
    return listOf(tokens.dropLast(1))
  }

  /**
   * The alternative word lists of a query, the typed one first: two neighbouring words as one
   * ("fiera milano" -> "fieramilano", as the name is often written), then one category word
   * replaced by each synonym; at most [max].
   */
  fun expansions(tokens: List<String>, max: Int = 12): List<List<String>> {
    val out = ArrayList<List<String>>()
    out += tokens
    for (i in 0 until tokens.size - 1) {
      val joined = tokens[i] + tokens[i + 1]
      if (joined.length < 6) continue
      val alt = tokens.take(i) + joined + tokens.drop(i + 2)
      if (alt !in out) out += alt
      if (out.size >= max) return out
    }
    for ((i, t) in tokens.withIndex()) {
      for (s in synonyms(t).drop(1)) {
        val alt = tokens.toMutableList()
        alt[i] = s
        if (alt !in out) out += alt
        if (out.size >= max) return out
      }
    }
    return out
  }

  /** Damerau-Levenshtein distance (one swap of two neighbours counts one), capped at [max] + 1. */
  fun editDistance(a: String, b: String, max: Int = 2): Int {
    if (a == b) return 0
    if (kotlin.math.abs(a.length - b.length) > max) return max + 1
    val la = a.length
    val lb = b.length
    var prev2: IntArray? = null
    var prev = IntArray(lb + 1) { it }
    for (i in 1..la) {
      val cur = IntArray(lb + 1)
      cur[0] = i
      var rowMin = cur[0]
      for (j in 1..lb) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2!![j - 2] + 1)
        cur[j] = v
        if (v < rowMin) rowMin = v
      }
      if (rowMin > max) return max + 1
      prev2 = prev
      prev = cur
    }
    return prev[lb]
  }

  /** How many letters may be wrong in a word of this length: none under 5, one up to 8, two beyond. */
  fun allowedErrors(token: String): Int = when {
    token.length < 5 -> 0
    token.length <= 8 -> 1
    else -> 2
  }

  /**
   * The best correction of a misspelt word among [candidates] (the words of the index that start
   * with its first two letters, with how many places use each): the nearest, the commonest among
   * the equally near; null when none is near enough.
   */
  fun correct(token: String, candidates: List<Pair<String, Int>>): String? {
    val max = allowedErrors(token)
    if (max == 0) return null
    var best: String? = null
    var bestD = max + 1
    var bestN = -1
    for ((term, n) in candidates) {
      if (term == token) return token
      val d = editDistance(token, term, max)
      if (d > max) continue
      if (d < bestD || (d == bestD && n > bestN)) {
        best = term
        bestD = d
        bestN = n
      }
    }
    return best
  }

  /**
   * The score of a place against the words typed: the name ([nameScore]); when the name has none
   * of the words, 65 if every word is the kind of the place as typed ("gommista", "stazione": a
   * category search), 50 through a synonym ("pneus", "logistics"), else 0, no hit at all. When
   * nothing but category words were typed, the places of that kind (95) come before the places
   * named so (at most 70): "stazione mantova" is the station, not the restaurant La Stazione.
   */
  fun score(name: String, alt: String?, label: String?, tokens: List<String>): Double {
    val lab = TextNorm.tokens(label)
    val s = nameScore(name, alt, tokens, lab)
    val labelHit = tokens.all { t -> lab.any { w -> w.startsWith(t) } }
    // nothing but category words ("stazione", "officina camion"): the places OF that kind first
    // (95), then a place named with a synonym ("Cremona Fiere" for "fiera", 80), then a place
    // merely NAMED so ("La Stazione", a restaurant: at most 70)
    if (tokens.all { synonymOf.containsKey(it) }) {
      if (labelHit) return 95.0
      val nameWords = clean(TextNorm.tokens(name))
      if (tokens.all { t -> synonyms(t).drop(1).any { syn -> syn in nameWords } }) return 80.0
      if (s > 10.0) return minOf(s, 70.0)
    }
    if (s > 10.0) return s
    // the kind itself as typed ("gommista brescia"): 65, so that the station beats a charging
    // "station" nearer the centre; through a synonym, whole word ("station" in the name, "pneus"
    // for a gommista): 50
    if (labelHit) return 65.0
    val words = lab + clean(TextNorm.tokens(name)) + clean(TextNorm.tokens(alt))
    val category = tokens.all { t -> lab.any { w -> w.startsWith(t) } || synonyms(t).drop(1).any { syn -> syn in words } }
    return if (category) 50.0 else 0.0
  }

  /**
   * The score of a name against the words typed: 100 the name itself, 80 its start, 70 the start
   * of another name of it, 60 every word in the name, 58 every word in the name or the kind
   * ("Cremona Po" for "cremona po centro commerciale"), 55 in the name or another name, 45 inside
   * the words, 30 some words, 10 none.
   */
  fun nameScore(name: String, alt: String?, tokens: List<String>, label: List<String> = emptyList()): Double {
    val n = clean(TextNorm.tokens(name))
    val a = clean(TextNorm.tokens(alt))
    val q = tokens.joinToString(" ")
    val ns = n.joinToString(" ")
    val az = a.joinToString(" ")
    fun startsWithWords(s: List<String>) = s.size >= tokens.size && tokens.indices.all { i ->
      if (i < tokens.size - 1) s[i] == tokens[i] else s[i].startsWith(tokens[i])
    }
    return when {
      ns == q || (az.isNotEmpty() && az == q) -> 100.0
      startsWithWords(n) -> 80.0
      a.isNotEmpty() && startsWithWords(a) -> 70.0
      tokens.all { t -> n.any { it.startsWith(t) } } -> 60.0
      label.isNotEmpty() && tokens.any { t -> n.any { it.startsWith(t) } } &&
          tokens.all { t -> n.any { it.startsWith(t) } || label.any { it.startsWith(t) } } -> 58.0
      tokens.all { t -> n.any { it.startsWith(t) } || a.any { it.startsWith(t) } } -> 55.0
      tokens.all { t -> ns.contains(t) || az.contains(t) } -> 45.0
      tokens.any { t -> n.any { it.startsWith(t) } } -> 30.0
      else -> 10.0
    }
  }

  /** The words that open an address ("via roma 10", "strada cipata"): the streets come before the places then. */
  val STREET_WORDS: Set<String> = setOf("via", "viale", "piazza", "piazzale", "corso", "strada", "vicolo", "largo", "borgo", "contrada", "localita",
      "frazione", "ss", "sp", "sr", "statale", "provinciale", "rue", "avenue", "strasse", "str", "calle", "ulica", "ul")

  /**
   * The penalty of the distance: 0 at the door, about 8 at 1 km, 16 at 3 km, 32 at 15 km, 56 at
   * 100 km: a far "Bar Centrale" never beats the one in town, a company with the exact name is
   * found anywhere.
   */
  fun distancePenalty(km: Double): Double = 8.0 * kotlin.math.log2(1.0 + km.coerceAtLeast(0.0))

  /** The radius (m) of a town of the address book for "<name> <town>": the places of the town and its outskirts. */
  fun townRadius(kind: String): Double = when (kind) {
    "city" -> 15_000.0
    "town" -> 8_000.0
    "village", "suburb" -> 4_500.0
    "quarter" -> 2_500.0
    else -> 2_500.0
  }

  /** A town of the address book that may close what was typed. */
  data class Town(val name: String, val kind: String, val lat: Double, val lon: Double, val pop: Long)

  /**
   * "esselunga mantova" -> the words "esselunga" and the town Mantova. The town is the longest
   * tail (up to three words) that is the whole name of a city, town or village anywhere, of a
   * hamlet or district within 40 km of the driver, or the first word(s) of a town within 80 km
   * ("canneto" for Canneto sull'Oglio); [lookup] gives the towns whose search form starts with
   * those words. Never the whole query (that is a town search, the address book answers it).
   */
  fun splitTown(tokens: List<String>, near: Pair<Double, Double>?, lookup: (String) -> List<Town>): Pair<List<String>, List<Town>> {
    if (tokens.size < 2) return tokens to emptyList()
    for (n in minOf(3, tokens.size - 1) downTo 1) {
      val tail = tokens.takeLast(n)
      val text = tail.joinToString(" ")
      val towns = lookup(text)
      // the towns of that name (Gardone Riviera and Gardone Val Trompia both answer "gardone"): the
      // three best, the big ones and the near ones first
      val best = towns.filter { t ->
        val tn = TextNorm.tokens(t.name)
        if (tn.take(n) != tail) return@filter false
        val km = if (near == null) 0.0 else metres(near.first, near.second, t.lat, t.lon) / 1000.0
        if (tn.size == n) rankOf(t.kind) <= 3 || km <= 40.0
        else rankOf(t.kind) <= 3 && tn.size <= n + 2 && tail.last().length >= 4 && km <= 80.0
      }.sortedWith(compareBy({ rankOf(it.kind) }, { -it.pop })).take(3)
      if (best.isNotEmpty()) return tokens.dropLast(n) to best
    }
    return tokens to emptyList()
  }

  /** Metres between two points, as core.Geo.dist (here too so that the tests need no Android). */
  fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val k = kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2))
    val m = 6_371_000.0 * Math.PI / 180.0
    return kotlin.math.hypot((lon2 - lon1) * m * k, (lat2 - lat1) * m)
  }

  fun rankOf(kind: String): Int = when (kind) { "city" -> 1; "town" -> 2; "village" -> 3; "suburb" -> 3; "quarter" -> 4; "hamlet" -> 4; else -> 5 }

  /** An address rather than a name: "Via Roma 10", "Strada Cipata", "Viale Certosa 150". */
  fun addressLike(title: String): Boolean {
    val t = TextNorm.tokens(title)
    return t.isNotEmpty() && (t.first() in STREET_WORDS || TextNorm.isHouseNumber(t.last()))
  }

  /**
   * Two results that are the same place: the same name (or one inside the other) within 150 m,
   * or any two addresses / any two names within 25 m. A company and the address of its door are
   * both kept: the company by its sign, the address for the house number.
   */
  fun samePlace(aTitle: String, aLat: Double, aLon: Double, bTitle: String, bLat: Double, bLon: Double): Boolean {
    val d = metres(aLat, aLon, bLat, bLon)
    if (d < 25 && addressLike(aTitle) == addressLike(bTitle)) return true
    if (d > 150) return false
    val x = clean(TextNorm.tokens(aTitle)).joinToString(" ")
    val y = clean(TextNorm.tokens(bTitle)).joinToString(" ")
    return x == y || (x.length >= 4 && y.contains(x)) || (y.length >= 4 && x.contains(y))
  }
}
