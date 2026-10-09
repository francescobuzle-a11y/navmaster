package app.navmaster.truck.nav

import kotlin.math.roundToInt

/**
 * Italian as the voice must say it. The route's words (GhGuide) and the warnings are written for
 * the eye: "2a uscita", "SS 16", "V.le", "3,8 m", "1 km", "su A14, E 55". Read by a synthetic
 * voice they come out wrong ("due a uscita", "esse esse sedici", "uno chilometri"). Here every
 * sentence is turned into what a person would say, before it reaches the voice.
 *
 * Pure Kotlin (no Android), tested on the computer with all the sentences of the app.
 */
object SpeechIt {
  // ---------------------------------------------------------------- numbers and distances

  private val ORD_F = listOf("", "prima", "seconda", "terza", "quarta", "quinta", "sesta", "settima", "ottava", "nona", "decima")
  private val ORD_M = listOf("", "primo", "secondo", "terzo", "quarto", "quinto", "sesto", "settimo", "ottavo", "nono", "decimo")

  fun ordinalF(n: Int): String = ORD_F.getOrNull(n) ?: "$n esima"

  /** A distance as it is said: "80 metri", "350 metri", "un chilometro", "un chilometro e mezzo", "12 chilometri". */
  fun distance(m: Double): String {
    val v = m.coerceAtLeast(0.0)
    return when {
      v < 100 -> "${((v / 10).roundToInt() * 10).coerceAtLeast(10)} metri"
      v < 950 -> "${(v / 50).roundToInt() * 50} metri"
      v < 10_000 -> {
        val halves = (v / 500).roundToInt()
        val whole = halves / 2
        val half = halves % 2 == 1
        when {
          whole == 1 && !half -> "un chilometro"
          whole == 1 -> "un chilometro e mezzo"
          half -> "$whole chilometri e mezzo"
          else -> "$whole chilometri"
        }
      }
      else -> "${(v / 1000).roundToInt()} chilometri"
    }
  }

  /** "3,8" metres → "3 metri e 80"; "4" → "4 metri". */
  private fun metresWords(intPart: String, dec: String?): String {
    val i = intPart.toInt()
    val head = if (i == 1) "un metro" else "$i metri"
    val d = dec?.trimEnd('0')?.takeIf { it.isNotEmpty() } ?: return head
    val cm = (d + "0").take(2).toInt()
    return "$head e $cm"
  }

  private fun tonnesWords(intPart: String, dec: String?): String {
    val d = dec?.trimEnd('0')?.takeIf { it.isNotEmpty() }
    return if (d == null) (if (intPart == "1") "una tonnellata" else "$intPart tonnellate") else "$intPart virgola $d tonnellate"
  }

  // ---------------------------------------------------------------- words

  private fun rx(p: String) = Regex(p)

  private fun rxi(p: String) = Regex(p, RegexOption.IGNORE_CASE)

  /** Abbreviations of the street names, as written on the maps. */
  private val ABBREVIATIONS: List<Pair<Regex, String>> =
      listOf(
          rxi("\\bV\\.\\s?le\\b\\.?") to "Viale",
          rxi("\\bP\\.\\s?zz?a\\b\\.?") to "Piazza",
          rxi("\\bP\\.\\s?le\\b\\.?") to "Piazzale",
          rxi("\\bC\\.\\s?so\\b\\.?") to "Corso",
          rxi("\\bL\\.\\s?go\\b\\.?") to "Largo",
          rxi("\\bL\\.\\s?re\\b\\.?") to "Lungomare",
          rxi("\\bV\\.\\s?lo\\b\\.?") to "Vicolo",
          rxi("\\bF\\.\\s?lli\\b\\.?") to "Fratelli",
          rx("\\bStr\\.") to "Strada",
          rx("\\bLoc\\.") to "Località",
          rx("\\bFraz\\.") to "Frazione",
          rx("\\bNaz\\.") to "Nazionale",
          rx("\\bProv\\.") to "Provinciale",
          rx("\\bTang\\.") to "Tangenziale",
          rx("\\bGen\\.") to "Generale",
          rx("\\bDott\\.") to "Dottor",
          rx("\\bProf\\.") to "Professor",
          rx("\\bMons\\.") to "Monsignor",
          rx("\\bCav\\.") to "Cavalier",
          rx("\\bAv\\.") to "Avenue",
      )

