package app.navmaster.truck.ui

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.navmaster.truck.AppGraph
import app.navmaster.truck.limits.RouteLimit
import app.navmaster.truck.nav.NavViewModel
import app.navmaster.truck.nav.PlanState
import app.navmaster.truck.poi.RoutePoi
import app.navmaster.truck.routing.Criticality
import app.navmaster.truck.routing.Severity
import app.navmaster.truck.vehicle.VehicleProfile
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.core.boundingBox
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeedUnit
import com.stadiamaps.ferrostar.maplibreui.NavigationMapClickResult
import com.stadiamaps.ferrostar.maplibreui.NavigationMapPuckStyle
import com.stadiamaps.ferrostar.maplibreui.NavigationMapView
import com.stadiamaps.ferrostar.maplibreui.routeline.RouteOverlayBuilder
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraOptions
import com.stadiamaps.ferrostar.maplibreui.runtime.rememberNavigationMapState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable
import uniffi.ferrostar.GeographicCoordinate

private enum class Sheet { NONE, SEARCH, VEHICLE, SETTINGS, REGIONS }

@Composable
fun MainScreen(vm: NavViewModel, initialSheet: String? = null, initialCrit: Int? = null) {
  KeepScreenOnDisposableEffect()
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

  val permissions =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
          arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
              Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.FOREGROUND_SERVICE_LOCATION)
      else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
          arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
      else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
    vm.setLocationPermission(result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
  }
  LaunchedEffect(Unit) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      vm.setLocationPermission(true)
    } else {
      launcher.launch(permissions)
    }
    AppGraph.catalog.refresh()
  }

  val ui by vm.navigationUiState.collectAsState()
  val plan by vm.plan.collectAsState()
  val nav by vm.nav.collectAsState()
  val garage by AppGraph.profiles.garage.collectAsState()
  val installed by AppGraph.regions.installed.collectAsState()
  val settings by AppGraph.settings.settings.collectAsState()
  val location by vm.location.collectAsState()
  val navigating = ui.isNavigating()
  val here = rememberCurrentCountry(location?.coordinates?.lat, location?.coordinates?.lng)

  var night by remember { mutableStateOf(MapStyles.isNight(settings.nightMode)) }
  LaunchedEffect(settings.nightMode) {
    while (true) {
      night = MapStyles.isNight(settings.nightMode)
      delay(60_000)
    }
  }
  var satellite by remember { mutableStateOf(false) }
  var sheet by remember {
    mutableStateOf(when (initialSheet) {
      "vehicle" -> Sheet.VEHICLE
      "settings", "settings_poi", "settings_map", "settings_live" -> Sheet.SETTINGS
      "regions" -> Sheet.REGIONS
      "search", "search_guided" -> Sheet.SEARCH
      else -> Sheet.NONE
    })
  }
  val searchGuided = initialSheet == "search_guided"
  // the search opened to choose the departure of a simulated trip (not the destination)
  var searchForStart by remember { mutableStateOf(false) }
  var openCrit by remember { mutableStateOf<Criticality?>(null) }
  // emulator test: open the detail of the n-th difficulty as soon as the routes are ready
  var critShown by remember { mutableStateOf(false) }
  LaunchedEffect(plan.current, plan.computing) {
    val cur = plan.current
    if (initialCrit != null && !critShown && cur != null && !plan.computing) {
      cur.criticalities.getOrNull(initialCrit)?.let { openCrit = it }
      critShown = true
    }
  }
  var openPoi by remember { mutableStateOf<RoutePoi?>(null) }
  var reportOpen by remember { mutableStateOf(initialSheet == "report") }
  var stopsOpen by remember { mutableStateOf(initialSheet == "stops") }
  var poiOpen by remember { mutableStateOf(initialSheet == "pois") }

  var countryHintClosed by remember { mutableStateOf(false) }
  val trafficTiles = if (settings.liveTraffic && settings.trafficOnMap && app.navmaster.truck.live.ApiKeys.tomtom(settings).isNotBlank())
    app.navmaster.truck.live.TrafficFeeds.tomtomFlowTiles(app.navmaster.truck.live.ApiKeys.tomtom(settings), night) else null
  val styleUri = remember(installed, night, satellite, trafficTiles) { MapStyles.styleUri(context, installed, night, satellite, trafficTiles) }

  // Garmin-like camera: tilted, the vehicle low on the screen so the road ahead is visible. Near a
  // turn in town it comes closer and leans a little more, smoothly, and goes back after it
  val h = configuration.screenHeightDp
  val w = configuration.screenWidthDp
  val is3d = settings.driveView == app.navmaster.truck.settings.DriveView.VIEW_3D
  val toManeuver = if (navigating) ui.progress?.distanceToNextManeuver else null
  val fastRoad = nav.analysis?.edgeAt((if (navigating) (nav.routeLength - (ui.progress?.distanceRemaining ?: 0.0)) else 0.0).coerceAtLeast(0.0))
      ?.roadClass in setOf("motorway", "trunk")
  val closeUp = toManeuver != null && toManeuver < (if (fastRoad) 0.0 else 260.0)
  val zoom by androidx.compose.animation.core.animateFloatAsState(
      (if (is3d) 16.6f else 16.0f) + (if (closeUp) 0.9f else 0f), androidx.compose.animation.core.tween(1500), label = "zoom")
  val tilt by androidx.compose.animation.core.animateFloatAsState(
      if (is3d) settings.tiltDeg.toFloat() + (if (closeUp) 5f else 0f) else 0f, androidx.compose.animation.core.tween(1500), label = "tilt")
  val cameraOptions =
      NavigationCameraOptions(
          browsingZoom = 15.0,
          navigationZoom = zoom.toDouble(),
          // 2D: straight from above, a little farther to see more around
          navigationTilt = tilt.toDouble().coerceAtMost(65.0),
          browsingPadding = PaddingValues(0.dp),
          navigationPadding =
              if (landscape) PaddingValues(top = (h * 0.30f).dp, end = (w * 0.42f).dp)
              else PaddingValues(top = (h * 0.40f).dp),
      )
  val mapState = rememberNavigationMapState()
  // routes computed (or another one chosen): the whole route on screen, beside the routes panel;
  // the map goes back to the driver when the plan is cleared
  val previewGeom = if (!navigating) plan.current?.route?.geometry else null
  LaunchedEffect(previewGeom?.size, previewGeom?.firstOrNull(), previewGeom?.lastOrNull()) {
    val g = previewGeom ?: return@LaunchedEffect
    val box = g.boundingBox() ?: return@LaunchedEffect
    runCatching {
      mapState.showRouteOverview(box,
          if (landscape) PaddingValues(start = (w * 0.5f).dp, top = 90.dp, end = 90.dp, bottom = 40.dp)
          else PaddingValues(start = 40.dp, top = 110.dp, end = 90.dp, bottom = (h * 0.42f).dp))
    }
  }
  LaunchedEffect(plan.stops.isEmpty()) { if (plan.stops.isEmpty() && !navigating) mapState.recenter(false) }
  // the buttons show up when the map is touched and go away by themselves
  var lastMapTap by remember { mutableStateOf(System.currentTimeMillis()) }
  var mapMoved by remember { mutableLongStateOf(0L) }
  // a point long-pressed on the map, waiting for "go / pass here / avoid"
  var pendingPoint by remember { mutableStateOf<GeographicCoordinate?>(null) }

  val traveled = if (navigating) (nav.routeLength - (ui.progress?.distanceRemaining ?: 0.0)).coerceAtLeast(0.0) else 0.0
  val nextLimit = nav.limits.firstOrNull { it.kind != "speed_camera" && it.alongM - traveled > -15 }
  // fixed speed cameras: only where warning about them is allowed, facing our way when the map says
  // the cameras that may be announced, worked out once per route (not at every position)
  val camerasOk = remember(nav.limits, nav.analysis, settings.enforcementEverywhere) {
    nav.limits.filter { l ->
      l.kind == "speed_camera" &&
          (settings.enforcementEverywhere || nav.analysis?.edgeAt(l.alongM)?.country?.uppercase() !in CAMERA_WARNINGS_BANNED) &&
          cameraFacesUs(l, nav.analysis)
    }
  }
  val nextCamera = if (!navigating || !settings.speedCameras) null else camerasOk.firstOrNull { l -> l.alongM - traveled in -10.0..800.0 }
  val cameraZoneOnly = nextCamera != null && !settings.enforcementEverywhere &&
      nav.analysis?.edgeAt(nextCamera.alongM)?.country?.uppercase() in CAMERA_ZONE_ONLY
  LaunchedEffect(nextCamera?.alongM, (nextCamera?.alongM?.minus(traveled) ?: 9999.0) < 600) {
    val cam = nextCamera ?: return@LaunchedEffect
    if (cam.alongM - traveled < 600) vm.say(if (cameraZoneOnly) "Zona di controllo della velocità" else
      "Autovelox tra ${(((cam.alongM - traveled) / 50).roundToInt() * 50).coerceAtLeast(50)} metri" +
          (if (cam.value > 0) ", limite ${cam.value.toInt()}" else ""))
  }
  val nextLimitDist = nextLimit?.let { it.alongM - traveled }
  val nextCrit = nav.criticalities.firstOrNull { it.severity != Severity.INFO && it.endM - traveled > -10 && it.startM - traveled < 3000 }

  // spoken warning for a limit the vehicle cannot pass, at 3 km and at 800 m
  val spoken = remember { mutableSetOf<String>() }
  LaunchedEffect(nextLimit, nextLimitDist?.let { (it / 100).roundToInt() }) {
    val l = nextLimit ?: return@LaunchedEffect
    val d = nextLimitDist ?: return@LaunchedEffect
    if (!l.blocking) return@LaunchedEffect
    for (at in listOf(3000.0, 800.0)) {
      val key = "${l.lat},${l.lon},$at"
      if (d <= at && d > at - 400 && key !in spoken) {
        spoken += key
        vm.say("Attenzione: tra ${app.navmaster.truck.nav.SpeechIt.distance(d)}, ${l.label} ${l.signValue ?: ""}. " +
            "Il mezzo non passa, verificare la segnaletica.")
      }
    }
  }

  // ---- the arrow along the route at every frame (see SmoothTrack)
  val track = remember { SmoothTrack() }
  val smoothLoc = remember { mutableStateOf<uniffi.ferrostar.UserLocation?>(null) }
  val trackAnalysis = nav.analysis
  val coreLoc = ui.location
  val measuredSpeed by vm.speedMs.collectAsState()
  // the guidance's own position along the route matches the route of the analysis: otherwise (a
  // new route not yet analysed, really off the route) the map shows the guidance's position as is
  val trackOk = navigating && trackAnalysis != null && coreLoc != null &&
      ui.routeDeviation is uniffi.ferrostar.RouteDeviation.NoDeviation &&
      app.navmaster.truck.core.Geo.dist(trackAnalysis.pointAt(traveled), coreLoc.coordinates) < 40.0
  SideEffect {
    if (trackOk) {
      if (traveled != track.target) {
        track.target = traveled
        track.targetAt = System.nanoTime()
      }
      track.speed = maxOf(coreLoc?.speed?.value ?: 0.0, measuredSpeed ?: 0.0).coerceIn(0.0, 45.0)
      track.accuracy = coreLoc?.horizontalAccuracy ?: 5.0
      track.speedObj = coreLoc?.speed
    }
  }
  LaunchedEffect(trackOk, trackAnalysis) {
    val a = trackAnalysis
    if (!trackOk || a == null) {
      smoothLoc.value = null
      return@LaunchedEffect
    }
    track.shown = -1.0
    track.lastFrame = 0L
    while (true) {
      androidx.compose.runtime.withFrameNanos { t ->
        smoothLoc.value = track.step(t, a)
      }
    }
  }

  Box(Modifier.fillMaxSize().background(Nm.Bg)) {
    // a new map view when the tablet turns: the old one kept drawing at the old size (a blank strip
    // on one side in portrait); the camera state survives, it is kept outside
    key(landscape) {
    // a finger moving the map (also two fingers zooming) while routes are offered: the list of
    // routes goes down to a small bar so the map can be looked at
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
      awaitPointerEventScope {
        while (true) {
          val e = awaitPointerEvent(PointerEventPass.Initial)
          if (e.type == PointerEventType.Move && e.changes.any { it.pressed && (it.position - it.previousPosition).getDistance() > 3f }) {
            mapMoved = System.currentTimeMillis()
          }
        }
      }
    }) {
    // only this part is redrawn at every frame while the arrow glides (not the whole screen)
    ScopedMap {
    val smooth = smoothLoc.value
    NavigationMapView(
        baseStyle = BaseStyle.Uri(styleUri),
        navigationMapState = mapState,
        // the arrow and the camera follow the position worked out here along the route (see
        // SmoothTrack); "off route" only tells the map to use it as it is, without its own snapping
        uiState = if (smooth != null) ui.copy(location = smooth, routeDeviation = uniffi.ferrostar.RouteDeviation.Deviation(uniffi.ferrostar.DeviationKind.OffStepOnRoute(0.0))) else ui,
        mapOptions = MapOptions(ornamentOptions = OrnamentOptions(isCompassEnabled = false, isScaleBarEnabled = false)),
        routeOverlayBuilder =
            RouteOverlayBuilder(
                // the route handed to the map once per route, not at every frame (see MapLines)
                navigationPath = { state ->
                  NmRouteLine(state.routeGeometry, "nm-route", Nm.Route, 13f, 3f)
                }),
        navigationCameraOptions = cameraOptions,
        locationPuckStyle =
            NavigationMapPuckStyle(
                dotFillColorCurrentLocation = Color(0xFF1E88E5),
                bearingColor = Color(0xFF0D47A1),
                dotRadius = 11.dp,
                dotStrokeWidth = 4.dp,
            ),
        onMapClick = { _, _ ->
          lastMapTap = System.currentTimeMillis()
          NavigationMapClickResult.Pass
        },
        onMapLongClick = { coordinate, _ ->
          lastMapTap = System.currentTimeMillis()
          when {
            !navigating && plan.addingStop -> vm.addVia(coordinate, "Tappa sulla mappa")
            !navigating && plan.stops.isEmpty() -> vm.selectDestination(coordinate, "Punto sulla mappa")
            else -> pendingPoint = coordinate
          }
          NavigationMapClickResult.Consume
        },
    ) { _ ->
      if (!navigating) {
        plan.variants.forEachIndexed { i, v ->
          if (i != plan.selected) NmRouteLine(v.route.geometry, "nm-alt-$i", Color(0xFF8C97A3), 8f, 2f)
        }
        plan.current?.let {
          NmRouteLine(it.route.geometry, "nm-preview", Nm.Route, 11f, 3f)
          // where the toll starts and ends, the tunnels
          RouteFeaturePins(it.analysis)
        }
      }
      // the markers change with the route, not at every position: worked out once (redoing them
      // each second rebuilt the map sources and made the map stutter)
      val crits = if (navigating) nav.criticalities else plan.current?.criticalities ?: emptyList()
      CritMarkers(crits)
      val limitsAll = if (navigating) nav.limits else plan.current?.limits ?: emptyList()
      LimitMarkers(remember(limitsAll) { limitsAll.filter { it.kind != "speed_camera" } })
      val stopPts = remember(plan.stops) { plan.stops.map { it.coordinate } }
      StopMarkers(stopPts)
      // the departure chosen for a simulated trip: a green point
      plan.start?.takeIf { !navigating }?.let { st ->
        val startJson = remember(st.coordinate) { points(listOf(st.coordinate.lat to st.coordinate.lng)) }
        val startSrc = rememberJsonSource(startJson)
        CircleLayer(id = "nm-start", source = startSrc, color = const(Nm.Accent), radius = const(10.dp), strokeColor = const(Color.White),
            strokeWidth = const(3.dp))
      }
      if (navigating) {
        // pins on the road: speed cameras (where warning about them is allowed) and the events;
        // the ones left behind go away every 250 m
        val step = (traveled / 250).toInt()
        val cams = remember(camerasOk, settings.speedCameras, step) {
          if (!settings.speedCameras) emptyList() else camerasOk.filter { l ->
            l.alongM > traveled - 50 && (settings.enforcementEverywhere || nav.analysis?.edgeAt(l.alongM)?.country?.uppercase() !in CAMERA_ZONE_ONLY)
          }
        }
        val livePins = remember(nav.live, step) { nav.live.filter { it.endM > traveled - 50 } }
        RoadPins(cams, livePins) { ev ->
          nav.analysis?.pointAt(ev.startM.coerceAtLeast(0.0))?.let { it.lat to it.lng } ?: (ev.e.lat to ev.e.lon)
        }
      }
    }
    }
    }
    }

    if (navigating) {
      val booth = nav.analysis?.nodes?.firstOrNull { it.alongM > traveled - 20 }
          ?.let { b -> Triple(b, nav.analysis?.boothRole(b), b.alongM - traveled) }
          ?.takeIf { it.third <= 2500 }
      NavigatingOverlay(vm, ui, garage.active, nextLimit, nextLimitDist, nextCrit, booth, traveled, nav.pois, landscape, mapState,
          settings, nav.analysis, night, lastMapTap, nextCamera, cameraZoneOnly, here?.iso, onCrit = { openCrit = it }, onPoi = { openPoi = it },
          live = nav.live, liveAsk = nav.liveAsk, onSettings = { sheet = Sheet.SETTINGS }, onReport = { reportOpen = true },
          stopsCount = plan.stops.size - 1, onStops = { stopsOpen = true }, onPois = { poiOpen = true })
      if (nav.recalculating) {
        Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).background(Color(0xE6000000)).padding(18.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = Nm.Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text("Ricalcolo del percorso…", color = Color.White, fontSize = 18.sp)
          }
        }
      }
      nav.prompt?.let { p ->
        PromptCard(p, traveled, onRamp = vm::answerRamp, onToll = vm::answerToll,
            onBreakGo = { pk -> vm.dismissPrompt(); vm.addStopDuringNav(pk.poi.coordinate, pk.poi.title.ifBlank { "Parcheggio" }) },
            onDismiss = vm::dismissPrompt, onClosure = vm::answerClosure)
      }
      if (stopsOpen && plan.stops.size > 1) {
        StopsPanel(plan.stops, onRemove = { i -> vm.removeStopDuringNav(i); if (plan.stops.size <= 2) stopsOpen = false },
            onRemoveAll = { vm.removeAllStopsDuringNav(); stopsOpen = false }, onClose = { stopsOpen = false })
      }
      if (reportOpen) {
        ReportPicker(nav.analysis?.edgeAt(traveled)?.country ?: here?.iso, onPick = { k -> reportOpen = false; vm.report(k) },
            onClose = { reportOpen = false })
      }
    } else {
      BrowsingOverlay(
          vm, plan, garage.active.name, garage.active.type.icon, landscape,
          onSearch = { searchForStart = false; sheet = Sheet.SEARCH },
          onPickStart = { searchForStart = true; sheet = Sheet.SEARCH },
          onVehicle = { sheet = Sheet.VEHICLE },
          onSettings = { sheet = Sheet.SETTINGS },
          onRegions = { sheet = Sheet.REGIONS },
          onSatellite = { satellite = !satellite },
          satellite = satellite,
          onRecenter = { mapState.recenter(false) },
          onCrit = { openCrit = it },
          tracking = mapState.isTrackingUser,
          onPois = { poiOpen = true },
          mapMoved = mapMoved,
      )
      // in a country whose map is not on the tablet: offer it
      // the hint goes away by itself after a while and never covers a route being chosen
      LaunchedEffect(here?.id) {
        if (here != null) {
          kotlinx.coroutines.delay(15_000)
          countryHintClosed = true
        }
      }
      if (here != null && installed.isNotEmpty() && installed.none { it.id == here.id } && here.available && !countryHintClosed &&
          plan.current == null) {
        Row(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 92.dp).shadow(8.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp)).background(Nm.PanelSolid).border(1.dp, Nm.Accent, RoundedCornerShape(20.dp))
                .clickable { sheet = Sheet.REGIONS }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(here.flag, fontSize = 24.sp)
          Spacer(Modifier.width(10.dp))
          Column {
            Text("Sei in ${here.name}", color = Nm.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Caption("Tocca per scaricarne la mappa", size = 13)
          }
          Spacer(Modifier.width(10.dp))
          Icon(Icons.Rounded.Close, "Chiudi", tint = Nm.Muted, modifier = Modifier.size(36.dp).clip(CircleShape).clickable { countryHintClosed = true }.padding(6.dp))
        }
      }
    }

    // messages about a place shared from another app (Google Maps …)
    val notice by vm.notice.collectAsState()
    Toast(notice, Modifier.navigationBarsPadding())

    pendingPoint?.let { pt ->
      PointChooser(
          navigating = navigating,
          modifier = Modifier.align(Alignment.Center),
          onVia = { if (navigating) vm.addStopDuringNav(pt, "Passa di qui", via = true) else vm.addVia(pt); pendingPoint = null },
          onDetour = { vm.detourAndReturn(pt); pendingPoint = null },
          onStartHere = { vm.setStart(pt, "Punto sulla mappa"); pendingPoint = null },
          onGo = { vm.selectDestination(pt, "Punto sulla mappa"); pendingPoint = null },
          onAvoid = { vm.avoidArea(pt); pendingPoint = null },
          onDismiss = { pendingPoint = null },
      )
    }

    if (installed.isEmpty() && sheet != Sheet.REGIONS) {
      WelcomeScreen(here) { sheet = Sheet.REGIONS }
    }

    when (sheet) {
      Sheet.SEARCH -> SearchScreen(location?.coordinates, startGuided = searchGuided, onPick = { f ->
        sheet = Sheet.NONE
        if (searchForStart) vm.setStart(f.coordinate, f.title) else vm.selectDestination(f.coordinate, f.title)
        searchForStart = false
      }, onClose = { sheet = Sheet.NONE; searchForStart = false })
      Sheet.VEHICLE -> VehicleEditor(onClose = {
        sheet = Sheet.NONE
        if (plan.stops.isNotEmpty()) vm.planRoutes()
      })
      Sheet.SETTINGS -> SettingsScreen(onClose = { sheet = Sheet.NONE; vm.refreshPois() }, onRegions = { sheet = Sheet.REGIONS },
          initialPage = initialSheet?.takeIf { it.startsWith("settings_") }?.substringAfter("settings_"))
      Sheet.REGIONS -> RegionsScreen(here, onClose = { sheet = Sheet.NONE })
      Sheet.NONE -> {}
    }
    if (poiOpen) {
      val simulatingNow by vm.simulating.collectAsState()
      PoiBrowser(
          route = if (navigating) nav.analysis?.route?.geometry else plan.current?.route?.geometry,
          traveledM = traveled,
          here = location?.coordinates,
          destination = plan.stops.lastOrNull()?.coordinate,
          stop = plan.stops.dropLast(1).firstOrNull()?.coordinate,
          navigating = navigating,
          onAddStop = { p ->
            poiOpen = false
            val label = p.title.ifBlank { "Tappa" }
            when {
              navigating -> vm.addStopDuringNav(p.coordinate, label)
              plan.stops.isNotEmpty() -> vm.addVia(p.coordinate, label)
              else -> vm.selectDestination(p.coordinate, label)
            }
          },
          onGo = { p ->
            poiOpen = false
            val label = p.title.ifBlank { "Destinazione" }
            if (navigating) vm.goTo(p.coordinate, label, simulatingNow) else vm.selectDestination(p.coordinate, label)
          },
          onClose = { poiOpen = false },
      )
    }
    openCrit?.let { c ->
      CriticalitySheet(
          c,
          (if (navigating) nav.analysis?.route else plan.current?.route)?.geometry,
          onAvoid = if (!navigating) ({ openCrit = null; vm.avoid(c) }) else null,
          onAddStop = if (!navigating) ({ openCrit = null; vm.startAddingStop() }) else null,
          onClose = { openCrit = null },
      )
    }
    openPoi?.let { p ->
      PoiSheet(p, traveled, onAddStop = { openPoi = null; vm.addStopDuringNav(p.poi.coordinate, p.poi.title.ifBlank { "Tappa" }) },
          onClose = { openPoi = null })
    }
  }
}

