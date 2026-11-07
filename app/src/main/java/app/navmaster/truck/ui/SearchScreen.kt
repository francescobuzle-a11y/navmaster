package app.navmaster.truck.ui

import androidx.compose.foundation.background
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.AppGraph
import app.navmaster.truck.data.InstalledRegion
import app.navmaster.truck.search.Found
import app.navmaster.truck.search.Geocoder
import app.navmaster.truck.search.TextNorm
import app.navmaster.truck.search.HouseHit
import app.navmaster.truck.search.PlaceHit
import app.navmaster.truck.search.StreetHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uniffi.ferrostar.GeographicCoordinate

/**
 * Destination search in two ways:
 *  - guided (iGO / Garmin): country, town, street, house number, one step at a time;
 *  - free text (Google Maps): anything typed, offline first and online when there is a connection.
 */
@Composable
fun SearchScreen(near: GeographicCoordinate?, onPick: (Found) -> Unit, onClose: () -> Unit, startGuided: Boolean = false) {
  var guided by remember { mutableStateOf(startGuided) }
  // the mode buttons and the field stay put, only the results scroll: with the keyboard open the
  // panel sits above it and every result can be reached
  AdaptiveSheet("Dove andiamo?", onClose, wide = true, scroll = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Pill("🔎 Ricerca libera", !guided) { guided = false }
      Pill("🧭 Paese › Città › Via", guided) { guided = true }
    }
    Spacer(Modifier.height(12.dp))
    val pick = { f: Found ->
      AppGraph.recents.add(f)
      onPick(f)
    }
    if (guided) GuidedSearch(pick) else FreeSearch(near, pick)
  }
}

/** The results under a search field: they scroll, and scrolling them puts the keyboard away. */
@Composable
private fun ColumnScope.Results(content: @Composable ColumnScope.() -> Unit) {
  val kb = LocalSoftwareKeyboardController.current
  val scroll = rememberScrollState()
  LaunchedEffect(scroll.isScrollInProgress) { if (scroll.isScrollInProgress) kb?.hide() }
  Column(Modifier.weight(1f, fill = false).verticalScroll(scroll), content = content)
}

@Composable
private fun field(value: String, onChange: (String) -> Unit, hint: String, focus: FocusRequester? = null) {
  val kb = LocalSoftwareKeyboardController.current
  OutlinedTextField(
      value, onChange, placeholder = { Text(hint, fontSize = 18.sp) }, singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { kb?.hide() }, onDone = { kb?.hide() }),
      leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Nm.Muted) },
      textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, color = Nm.Text),
      modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
      shape = RoundedCornerShape(18.dp),
      colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Nm.Accent, unfocusedBorderColor = Nm.Line, cursorColor = Nm.Accent),
  )
}