  /** "S. Maria" → "Santa Maria", "S. Andrea" → "Sant'Andrea", "S. Stefano" → "Santo Stefano", "S. Marco" → "San Marco". */
  private val SAINT = rx("\\bS(?:\\.|ta\\.|to\\.)\\s?([A-ZÀ-Ý][a-zà-ÿ']+)")

  private fun saint(name: String): String {
    val c0 = name[0].lowercaseChar()
    val c1 = name.getOrNull(1)?.lowercaseChar() ?: 'a'
    return when {
      c0 in "aeiouàèéìòù" -> "Sant'$name"
      (c0 == 's' && c1 !in "aeiou") || c0 == 'z' -> "Santo $name"
      name.endsWith("a") && name !in setOf("Luca", "Andrea", "Nicola", "Elia", "Mattia", "Battista") -> "Santa $name"
      else -> "San $name"
    }
  }

  /**
   * The road numbers: "SS 16" → "Statale 16", "SP12" → "Provinciale 12", "RA 11" → "Raccordo 11",
   * "A14, E 55" → "A14" (the European number is not said next to the national one), "A 14" → "A14".
   */
  private val REFS: List<Pair<Regex, String>> =
      listOf(
          rx("\\bS\\.?\\s?S\\.?\\s?(\\d+[a-zA-Z]?)\\b") to "Statale $1",
          rx("\\bS\\.?\\s?P\\.?\\s?(\\d+[a-zA-Z]?)\\b") to "Provinciale $1",
          rx("\\bS\\.?\\s?R\\.?\\s?(\\d+[a-zA-Z]?)\\b") to "Regionale $1",
          rx("\\bS\\.?\\s?C\\.?\\s?(\\d+[a-zA-Z]?)\\b") to "Comunale $1",
          rx("\\bS\\.?\\s?G\\.?\\s?C\\.?\\s?(\\d+)\\b") to "Grande Comunicazione $1",
          rx("\\bRA\\s?(\\d+)\\b") to "Raccordo $1",
          rx("\\bNSA\\s?(\\d+)\\b") to "Nuova Statale $1",
          rx("\\bA\\s(\\d{1,2})\\b") to "A$1",
      )
  private val EUROPEAN_NEXT = rx("(\\b(?:A\\d{1,2}|Statale \\d+\\w?|Raccordo \\d+))\\s*[,;/]\\s*E\\s?\\d{1,3}\\b")
  private val EUROPEAN_BEFORE = rx("\\bE\\s?\\d{1,3}\\s*[,;/]\\s*(A\\d{1,2}|Statale \\d+\\w?)")

  /** The prepositions before a road: "su A14" → "sull'A14", "su Statale 16" → "sulla Statale 16", "su Via Roma" → "in Via Roma". */
  private val PREP: List<Pair<Regex, String>> =
      listOf(
          rx("\\bsu (A\\d)") to "sull'$1",
          rx("\\bverso (A\\d)") to "verso l'$1",
          rx("\\bper (A\\d)") to "per l'$1",
          rx("\\bin (A\\d)") to "sull'$1",
          rx("\\bsu (Statale|Provinciale|Regionale|Comunale|Tangenziale|Strada|Superstrada|Autostrada|Circonvallazione|Variante|Nuova Statale|Grande Comunicazione|Complanare|Rampa|Bretella|Litoranea|Provinciale)\\b") to "sulla $1",
          rx("\\bverso (Statale|Provinciale|Regionale|Comunale|Tangenziale|Superstrada|Autostrada|Circonvallazione|Variante)\\b") to "verso la $1",
          rx("\\bper (Statale|Provinciale|Regionale|Comunale|Tangenziale|Superstrada|Autostrada|Circonvallazione|Variante)\\b") to "per la $1",
          rx("\\bsu (Raccordo|Viadotto|Ponte|Lungomare|Lungofiume|Lungotevere|Passante)\\b") to "sul $1",
          rx("\\bsu (Via|Viale|Vicolo|Corso|Piazza|Piazzale|Largo|Contrada|Località|Frazione|Borgo|Salita|Discesa|Galleria)\\b") to "in $1",
          rx("\\bper (Via|Viale|Vicolo|Corso|Piazza|Piazzale|Largo|Contrada|Borgo)\\b") to "in $1",
      )