@Composable
private fun BrowsingOverlay(
    vm: NavViewModel,
    plan: PlanState,
    vehicleName: String,
    vehicleIcon: String,
    landscape: Boolean,
    onSearch: () -> Unit,
    onVehicle: () -> Unit,
    onSettings: () -> Unit,
    onRegions: () -> Unit,
    onSatellite: () -> Unit,
    satellite: Boolean,
    onRecenter: () -> Unit,
    onCrit: (Criticality) -> Unit,
    tracking: Boolean = false,
    onPois: () -> Unit = {},
    mapMoved: Long = 0L,
    onPickStart: () -> Unit = {},
) {
  // the routes panel goes down while the map is moved, and comes back with "Dettagli"
  var collapsed by remember { mutableStateOf(false) }
  LaunchedEffect(mapMoved) { if (mapMoved > 0 && plan.variants.isNotEmpty()) collapsed = true }
  LaunchedEffect(plan.stops.isEmpty()) { if (plan.stops.isEmpty()) collapsed = false }
  val screenH = LocalConfiguration.current.screenHeightDp
  Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
    // search bar
    Row(
        Modifier.align(Alignment.TopStart).then(if (landscape) Modifier.fillMaxWidth(0.5f) else Modifier.fillMaxWidth().padding(end = 76.dp))
            .heightIn(min = 64.dp).shadow(10.dp, RoundedCornerShape(32.dp))
            .clip(RoundedCornerShape(32.dp)).background(Nm.Panel).border(1.dp, Nm.Line, RoundedCornerShape(32.dp)).clickable(onClick = onSearch)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Rounded.Search, null, tint = Nm.Muted, modifier = Modifier.size(28.dp))
      Spacer(Modifier.width(12.dp))
      Text("Dove andiamo?", color = Nm.Muted, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      Row(
          Modifier.clip(RoundedCornerShape(20.dp)).background(Nm.Raised).clickable(onClick = onVehicle).padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(vehicleIcon, fontSize = 18.sp)
        Spacer(Modifier.width(6.dp))
        Text(vehicleName, color = Nm.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
      }
    }
    // map buttons
    // map buttons: only what is useful now (the vehicle is already in the search bar, the countries
    // are in the settings); with a route on screen, only the satellite view
    Column(Modifier.align(Alignment.TopEnd), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (plan.stops.isEmpty()) RoundAction(Icons.Rounded.Settings, "Impostazioni", onClick = onSettings)
      RoundAction(Icons.Rounded.Layers, "Satellite", container = if (satellite) Nm.Accent else Nm.Panel, onClick = onSatellite)
      RoundAction(Icons.Rounded.Place, "Punti di interesse", onClick = onPois)
    }
    if (!tracking) RoundAction(Icons.Rounded.MyLocation, "Centra", Modifier.align(Alignment.BottomEnd), onClick = onRecenter)

    if (plan.stops.isNotEmpty()) {
      PlanPanel(
          plan, vehicleName,
          modifier = Modifier.align(if (landscape) Alignment.BottomStart else Alignment.BottomCenter)
              .then(
                  when {
                    collapsed && landscape -> Modifier.fillMaxWidth(0.5f).padding(end = 64.dp)
                    collapsed -> Modifier.fillMaxWidth().padding(end = 64.dp)
                    landscape -> Modifier.fillMaxWidth(0.46f).fillMaxHeight(0.84f)
                    // below the search bar, whatever the height of the screen
                    else -> Modifier.fillMaxWidth().heightIn(max = (screenH * 0.66f).dp.coerceAtMost(600.dp))
                  }),
          collapsed = collapsed,
          onExpand = { collapsed = false },
          onSelect = vm::selectVariant,
          onCrit = onCrit,
          onUnavoid = vm::unavoid,
          onAddStop = { if (plan.addingStop) vm.cancelAddingStop() else vm.startAddingStop() },
          onRemoveStop = vm::removeStop,
          onRemoveArea = vm::removeAvoidArea,
          onStart = { vm.start(false) },
          onSimulate = { vm.start(true) },
          onCancel = vm::clearPlan,
          onPickStart = onPickStart,
          onClearStart = vm::clearStart,
          onMore = vm::loadMoreRoutes,
      )
    } else {
      Text("Cerca un indirizzo o tieni premuto sulla mappa", color = Color(0xCCFFFFFF), fontSize = 14.sp,
          modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp))
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NavigatingOverlay(
    vm: NavViewModel,
    ui: com.stadiamaps.ferrostar.core.NavigationUiState,
    vehicle: VehicleProfile,
    nextLimit: RouteLimit?,
    nextLimitDist: Double?,
    nextCrit: Criticality?,
    booth: Triple<app.navmaster.truck.routing.RouteNode, String?, Double>?,
    traveled: Double,
    pois: List<RoutePoi>,
    landscape: Boolean,
    mapState: com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState,
    settings: app.navmaster.truck.settings.Settings,
    analysis: app.navmaster.truck.routing.RouteAnalysis?,
    night: Boolean,
    lastMapTap: Long,
    camera: RouteLimit?,
    cameraZoneOnly: Boolean,
    countryIso: String?,
    onCrit: (Criticality) -> Unit,
    onPoi: (RoutePoi) -> Unit,
    live: List<app.navmaster.truck.live.RouteLiveEvent> = emptyList(),
    liveAsk: app.navmaster.truck.live.RouteLiveEvent? = null,
    onSettings: () -> Unit = {},
    onReport: () -> Unit = {},
    stopsCount: Int = 0,
    onStops: () -> Unit = {},
    onPois: () -> Unit = {},
) {
  val simulating by vm.simulating.collectAsState()
  val simSpeed by vm.simSpeed.collectAsState()
  // buttons: shown for a few seconds after the map is touched (and at the start), then only the
  // ones that are needed right now (the "centre" one when the map was moved by hand)
  var controls by remember { mutableStateOf(true) }
  LaunchedEffect(lastMapTap) {
    controls = true
    delay(8_000)
    controls = false
  }
  // junction view and lane guidance only at motorway junctions and complicated multi-lane ones
  val scene = junctionSceneOf(ui.visualInstruction, ui.progress?.distanceToNextManeuver, analysis, traveled, countryIso)
  val jv = if (settings.junctionView) scene else null
  // the lanes to keep, from 2 km before a motorway exit, fork or merge (400 m in town)
  val laneGuide = laneGuideOf(ui.visualInstruction, ui.progress?.distanceToNextManeuver, analysis, traveled)
  val fastRoad = analysis?.edgeAt(traveled)?.roadClass in setOf("motorway", "trunk")
  val nextLive = live.firstOrNull { it.endM - traveled > -20 && it.startM - traveled < (if (fastRoad) 5000.0 else 2000.0) }
  // the speed the GPS gives, or measured from the last positions when it gives none
  val measured by vm.speedMs.collectAsState()
  val speedKmh = (ui.location?.speed?.value?.takeIf { it >= 0 } ?: measured)?.let { (it * 3.6).roundToInt() }
  val limitKmh = ui.currentAnnotation?.speedLimit?.value(MeasurementSpeedUnit.KilometersPerHour)?.roundToInt()
  androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(10.dp)) {
  // the junction view fits the screen: about a third of the height upright, never taller than wide
  val jvHeight = (maxHeight.value * 0.36f).coerceIn(170f, 360f).coerceAtMost(maxWidth.value * 0.8f).dp
  Column(Modifier.fillMaxSize()) {
  Row(Modifier.weight(1f).fillMaxWidth()) {
  Column(Modifier.weight(1f).fillMaxHeight()) {
    TopManeuverBar(ui.visualInstruction, ui.progress?.distanceToNextManeuver, Modifier.fillMaxWidth(), showLanes = false)
    if (laneGuide != null) {
      LaneStrip(laneGuide, (if (landscape && jv == null) Modifier.widthIn(max = 640.dp) else Modifier).fillMaxWidth().padding(top = 6.dp))
    }
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (nextLimit != null && nextLimitDist != null && nextLimitDist < (if (nextLimit.blocking) 10_000.0 else 5_000.0)) {
        RestrictionBanner(nextLimit, nextLimitDist, vehicleValue(nextLimit, vehicle))
      } else if (nextCrit != null) {
        CritBanner(nextCrit, (nextCrit.startM - traveled).coerceAtLeast(0.0)) { onCrit(nextCrit) }
      }
      if (booth != null) BoothBanner(booth.first, booth.second, booth.third)
      if (camera != null) CameraBanner(camera, (camera.alongM - traveled).coerceAtLeast(0.0), cameraZoneOnly)
      if (nextLive != null) LiveBanner(nextLive, (nextLive.startM - traveled).coerceAtLeast(0.0), analysis?.edgeAt(nextLive.startM)?.country)
    }
    if (jv != null && !landscape) JunctionView(jv, night, Modifier.fillMaxWidth().padding(top = 8.dp), height = jvHeight)
    Box(Modifier.weight(1f).fillMaxWidth()) {
      // the speed is in the bottom bar when the driver keeps it there, else under the limit sign
      val speedInBar = settings.tripFields.contains("SPEED")
      SpeedPanel(if (speedInBar) null else speedKmh, limitKmh, vehicle.topSpeedKmh, Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp), settings.speedWarningKmh)
      // places along the route: a narrow panel at the edge (the right one unless the driver chose
      // the left), under the manoeuvre bar, never in the middle of the road ahead
      val atRight = settings.poiRailSide != app.navmaster.truck.settings.PoiSide.LEFT
      if (jv == null) {
        PoiRail(pois, traveled, settings.poiRailCount, settings.poiRailSeconds, settings.poiRailOpacity, atRight, narrow = !landscape,
            modifier = Modifier.align(if (atRight) Alignment.TopEnd else Alignment.TopStart).padding(top = if (atRight) 4.dp else 34.dp), onPoi = onPoi,
            onAll = onPois)
      }
      Column(Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
          horizontalAlignment = Alignment.End) {
        // always there: report something on the road, and the settings (changed any time while driving)
        if (settings.liveReports) {
          Box(
              Modifier.size(56.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(Nm.Amber).border(1.dp, Nm.Line, CircleShape)
                  .clickable(onClick = onReport),
              contentAlignment = Alignment.Center,
          ) { Text("⚠", fontSize = 26.sp, color = Color.Black) }
        }
        RoundAction(Icons.Rounded.Settings, "Impostazioni", size = 56.dp, onClick = onSettings)
        if (stopsCount > 0) StopsButton(stopsCount, onStops)
        // the map was moved by hand: "centre" stays until the driver uses it
        if (!mapState.isTrackingUser) RoundAction(Icons.Rounded.MyLocation, "Centra") { mapState.recenter(true) }
        androidx.compose.animation.AnimatedVisibility(controls, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 2D / 3D in one touch: the label says the view it switches to
        val is3d = settings.driveView == app.navmaster.truck.settings.DriveView.VIEW_3D
        Box(
            Modifier.size(64.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(Nm.Panel).border(1.dp, Nm.Line, CircleShape)
                .clickable {
                  AppGraph.settings.update {
                    it.copy(driveView = if (is3d) app.navmaster.truck.settings.DriveView.VIEW_2D else app.navmaster.truck.settings.DriveView.VIEW_3D)
                  }
                  mapState.recenter(true)
                },
            contentAlignment = Alignment.Center,
        ) { Text(if (is3d) "2D" else "3D", color = Nm.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        RoundAction(if (ui.isMuted == true) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "Voce") { vm.toggleMute() }
        RoundAction(Icons.Rounded.Close, "Termina", container = Nm.Red) { vm.stopNavigation() }
        }
        }
        // muted: a small reminder stays even when the buttons are away
        if (!controls && ui.isMuted == true) RoundAction(Icons.Rounded.VolumeOff, "Voce", size = 48.dp, container = Nm.Red) { vm.toggleMute() }
      }
      if (simulating) {
        SimControls(simSpeed, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            onPrevManeuver = { vm.simManeuver(false) }, onBack = { vm.simJump(-500.0) }, onSlower = vm::simSlower,
            onFaster = vm::simFaster, onAhead = { vm.simJump(500.0) }, onNextManeuver = { vm.simManeuver(true) })
      }
      if (liveAsk != null) {
        LiveAskCard(liveAsk, analysis?.edgeAt(liveAsk.startM)?.country, onAnswer = { yes -> vm.voteLive(liveAsk, yes) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (simulating) 84.dp else 12.dp))
      }
      if (simulating) {
        Text("SIMULAZIONE", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 6.dp).background(Nm.Amber, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp))
      }
    }
  }
  if (jv != null && landscape) {
    // as on the reference device: a tall panel on the right, from the top down to the bottom bar,
    // the manoeuvre, the lanes and the map on the left
    JunctionView(jv, night, Modifier.weight(0.72f).fillMaxHeight().padding(start = 10.dp, bottom = 8.dp), fill = true)
  }
  }
    val drivenS = vm.nav.collectAsState().value.drivenS
    val nextStop = vm.nextStopAhead(traveled)
    val remainingM = ui.progress?.distanceRemaining
    val remainingS = ui.progress?.durationRemaining
    TripBar(
        fields = settings.tripFields.mapNotNull { TripField.of(it) }.ifEmpty { listOf(TripField.SPEED, TripField.ARRIVAL, TripField.DIST_LEFT, TripField.TIME_LEFT) },
        data = TripData(
            speedKmh = speedKmh,
            limitKmh = limitKmh?.let { minOf(it, vehicle.topSpeedKmh) },
            toleranceKmh = settings.speedWarningKmh,
            remainingM = remainingM,
            remainingS = remainingS,
            nextStopM = nextStop?.let { it - traveled },
            nextStopS = nextStop?.let { at -> if (remainingM != null && remainingS != null && remainingM > 1) remainingS * (at - traveled) / remainingM else null },
            drivenS = drivenS,
            headingDeg = ui.location?.courseOverGround?.degrees?.toDouble(),
            road = ui.currentStepRoadName,
        ),
        onChange = { i, f ->
          AppGraph.settings.update { st ->
            val list = st.tripFields.toMutableList()
            while (list.size < 4) list += listOf("SPEED", "ARRIVAL", "DIST_LEFT", "TIME_LEFT")[list.size]
            list[i] = f.name
            st.copy(tripFields = list)
          }
        },
        modifier = Modifier.fillMaxWidth(),
    )
  }
  }
}

private fun vehicleValue(l: RouteLimit, v: VehicleProfile): String? =
    when (l.kind) {
      "maxheight" -> Fmt.metres(v.heightM)
      "maxwidth" -> Fmt.metres(v.widthM)
      "maxlength" -> Fmt.metres(v.lengthM)
      "maxaxleload" -> Fmt.tonnes(v.axleLoadT)
      "maxweight" -> Fmt.tonnes(AppGraph.profiles.garage.value.let { v.tripWeightT(it.loadT) })
      "adr_tunnel" -> "ADR ${v.adr.name}"
      else -> null
    }

private fun points(list: List<Pair<Double, Double>>): String =
    """{"type":"FeatureCollection","features":[""" +
        list.joinToString(",") { """{"type":"Feature","geometry":{"type":"Point","coordinates":[${it.second},${it.first}]},"properties":{}}""" } +
        "]}"

@Composable
@MaplibreComposable
private fun LimitMarkers(limits: List<RouteLimit>) {
  val okJson = remember(limits) { points(limits.filter { !it.blocking }.map { it.lat to it.lon }) }
  val badJson = remember(limits) { points(limits.filter { it.blocking }.map { it.lat to it.lon }) }
  val ok = rememberJsonSource(okJson)
  val bad = rememberJsonSource(badJson)
  CircleLayer(id = "nm-limits-ok", source = ok, color = const(NmAmber), radius = const(7.dp), strokeColor = const(Color.White), strokeWidth = const(2.dp))
  CircleLayer(id = "nm-limits-bad", source = bad, color = const(NmRed), radius = const(11.dp), strokeColor = const(Color.White), strokeWidth = const(3.dp))
}

@Composable
@MaplibreComposable
private fun CritMarkers(list: List<Criticality>) {
  val warnJson = remember(list) { points(list.filter { it.severity == Severity.WARN }.map { it.lat to it.lon }) }
  val critJson = remember(list) { points(list.filter { it.severity == Severity.CRITICAL }.map { it.lat to it.lon }) }
  val warn = rememberJsonSource(warnJson)
  val crit = rememberJsonSource(critJson)
  CircleLayer(id = "nm-crit-warn", source = warn, color = const(Nm.Amber), radius = const(9.dp), strokeColor = const(Color(0xFF111111)), strokeWidth = const(3.dp))
  CircleLayer(id = "nm-crit-bad", source = crit, color = const(Nm.Red), radius = const(11.dp), strokeColor = const(Color.White), strokeWidth = const(3.dp))
}

@Composable
@MaplibreComposable
private fun StopMarkers(stops: List<GeographicCoordinate>) {
  val json = remember(stops) { points(stops.map { it.lat to it.lng }) }
  val src = rememberJsonSource(json)
  CircleLayer(id = "nm-stops", source = src, color = const(Color(0xFFD50000)), radius = const(10.dp), strokeColor = const(Color.White), strokeWidth = const(3.dp))
}

/** What to do with a point long-pressed on the map. */
@Composable
private fun PointChooser(
    navigating: Boolean,
    modifier: Modifier,
    onVia: () -> Unit,
    onGo: () -> Unit,
    onAvoid: () -> Unit,
    onDismiss: () -> Unit,
    onDetour: () -> Unit = {},
    onStartHere: () -> Unit = {},
) {
  Panel(modifier.widthIn(max = 460.dp).padding(16.dp), padding = 16.dp) {
    Title("Punto sulla mappa", size = 20)
    if (navigating) {
      Caption("Come vuoi passarci?", size = 14)
      Spacer(Modifier.height(10.dp))
      BigButton("Passa di qui e prosegui", Modifier.fillMaxWidth(), Icons.Rounded.AddLocationAlt, onClick = onVia)
      Caption("Ricalcola il percorso da quel punto in poi verso la destinazione (se c'è una strada adatta al mezzo).",
          Modifier.padding(start = 6.dp, top = 4.dp, bottom = 8.dp), size = 13)
      BigButton("Vai lì e torna sul percorso", Modifier.fillMaxWidth(), Icons.Rounded.Navigation, BtnStyle.SECONDARY, onClick = onDetour)
      Caption("Arrivi al punto, poi torni sul percorso calcolato all'inizio e lo segui fino alla destinazione.",
          Modifier.padding(start = 6.dp, top = 4.dp), size = 13)
    } else {
      Caption("Il passaggio va da solo nel punto del viaggio dove allunga meno.", size = 13)
      Spacer(Modifier.height(10.dp))
      BigButton("Passa di qui", Modifier.fillMaxWidth(), Icons.Rounded.AddLocationAlt, onClick = onVia)
    }
    if (!navigating) {
      Spacer(Modifier.height(8.dp))
      BigButton("Vai qui (nuova destinazione)", Modifier.fillMaxWidth(), Icons.Rounded.Navigation, BtnStyle.SECONDARY, onClick = onGo)
      Spacer(Modifier.height(8.dp))
      BigButton("Evita questa zona", Modifier.fillMaxWidth(), Icons.Rounded.Block, BtnStyle.SECONDARY, onClick = onAvoid)
      Spacer(Modifier.height(8.dp))
      // to try a trip from somewhere else: the route is computed from here and can be simulated
      BigButton("Parti da qui (simulazione)", Modifier.fillMaxWidth(), Icons.Rounded.Navigation, BtnStyle.SECONDARY, onClick = onStartHere)
    }
    Spacer(Modifier.height(8.dp))
    BigButton("Annulla", Modifier.fillMaxWidth(), style = BtnStyle.GHOST, onClick = onDismiss)
  }
}

/** Countries where a navigator may not warn about speed cameras (Germany, Switzerland). */
private val CAMERA_WARNINGS_BANNED = setOf("DE", "CH")

/** Countries where only a "control zone" may be shown, not the camera itself (France). */
private val CAMERA_ZONE_ONLY = setOf("FR")

/** A camera mapped with the direction it faces: only the ones that check our direction. */
private fun cameraFacesUs(l: RouteLimit, a: app.navmaster.truck.routing.RouteAnalysis?): Boolean {
  val raw = l.raw?.trim()?.uppercase() ?: return true
  val deg = raw.toDoubleOrNull() ?: mapOf("N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0, "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0)[raw]
      ?: return true
  a ?: return true
  val heading = app.navmaster.truck.core.Geo.bearing(a.pointAt((l.alongM - 15).coerceAtLeast(0.0)), a.pointAt(l.alongM + 15))
  // OSM gives the direction the camera looks at, i.e. towards the traffic it checks
  val facing = kotlin.math.abs(app.navmaster.truck.core.Geo.angleDiff(heading, (deg + 180) % 360))
  return facing < 70
}


/** A composable part of its own: what it reads is redrawn without redrawing the whole screen. */
@Composable
private fun ScopedMap(content: @Composable () -> Unit) = content()

/**
 * The arrow on the map, moved at every frame along the route instead of once a second.
 *
 * Why: the position of the guidance changes once a second (one fix a second), and the map drew it
 * snapped onto the whole route. Where the route passes again close to itself (a U-turn at a
 * roundabout, a loop) the snapping could pick the later part: the arrow jumped ahead and then
 * stood still until the vehicle caught up; standing still, the wandering fixes made it twitch.
 *
 * Here the position comes from the progress of the guidance along the route (metres driven, worked
 * out on the current step, so never on another part of the route), carried forward between two
 * fixes at the speed of the vehicle and corrected softly towards each new one. It never goes
 * backwards, and it stands still when the vehicle does.
 */
private class SmoothTrack {
  var shown = -1.0
  var lastFrame = 0L
  var target = 0.0
  var targetAt = 0L
  var speed = 0.0
  var accuracy = 5.0
  var speedObj: uniffi.ferrostar.Speed? = null

  fun step(t: Long, a: app.navmaster.truck.routing.RouteAnalysis): uniffi.ferrostar.UserLocation {
    val dt = if (lastFrame == 0L) 0.0 else ((t - lastFrame) / 1e9).coerceIn(0.0, 0.1)
    lastFrame = t
    // where the vehicle should be now: the last position of the guidance, carried on at its speed
    val ahead = ((t - targetAt) / 1e9).coerceIn(0.0, 1.2)
    val predicted = target + speed * ahead
    if (shown < 0 || kotlin.math.abs(predicted - shown) > 60) {
      shown = predicted
    } else {
      val rate = (speed + 1.8 * (predicted - shown)).coerceAtLeast(0.0)
      shown += rate * dt
    }
    shown = shown.coerceIn(0.0, a.length)
    val p = a.pointAt(shown)
    val b0 = a.pointAt((shown - 5).coerceAtLeast(0.0))
    val b1 = a.pointAt((shown + 15).coerceAtMost(a.length))
    val brg = ((app.navmaster.truck.core.Geo.bearing(b0, b1) % 360.0) + 360.0) % 360.0
    return uniffi.ferrostar.UserLocation(
        p, accuracy, uniffi.ferrostar.CourseOverGround(brg.toInt().coerceIn(0, 359).toUShort(), 5u.toUShort()),
        java.time.Instant.now(), speedObj)
  }
}