@Composable
private fun ResultRow(icon: String, title: String, detail: String?, trailing: String? = null, onClick: () -> Unit) {
  Row(
      Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
          .padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(icon, fontSize = 24.sp, modifier = Modifier.width(40.dp))
    Column(Modifier.weight(1f)) {
      Text(title, color = Nm.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (!detail.isNullOrBlank()) Caption(detail, lines = 1)
    }
    if (trailing != null) Caption(trailing, color = Nm.Muted)
    Icon(Icons.Rounded.ChevronRight, null, tint = Nm.Muted)
  }
}

@Composable
private fun ColumnScope.FreeSearch(near: GeographicCoordinate?, onPick: (Found) -> Unit) {
  var q by remember { mutableStateOf("") }
  var offline by remember { mutableStateOf<List<Found>>(emptyList()) }
  var online by remember { mutableStateOf<List<Found>>(emptyList()) }
  var busy by remember { mutableStateOf(false) }
  // what was really searched when a word was corrected ("Esselunga mantva" -> "esselunga mantova")
  var corrected by remember { mutableStateOf<String?>(null) }
  val focus = remember { FocusRequester() }
  val recents by AppGraph.recents.items.collectAsState()
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  LaunchedEffect(q) {
    online = emptyList()
    if (q.trim().length < 2) {
      offline = emptyList()
      return@LaunchedEffect
    }
    delay(250)
    busy = true
    offline = withContext(Dispatchers.IO) {
      val coord = Geocoder.parseCoordinates(q)
      if (coord != null) listOf(Found("Coordinate", "${coord.lat}, ${coord.lng}", coord, "📌"))
      else {
        // a word that is in no name of the tablet corrected to the nearest one ("Cornelliani", "mantva")
        val fixed = runCatching { AppGraph.places.corrected(Geocoder.fixQuery(q), AppGraph.addresses) }.getOrDefault(Geocoder.fixQuery(q))
        corrected = fixed.takeIf { TextNorm.norm(it) != TextNorm.norm(Geocoder.fixQuery(q)) }
        val addr = runCatching { AppGraph.addresses.free(fixed, near) }.getOrDefault(emptyList())
        // every named place of the country (companies, shops, workshops, hotels ... luoghi.sqlite)
        val places = runCatching { AppGraph.places.search(fixed, near, AppGraph.addresses) }.getOrDefault(emptyList())
        val pois = runCatching { AppGraph.poi.search(q) }.getOrDefault(emptyList()).map { p ->
          Found(p.title, (app.navmaster.truck.settings.PoiCategories.byId(p.cat)?.label ?: p.cat), p.coordinate,
              app.navmaster.truck.settings.PoiCategories.byId(p.cat)?.icon ?: "📍",
              near?.let { app.navmaster.truck.core.Geo.dist(it.lat, it.lng, p.lat, p.lon) })
        }.sortedBy { it.distanceM ?: 0.0 }.take(8).filter { x -> places.none { app.navmaster.truck.core.Geo.dist(it.coordinate, x.coordinate) < 60 } }
        // the town of that name first ("Verona": the city, not the Hotel Verona nor the station),
        // then the places that answer every word typed (a company by its name, "Esselunga
        // Mantova", the tyre shops for "gommista Brescia"), then the streets, then the places
        // that answer only in part; with a house number typed the addresses first
        // the town typed, alone or with its parent ("Lunetta Mantova": the district, then its parent and county are in the detail)
        val qt = TextNorm.tokens(fixed)
        val (towns, addrRest) = addr.partition { it.icon == "🏙" && TextNorm.tokens(it.title + " " + it.detail).take(qt.size) == qt }
        val (strong, weak) = places.partition { it.match >= 50.0 }
        val address = qt.any { TextNorm.isHouseNumber(it) } || qt.firstOrNull() in app.navmaster.truck.search.Matching.STREET_WORDS
        (if (address) towns + addrRest + strong + weak + pois else towns + strong + addrRest + weak + pois)
      }
    }
    // online results complete what is not on the tablet (other countries, shops, companies ...):
    // the tablet's geocoder and Photon first; HERE (the companies, a counted allowance) only once
    // the typing has paused for a while, so a word typed letter by letter costs one request
    delay(400)
    online = runCatching { Geocoder.search(q, near, withHere = false) }.getOrDefault(emptyList())
    busy = false
    if (q.trim().length >= 4) {
      delay(900)
      val withHere = runCatching { Geocoder.search(q, near, withHere = true) }.getOrDefault(emptyList())
      if (withHere.isNotEmpty()) online = withHere
    }
  }
  field(q, { q = it }, "Via, città, azienda o coordinate", focus)
  Spacer(Modifier.height(8.dp))
  Results {
    if (q.isBlank()) {
      if (recents.isNotEmpty()) SectionHeader("Recenti")
      for (r in recents) ResultRow("🕘", r.title, r.detail) { onPick(r.toFound()) }
    } else {
      if (busy && offline.isEmpty()) CircularProgressIndicator(color = Nm.Accent, modifier = Modifier.padding(12.dp))
      corrected?.let { Caption("Cercato «$it»") }
      if (offline.isNotEmpty()) SectionHeader("Sul tablet (senza rete)")
      for (f in offline) ResultRow(f.icon, f.title, f.detail, f.distanceM?.let { Fmt.distanceText(it) }) { onPick(f) }
      val extra = online.filter { o -> offline.none { app.navmaster.truck.search.Matching.samePlace(it.title, it.coordinate.lat, it.coordinate.lng, o.title, o.coordinate.lat, o.coordinate.lng) } }
      if (extra.isNotEmpty()) SectionHeader("Online")
      for (f in extra) ResultRow(f.icon, f.title, f.detail) { onPick(f) }
      if (!busy && offline.isEmpty() && extra.isEmpty()) Caption("Nessun risultato. Prova con meno parole o con la ricerca guidata.")
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.GuidedSearch(onPick: (Found) -> Unit) {
  val regions = remember { AppGraph.addresses.regionsWithAddresses() }
  var region by remember { mutableStateOf(regions.singleOrNull()) }
  var place by remember { mutableStateOf<PlaceHit?>(null) }
  var street by remember { mutableStateOf<StreetHit?>(null) }
  var q by remember { mutableStateOf("") }
  val catalog by AppGraph.catalog.catalog.collectAsState()
  fun flagOf(r: InstalledRegion) = catalog?.countries?.firstOrNull { it.id == r.id }?.flag ?: "🏳"

  // breadcrumb: tap a step to go back to it
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Crumb(region?.let { flagOf(it) + " " + it.label } ?: "Paese", region == null) { region = null; place = null; street = null; q = "" }
    if (region != null) Crumb(place?.name ?: "Città", place == null) { place = null; street = null; q = "" }
    if (place != null) Crumb(street?.name ?: "Via", street == null) { street = null; q = "" }
    if (street != null) Crumb("Civico", true) {}
  }
  Spacer(Modifier.height(10.dp))

  val r = region
  if (r == null) {
    Results {
      if (regions.isEmpty()) Caption("Scarica prima un Paese: gli indirizzi sono sul tablet e funzionano senza rete.")
      for (x in regions) ResultRow(flagOf(x), x.label, "Indirizzi offline") { region = x; q = "" }
    }
    return
  }
  val p = place
  if (p == null) {
    var list by remember(r.id) { mutableStateOf<List<PlaceHit>>(emptyList()) }
    LaunchedEffect(r.id, q) {
      delay(150)
      list = withContext(Dispatchers.IO) { runCatching { AppGraph.addresses.places(r.id, q) }.getOrDefault(emptyList()) }
    }
    field(q, { q = it }, "Città o paese")
    Spacer(Modifier.height(6.dp))
    Results {
      for (x in list) {
        val detail = listOfNotNull(x.kindLabel, x.parentName, x.county).joinToString(" · ")
        ResultRow(if (x.kind == "city") "🏙" else "🏘", x.name, detail) { place = x; q = "" }
      }
    }
    return
  }
  val s = street
  if (s == null) {
    var list by remember(p.id) { mutableStateOf<List<StreetHit>>(emptyList()) }
    LaunchedEffect(p.id, q) {
      delay(120)
      list = withContext(Dispatchers.IO) { runCatching { AppGraph.addresses.streets(r.id, p, q) }.getOrDefault(emptyList()) }
    }
    field(q, { q = it }, "Via di ${p.name}")
    Spacer(Modifier.height(6.dp))
    Results {
      ResultRow("🎯", "Centro di ${p.name}", "Senza via") {
        onPick(Found(p.name, listOfNotNull(p.county, r.label).joinToString(" · "), GeographicCoordinate(p.lat, p.lon), "🏙"))
      }
      for (x in list) ResultRow(if (x.noStreet) "🏘" else "🛣", x.name, x.placeName) { street = x; q = "" }
    }
    return
  }
  var houses by remember(s.id) { mutableStateOf<List<HouseHit>>(emptyList()) }
  LaunchedEffect(s.id) { houses = withContext(Dispatchers.IO) { runCatching { AppGraph.addresses.houses(r.id, s.id) }.getOrDefault(emptyList()) } }
  field(q, { q = it }, "Numero civico")
  Spacer(Modifier.height(8.dp))
  Results {
  BigButton("Vai in ${s.name} (senza civico)", Modifier.fillMaxWidth(), style = BtnStyle.SECONDARY) {
    onPick(Found(s.name, listOfNotNull(s.placeName, r.label).joinToString(" · "), GeographicCoordinate(s.lat, s.lon), "🛣"))
  }
  Spacer(Modifier.height(10.dp))
  val shown = houses.filter { q.isBlank() || it.num.startsWith(q.trim(), ignoreCase = true) }.take(200)
  // a number typed that is not in the map: Google's position (tablet geocoder, with the network),
  // else placed between the known numbers of the street
  val typed = q.trim().takeIf { Regex("^\\d{1,5}\\s*[a-zA-Z]?(/\\w+)?$").matches(it) }
  val missing = typed != null && houses.none { it.num.equals(typed, true) }
  var online by remember(s.id, typed) { mutableStateOf<Found?>(null) }
  LaunchedEffect(s.id, typed) {
    online = null
    if (!missing || typed == null) return@LaunchedEffect
    delay(500)
    online = withContext(Dispatchers.IO) {
      runCatching { app.navmaster.truck.search.Geocoder.platform("${s.name} $typed, ${s.placeName ?: ""}", GeographicCoordinate(s.lat, s.lon), 3) }
          .getOrDefault(emptyList())
          .firstOrNull { f -> app.navmaster.truck.core.Geo.dist(f.coordinate.lat, f.coordinate.lng, s.lat, s.lon) < 3000 &&
              f.title.contains(typed.takeWhile { it.isDigit() }) }
    }
  }
  if (missing && typed != null) {
    val o = online
    if (o != null) {
      BigButton("Vai in ${s.name} $typed", Modifier.fillMaxWidth()) {
        onPick(Found("${s.name} $typed", listOfNotNull("posizione da Google", s.placeName, r.label).joinToString(" · "), o.coordinate, "📍"))
      }
      Spacer(Modifier.height(8.dp))
    } else {
      val e = app.navmaster.truck.search.HouseNumbers.find(typed, houses.map { app.navmaster.truck.search.HouseNumbers.Known(it.num, it.lat, it.lon) })
      if (e != null) {
        BigButton("Vai in ${s.name} $typed (stimato ${e.note ?: ""})", Modifier.fillMaxWidth()) {
          onPick(Found("${s.name} $typed", listOfNotNull("civico stimato", s.placeName, r.label).joinToString(" · "), GeographicCoordinate(e.lat, e.lon), "📍"))
        }
        Spacer(Modifier.height(8.dp))
      }
    }
  }
  if (houses.isEmpty()) Caption("Nessun numero civico mappato su questa via: scrivi il numero, lo cerco online.")
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    for (h in shown) {
      Text(h.num, color = Nm.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
          modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(Nm.Raised)
              .border(1.dp, Nm.Line, RoundedCornerShape(14.dp))
              .clickable { onPick(Found("${s.name} ${h.num}", listOfNotNull(s.placeName, r.label).joinToString(" · "), GeographicCoordinate(h.lat, h.lon), "📍")) }
              .padding(horizontal = 14.dp, vertical = 14.dp))
    }
  }
  }
}

@Composable
private fun Crumb(text: String, current: Boolean, onClick: () -> Unit) {
  Text(text, color = if (current) Color.White else Nm.Muted, fontSize = 15.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
      modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (current) Nm.AccentDark else Nm.Raised).clickable(onClick = onClick)
          .padding(horizontal = 12.dp, vertical = 8.dp))
}