  /** Numbers with their unit, written for the eye. */
  private val UNITS: List<Pair<Regex, (MatchResult) -> String>> =
      listOf(
          rxi("\\b(\\d+)(?:,(\\d+))?\\s?km/h\\b") to { m -> "${m.groupValues[1]} chilometri orari" },
          rx("\\b(\\d+),5\\s?km\\b") to { m -> m.groupValues[1].toInt().let { if (it == 1) "un chilometro e mezzo" else "$it chilometri e mezzo" } },
          rx("\\b(\\d+),(\\d)\\s?km\\b") to { m -> "${m.groupValues[1]} virgola ${m.groupValues[2]} chilometri" },
          rx("\\b(\\d+)\\s?km\\b") to { m -> if (m.groupValues[1] == "1") "un chilometro" else "${m.groupValues[1]} chilometri" },
          rx("\\b(\\d+)(?:,(\\d{1,2}))?\\s?m\\b(?!\\w)") to { m -> metresWords(m.groupValues[1], m.groupValues[2].ifEmpty { null }) },
          rx("\\b(\\d+)(?:,(\\d{1,2}))?\\s?t\\b(?!\\w)") to { m -> tonnesWords(m.groupValues[1], m.groupValues[2].ifEmpty { null }) },
          rx("\\b(\\d+)\\s?min\\b\\.?") to { m -> if (m.groupValues[1] == "1") "un minuto" else "${m.groupValues[1]} minuti" },
          rx("\\b(\\d+)\\s?h\\b") to { m -> if (m.groupValues[1] == "1") "un'ora" else "${m.groupValues[1]} ore" },
      )

  /** Agreement of "1": "1 chilometri" → "un chilometro", "1 ore" → "un'ora"… (left by sentences built elsewhere). */
  private val ONE: List<Pair<Regex, String>> =
      listOf(
          rx("\\b1 chilometri\\b") to "un chilometro",
          rx("\\b1 metri\\b") to "un metro",
          rx("\\b1 minuti\\b") to "un minuto",
          rx("\\b1 ore\\b") to "un'ora",
          rx("\\b1 tonnellate\\b") to "una tonnellata",
          rx("\\b0 ore e\\s") to "",
      )

  /** "la 2a uscita", "la 2ª uscita", "la 2° uscita" → "la seconda uscita". */
  private val ORDINAL_EXIT = rxi("\\b(\\d{1,2})\\s?(?:a|ª|°|º|\\^)\\s+(uscita|strada|traversa|svolta|rampa)")
  private val ORDINAL_M = rx("\\b(\\d{1,2})\\s?[°º]\\s+([a-z])")

  /**
   * Any sentence made ready for the voice: street abbreviations, road numbers, prepositions,
   * units, ordinals; one word for the roundabout ("rotonda"); no double spaces or dots.
   */
  fun normalize(text: String): String {
    var s = " " + text.trim() + " "
    s = s.replace(' ', ' ').replace(Regex("\\s+"), " ")
    for ((r, w) in ABBREVIATIONS) s = r.replace(s, w)
    s = SAINT.replace(s) { saint(it.groupValues[1]) }
    for ((r, w) in REFS) s = r.replace(s, w)
    s = EUROPEAN_NEXT.replace(s, "$1")
    s = EUROPEAN_BEFORE.replace(s, "$1")
    s = ORDINAL_EXIT.replace(s) { m -> "${ordinalF(m.groupValues[1].toInt())} ${m.groupValues[2].lowercase()}" }
    s = ORDINAL_M.replace(s) { m -> "${ORD_M.getOrNull(m.groupValues[1].toInt()) ?: m.groupValues[1]} ${m.groupValues[2]}" }
    for ((r, f) in UNITS) s = r.replace(s, f)
    for ((r, w) in ONE) s = r.replace(s, w)
    s = s.replace(Regex("\\b[Rr]otatoria\\b")) { if (it.value[0] == 'R') "Rotonda" else "rotonda" }
    s = s.replace(Regex("\\b(nella|alla|dalla|della) [Rr]otonda\\b")) { it.value.lowercase() }
    for ((r, w) in PREP) s = r.replace(s, w)
    // "Continua sull'A14 ." → "Continua sull'A14."
    s = s.replace(Regex("\\s+([.,;:!?])"), "$1").replace(Regex("([.,;:!?])\\1+"), "$1").replace(Regex(",\\s*\\."), ".")
    s = s.replace(Regex("\\s+"), " ").trim()
    return s
  }

  // ---------------------------------------------------------------- the manoeuvres

  private val ROUNDABOUT = rxi("^(?:entra (?:nella|in) (?:rotonda|rotatoria)(?: ([^,]+?))?|alla (?:rotonda|rotatoria)(?: ([^,]+?))?,?) e prendi la (\\S+) uscita(?: (?:per|verso|su|in) (.+))?$")
  private val TAKE_EXIT = rxi("^prendi l'uscita (?:verso |per )?(.+)$")
  private val TAKE_RAMP = rxi("^prendi (?:lo svincolo|la rampa)(?: (?:a|sulla) (destra|sinistra))?(?: (?:verso|per) (.+))?$")
  private val VERB = rxi("^(imboccare|prendere|rimanere su(?:lla|l)?|restare su(?:lla|l)?|rimanere in|restare in)\\s+(.+)$")

  /** "A1" → "l'A1", "E35" / "SS16" / "Statale 16" → "la …", a street stays as it is. */
  private fun withArticle(road: String): String = when {
    Regex("^A\\d").containsMatchIn(road) -> "l'$road"
    Regex("^(E|SS|SP|SR|SGC|RA|S[A-Z]?\\d)").containsMatchIn(road) || Regex("^(Statale|Provinciale|Regionale|Tangenziale|Superstrada|Autostrada|Strada)\\b").containsMatchIn(road) -> "la $road"
    else -> road
  }

  /** "l'A1" → "sull'A1", "la E35" → "sulla E35", "Via Roma" → "in Via Roma". */
  private fun onRoad(road: String): String = when {
    road.startsWith("l'") -> "sull'" + road.removePrefix("l'")
    road.startsWith("la ") -> "sulla " + road.removePrefix("la ")
    Regex("^(Via|Viale|Corso|Piazza|Largo|Vicolo|Piazzale)\\b").containsMatchIn(road) -> "in $road"
    else -> "su $road"
  }

  private val KEEP = rxi("^(?:mantieni|tieni|resta)(?: la| sulla)? (destra|sinistra)(?: al bivio)?(?: (?:verso|per|su) (.+))?$")
  private val TURN_ON = rxi("^(svolta|gira|curva)( leggermente| decisamente| bruscamente)? a (destra|sinistra) (?:su|in) (.+)$")
  private val TURN_TO = rxi("^(svolta|gira|curva)( leggermente| decisamente| bruscamente)? a (destra|sinistra) (?:verso|per) (.+)$")

  /** The places or road after "verso": "Ancona" → "in direzione Ancona"; a road stays a road. */
  private fun toward(t: String): String {
    // the route's text: "Mantieni la sinistra per imboccare A1", "… per rimanere su E 35"
    VERB.find(t.trim())?.let { m ->
      val road = withArticle(normalize(fewPlaces(m.groupValues[2])).replace(Regex("\\b([A-Z]{1,3}) (\\d{1,4})\\b"), "$1$2"))
      val stay = m.groupValues[1].lowercase().let { it.startsWith("riman") || it.startsWith("rest") }
      return if (stay) "per restare " + onRoad(road) else "per imboccare $road"
    }
    val n = normalize(fewPlaces(t))
    return if (Regex("^(Via|Viale|Corso|Piazza|Largo|Vicolo|Piazzale)\\b").containsMatchIn(n)) "in $n"
    else if (Regex("^(A\\d)").containsMatchIn(n)) "verso l'$n"
    else if (Regex("^(Statale|Provinciale|Regionale|Tangenziale|Superstrada|Autostrada)\\b").containsMatchIn(n)) "verso la $n"
    else "in direzione $n"
  }

  /**
   * One manoeuvre in good Italian, from the route's own sentence and, when known, which way it
   * goes ([modifier]: "RIGHT", "SLIGHT_LEFT"…). Returns null for what is not said at all (the
   * exit of a roundabout already announced with its entry, "Continua" on the same road).
   */
  fun maneuver(text: String, type: String? = null, modifier: String? = null): String? {
    val raw = text.trim().trimEnd('.', ' ')
    if (raw.isEmpty()) return null
    val t = (type ?: "").uppercase()
    val side = when {
      modifier == null -> null
      "LEFT" in modifier.uppercase() -> "sinistra"
      "RIGHT" in modifier.uppercase() -> "destra"
      else -> null
    }
    // not said: leaving a roundabout, "Continua su …" straight on, a change of name
    if (Regex("^esci dalla (rotatoria|rotonda)", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return null
    if (("CONTINUE" in t || "NEW_NAME" in t || "NOTIFICATION" in t) && side == null) return null
    ROUNDABOUT.find(raw)?.let { m ->
      val name = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
      val nth = m.groupValues[3].let { o -> o.filter { it.isDigit() }.toIntOrNull()?.let { ordinalF(it) } ?: o }
      val where = m.groupValues[4].trim().takeIf { it.isNotEmpty() }?.let { ", " + toward(it) } ?: ""
      val at = if (name.isNotEmpty() && !name.equals("rotonda", true)) "Alla rotonda ${normalize(name).removePrefix("Rotonda ").removePrefix("Rotatoria ")}" else "Alla rotonda"
      return normalize("$at prendi la $nth uscita$where")
    }
    TAKE_EXIT.find(raw)?.let { m ->
      val what = normalize(fewPlaces(m.groupValues[1]))
      val lead = if (side != null) "Esci a $side" else "Esci"
      // "uscita Riccione", "uscita 7, Riccione"
      return "$lead, uscita $what"
    }
    TAKE_RAMP.find(raw)?.let { m ->
      val s = m.groupValues[1].ifEmpty { side ?: "" }
      val where = m.groupValues[2].takeIf { it.isNotBlank() }?.let { " " + toward(it) } ?: ""
      return if (s.isNotEmpty()) "Tieni la $s$where" else "Prendi lo svincolo$where"
    }
    KEEP.find(raw)?.let { m ->
      val where = m.groupValues[2].takeIf { it.isNotBlank() }?.let { " " + toward(it) } ?: ""
      return "Tieni la ${m.groupValues[1].lowercase()}$where"
    }
    TURN_ON.find(raw)?.let { m ->
      val how = m.groupValues[2].trim()
      val road = normalize("su " + m.groupValues[4])
      return "Svolta${if (how.isNotEmpty()) " $how" else ""} a ${m.groupValues[3].lowercase()} $road".replace("su ", "in ").let { fixRoadPrep(it) }
    }
    TURN_TO.find(raw)?.let { m ->
      val how = m.groupValues[2].trim()
      return "Svolta${if (how.isNotEmpty()) " $how" else ""} a ${m.groupValues[3].lowercase()} ${toward(m.groupValues[4])}"
    }
    return normalize(raw)
  }

  /**
   * The essential: at most two places of a sign ("Bologna/Firenze/Roma/Ancona" → "Bologna e
   * Firenze"); a slash would be read out as "barra".
   */
  fun fewPlaces(t: String): String {
    val parts = t.split(Regex("\\s*[/;|]\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
    return parts.take(2).joinToString(" e ")
  }

  private val WHERE = Regex(",\\s*(?:uscita|in direzione|verso)\\b|\\s(?:in direzione|verso)\\s")

  /**
   * The manoeuvre without where it goes, for the moment it is due (the place was said with the
   * preparation): "Esci a destra, uscita Riccione" → "Esci a destra"; "Tieni la sinistra verso
   * l'A14" → "Tieni la sinistra". A turn into a street keeps the street.
   */
  fun short(text: String, dropRoad: Boolean = false): String {
    var t = text.trim().trimEnd('.', ' ')
    WHERE.find(t)?.let { cut -> if (cut.range.first >= 8) t = t.substring(0, cut.range.first).trim().trimEnd(',') }
    // the road too, when it was said with the preparation: "Svolta a destra in Via Roma" → "Svolta a destra"
    if (dropRoad && TURN_LEAD.containsMatchIn(t)) {
      ROAD.find(t)?.let { cut -> if (cut.range.first >= 8) t = t.substring(0, cut.range.first).trim().trimEnd(',') }
    }
    return t
  }

  private val TURN_LEAD = Regex("^(Svolta|Gira|Curva|Tieni|Esci|Mantieni)\\b")
  private val ROAD = Regex("\\s(?:in |sulla |sul |sull')(?=[A-Z0-9À-Ý])")

  /** After a turn: "in Via Roma", "sulla Statale 16", "sull'A14", "in direzione Rimini" for a bare place. */
  private fun fixRoadPrep(s: String): String =
      s.replace(Regex("\\bin sulla\\b"), "sulla").replace(Regex("\\bin sull'"), "sull'").replace(Regex("\\bin sul\\b"), "sul")
          .replace(Regex("\\bin in\\b"), "in")
}
