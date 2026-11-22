package app.navmaster.truck.ui

import app.navmaster.truck.core.SpeedUnit
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.rounded.MoneyOff
import androidx.compose.material.icons.rounded.Euro
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.BrightnessAuto
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
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
  // the microphone for the spoken "sì" / "no" to "c'è ancora?", asked once (Settings › Traffico can turn it off)
  val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
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
  // the route offered aside while driving (toll button, choices changed): its overview beside the guidance
  val offer by vm.offer.collectAsState()
  val trip by AppGraph.trip.collectAsState()
  // without a route the map as while driving (Waze): following the vehicle, its direction up, tilted
  val idleDrive = !navigating && plan.stops.isEmpty() && settings.idleDriveView
  val idleSpeedMs by vm.speedMs.collectAsState()
  LaunchedEffect(navigating, settings.voiceAnswers, settings.liveReports) {
    if (navigating && settings.voiceAnswers && settings.liveReports && !settings.micAsked &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      AppGraph.settings.update { it.copy(micAsked = true) }
      micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }
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
  // the alerts on the map (autovelox, Tutor, semafori, ZTL, pericoli…), always on: read when the data
  // change, written for the map when the driver's choices change (AlertMapData), never per frame
  val alertPackVersion by app.navmaster.truck.limits.AlertPack.version.collectAsState()
  val cameraFilesVersion by app.navmaster.truck.live.CameraFiles.version.collectAsState()
  val importedVersion by app.navmaster.truck.limits.CameraImport.version.collectAsState()
  val alertsAll by androidx.compose.runtime.produceState<List<app.navmaster.truck.limits.AlertPoint>?>(null, installed,
      alertPackVersion, cameraFilesVersion, importedVersion, settings.speedCamerasImported, settings.alertsOnMap) {
    if (!settings.alertsOnMap) { value = null; return@produceState }
    // after the work of the start (crossings snapped): reading thousands of alerts meanwhile made the
    // Java heap be collected while the engine had the most memory mappings, and the app was killed
    kotlinx.coroutines.withTimeoutOrNull(90_000) { AppGraph.backgroundDone.first { it } }
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      runCatching { app.navmaster.truck.limits.AlertMapData.load(AppGraph.regions.withData(), settings.speedCamerasImported) }
          .onFailure { android.util.Log.w("NavMasterAlerts", "alerts: $it") }.getOrNull()
    }
  }
  val alertLayer by androidx.compose.runtime.produceState<app.navmaster.truck.limits.AlertMapData.Layer?>(null, alertsAll,
      settings.alertsOnMap, settings.alertGroups, settings.speedCamerasUnverified, settings.speedMph) {
    val all = alertsAll
    value = if (!settings.alertsOnMap || all == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      runCatching { app.navmaster.truck.limits.AlertMapData.render(all, settings.alertGroups, settings.speedCamerasUnverified,
          settings.deviceTag, context.cacheDir) }.onFailure { android.util.Log.w("NavMasterAlerts", "alerts map: $it") }.getOrNull()
    }
  }
  var openAlert by remember { mutableStateOf<app.navmaster.truck.limits.AlertPoint?>(null) }
  val alertScope = androidx.compose.runtime.rememberCoroutineScope()
  // the places of the driving panel shown on the map (DriveHud.PoiColumn → DrivePoiPins)
  var drivePins by remember { mutableStateOf<List<PoiPin>>(emptyList()) }
  var reportOpen by remember { mutableStateOf(initialSheet == "report") }
  var stopsOpen by remember { mutableStateOf(initialSheet == "stops") }
  var poiOpen by remember { mutableStateOf(initialSheet == "pois") }

  var countryHintClosed by remember { mutableStateOf(false) }
  val trafficTiles = if (settings.liveTraffic && settings.trafficOnMap && app.navmaster.truck.live.ApiKeys.tomtom(settings).isNotBlank())
    app.navmaster.truck.live.TrafficFeeds.tomtomFlowTiles(app.navmaster.truck.live.ApiKeys.tomtom(settings), night) else null
  // the map from the internet where the vehicle or the destination is in a country not downloaded
  val destination = plan.stops.lastOrNull()?.coordinate
  val destCountry = remember(destination?.let { (it.lat * 20).toInt() to (it.lng * 20).toInt() }) {
    destination?.let { AppGraph.catalog.countryAt(it.lat, it.lng) }
  }
  val onlineMap = remember(settings.onlineMap, installed, here?.id, destCountry?.id) {
    val withMap = installed.filter { it.map.exists() }.map { it.id }.toSet()
    settings.onlineMap && (withMap.isEmpty() || (here != null && here.id !in withMap) || (destCountry != null && destCountry.id !in withMap))
  }
  // the light packages of the country where the vehicle is and of the destination's (guidance online)
  LaunchedEffect(settings.onlineLite, settings.onlineLitePoi, here?.id, destCountry?.id, installed) {
    return@LaunchedEffect
    here?.let { AppGraph.regions.ensureLite(it.id, it.name) }
    destCountry?.let { AppGraph.regions.ensureLite(it.id, it.name) }
  }
  // the map's style written off the main thread (reading, rewriting and saving the style JSON froze
  // the screen for a moment at every switch of day/night, satellite or country); the style in use
  // stays until the new one is ready, the first one is made at once
  val styleFirst = remember { MapStyles.styleUri(context, installed, night, satellite, trafficTiles, onlineMap) }
  val styleUri by androidx.compose.runtime.produceState(styleFirst, installed, night, satellite, trafficTiles, onlineMap) {
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      runCatching { MapStyles.styleUri(context, installed, night, satellite, trafficTiles, onlineMap) }.getOrDefault(value)
    }
  }

  // the driving camera as TomTom GO draws it (DriveCamera): tilted, close, the vehicle low on the
  // screen, the horizon in sight; the same at every speed. At roundabouts and junctions the view
  // rises and comes back (driven below, frame by frame, while the vehicle is in their stretch)
  val h = configuration.screenHeightDp
  val w = configuration.screenWidthDp
  val is3d = settings.driveView == app.navmaster.truck.settings.DriveView.VIEW_3D
  // the tilt the tablet's map allows (60°, more if its map underneath accepts it: asked below)
  var mapMaxTilt by remember { mutableStateOf(DriveCamera.MAP_MAX_TILT) }
  val plainView = DriveCamera.plainView(settings, landscape, mapMaxTilt)
  // 2D from above, as far as before (16) at the plain closeness, nearer or farther with it
  val zoom = if (is3d) plainView.second else settings.driveZoom - 2.6
  val tilt = if (is3d) plainView.first else 0.0
  // the vehicle in the middle across, as high as chosen; while the places' column (or, across,
  // the junction view) is open at the right, the view glides aside to leave it room, and back
  val vehicleAt = ((if (landscape) settings.vehicleAcrossPct else settings.vehicleUprightPct) / 100.0).coerceIn(0.3, 0.9)
  var sideRoomTarget by remember { mutableStateOf(0.dp) }
  // lying down the offered route's card takes the right side: the view moves aside for it too
  val offerRoom = if (navigating && offer != null && landscape) (w * 0.40f).dp else 0.dp
  val sideRoom by androidx.compose.animation.core.animateDpAsState(
      if (navigating) maxOf(sideRoomTarget, offerRoom) else 0.dp, androidx.compose.animation.core.tween(700), label = "lato")
  val navPadding = PaddingValues(
      top = (h * (2 * vehicleAt - 1)).coerceAtLeast(0.0).toFloat().dp,
      bottom = (h * (1 - 2 * vehicleAt)).coerceAtLeast(0.0).toFloat().dp,
      end = sideRoom)
  val cameraOptions =
      NavigationCameraOptions(
          browsingZoom = 15.0,
          navigationZoom = zoom,
          navigationTilt = tilt,
          browsingPadding = PaddingValues(0.dp),
          navigationPadding = navPadding,
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
  // without a route: the driving view (idleDrive) or the plain one from above, also right after the end of a guidance
  LaunchedEffect(plan.stops.isEmpty(), navigating, settings.idleDriveView) { if (plan.stops.isEmpty() && !navigating) mapState.recenter(idleDrive) }
  // tests on the tablet: the map moved to a place and zoom (UiHints.view, extra nm_view)
  val testView by UiHints.view.collectAsState()
  LaunchedEffect(testView) {
    val v = testView ?: return@LaunchedEffect
    kotlinx.coroutines.delay(300)
    runCatching {
      mapState.cameraMode = com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode.FREE
      val cam = mapState.cameraState.position
      mapState.cameraState.animateTo(cam.copy(target = org.maplibre.spatialk.geojson.Position(v.second, v.first), zoom = v.third, tilt = 0.0, bearing = 0.0),
          kotlin.time.Duration.parse("600ms"))
    }
    UiHints.view.value = null
  }
  // the buttons show up when the map is touched and go away by themselves
  var lastMapTap by remember { mutableStateOf(System.currentTimeMillis()) }
  var mapMoved by remember { mutableLongStateOf(0L) }
  // a point long-pressed on the map, waiting for "go / pass here / avoid"
  var pendingPoint by remember { mutableStateOf<GeographicCoordinate?>(null) }

  val traveled = if (navigating) (nav.routeLength - (ui.progress?.distanceRemaining ?: 0.0)).coerceAtLeast(0.0) else 0.0
  // only what is a ban for this vehicle, or a limit it passes only just (not a 44 t sign for a 27 t lorry)
  val limitWeightT = garage.active.tripWeightT(garage.loadT)
  val nextLimit = nav.limits.firstOrNull {
    it.kind != "speed_camera" && it.kind != "speed_camera_mobile" && !it.isSection && it.alongM - traveled > -15 &&
        app.navmaster.truck.limits.LimitsIndex.relevant(it, garage.active, limitWeightT)
  }
  // fixed speed cameras: only where warning about them is allowed, facing our way when the map says
  // the cameras that may be announced, worked out once per route (not at every position)
  val camerasOk = remember(nav.limits, nav.analysis, settings.enforcementEverywhere) {
    nav.limits.filter { l ->
      (l.kind == "speed_camera" || l.kind == "speed_camera_mobile") &&
          (settings.enforcementEverywhere || nav.analysis?.edgeAt(l.alongM)?.country?.uppercase() !in CAMERA_WARNINGS_BANNED) &&
          cameraFacesUs(l, nav.analysis)
    }
  }
  val nextCamera = if (!navigating || !settings.speedCameras) null else camerasOk.firstOrNull { l -> l.alongM - traveled in -10.0..800.0 }
  val cameraZoneOnly = nextCamera != null && !settings.enforcementEverywhere &&
      nav.analysis?.edgeAt(nextCamera.alongM)?.country?.uppercase() in CAMERA_ZONE_ONLY
  LaunchedEffect(nextCamera?.alongM, (nextCamera?.alongM?.minus(traveled) ?: 9999.0) < 600) {
    val cam = nextCamera ?: return@LaunchedEffect
    if (cam.alongM - traveled < 600) {
      // the limit with this vehicle, said as the banner shows it (a lorry: 80 where the camera's sign says 130)
      val lim = if (cam.value > 0) limitAt(garage.active, nav.analysis, cam.alongM, cam.value.toInt(), here?.iso) else null
      vm.say(if (cameraZoneOnly) "Zona di controllo della velocità" else
        (cam.cameraWhat?.replaceFirstChar { it.uppercase() } ?: "Autovelox") +
            " tra ${(((cam.alongM - traveled) / 50).roundToInt() * 50).coerceAtLeast(50)} metri" +
            (when {
              lim != null && lim.why != app.navmaster.truck.vehicle.SpeedRules.Why.SIGNED -> ", limite per il tuo mezzo ${SpeedUnit.spoken(lim.kmh)}"
              lim != null -> ", limite ${SpeedUnit.spoken(lim.kmh)}"
              cam.value > 0 -> ", limite ${SpeedUnit.spoken(cam.value.toInt())}"
              else -> ""
            }))
    }
  }
  // sections under average speed control (Tutor): said 1 km before, the average shown inside, said
  // at the end; the limit is the lower of the section's and the vehicle's (a lorry: 80 on motorways)
  val sectionsOk = remember(nav.limits, nav.analysis, settings.enforcementEverywhere) {
    nav.limits.filter { l ->
      l.isSection && (settings.enforcementEverywhere || nav.analysis?.edgeAt(l.alongM)?.country?.uppercase() !in CAMERA_WARNINGS_BANNED)
    }
  }
  val section = if (!navigating || !settings.speedCameras) null else sectionsOk.firstOrNull { l -> traveled < l.endM + 30 && l.alongM - traveled < 1500 }
  val sectionKey = section?.alongM?.toLong()
  val sectionLimit = section?.let { s ->
    limitAt(garage.active, nav.analysis, s.alongM, s.value.toInt().takeIf { it > 0 }, here?.iso)?.kmh ?: garage.active.topSpeedKmh.takeIf { it > 0 }
  }
  val sectionEntry = remember { androidx.compose.runtime.mutableStateMapOf<Long, Pair<Long, Double>>() }
  val inSection = section != null && traveled >= section.alongM
  LaunchedEffect(sectionKey, inSection) {
    if (inSection && sectionKey != null && sectionKey !in sectionEntry) sectionEntry[sectionKey] = System.currentTimeMillis() to traveled
  }
  val sectionAvg = sectionKey?.let { sectionEntry[it] }?.let { (t0, m0) ->
    val s = (System.currentTimeMillis() - t0) / 1000.0
    if (s < 20) null else ((traveled - m0) / s * 3.6).roundToInt()
  }
  LaunchedEffect(sectionKey, section != null && section.alongM - traveled < 1000) {
    val s = section ?: return@LaunchedEffect
    if (s.alongM - traveled in 0.0..1000.0) vm.say("Tra ${app.navmaster.truck.nav.SpeechIt.distance(s.alongM - traveled)} " +
        "inizia un tratto controllato dal Tutor" + (sectionLimit?.let { ", limite ${SpeedUnit.spoken(it)}" } ?: "") + ".")
  }
  val sectionOver = inSection && sectionAvg != null && sectionLimit != null && sectionAvg > sectionLimit + 2 &&
      traveled - (section?.alongM ?: 0.0) > 2000
  LaunchedEffect(sectionKey, sectionOver) {
    if (sectionOver) vm.say("Attenzione: velocità media sopra il limite del Tutor.")
  }
  LaunchedEffect(sectionKey, section != null && traveled >= section.endM) {
    val s = section ?: return@LaunchedEffect
    if (traveled >= s.endM && sectionKey != null && sectionKey in sectionEntry) vm.say("Fine del tratto Tutor" + (sectionAvg?.let { ", media $it" } ?: "") + ".")
  }
  val nextLimitDist = nextLimit?.let { it.alongM - traveled }
  val nextCrit = nav.criticalities.firstOrNull { it.severity != Severity.INFO && it.endM - traveled > -10 && it.startM - traveled < 3000 }

  // spoken notice (calm, no alarm) for a limit over the vehicle's measures, at 3 km and at 800 m
  val spoken = remember { mutableSetOf<String>() }
  LaunchedEffect(nextLimit, nextLimitDist?.let { (it / 100).roundToInt() }) {
    val l = nextLimit ?: return@LaunchedEffect
    val d = nextLimitDist ?: return@LaunchedEffect
    if (!l.blocking) return@LaunchedEffect
    for (at in listOf(3000.0, 800.0)) {
      val key = "${l.lat},${l.lon},$at"
      if (d <= at && d > at - 400 && key !in spoken) {
        spoken += key
        vm.say("Tra ${app.navmaster.truck.nav.SpeechIt.distance(d)} è segnalato: ${l.label} ${l.signValue ?: ""}. " +
            "Verifica la segnaletica." + (if (l.ver == "dubbio") " Il dato è da confermare." else ""))
      }
    }
  }

  // ---- the arrow along the route at every frame (see SmoothTrack)
  val track = remember { SmoothTrack() }
  val smoothLoc = remember { mutableStateOf<uniffi.ferrostar.UserLocation?>(null) }
  // the same position, in metres of the route, for the junction view (read inside its drawing only)
  val smoothAlong = remember { mutableStateOf(-1.0) }
  val trackAnalysis = nav.analysis
  val coreLoc = ui.location
  val measuredSpeed by vm.speedMs.collectAsState()
  // the position on the route from the satellites themselves (SmartLocationProvider), every fix:
  // the guidance's own snapping stands still at the end of a step until the step moves on, then
  // jumps; in the simulation (no satellites) the guidance's position is what there is
  val gpsFix by AppGraph.smartLocation.onRoute.collectAsState()
  // only a position on the route the map is showing: after a recalculation the provider has the
  // new route before the analysis is there, and its distances put on the old line sent the arrow
  // back to the start of the old route
  val trackKey = remember(trackAnalysis) { AppGraph.smartLocation.routeKeyOf(trackAnalysis?.route?.geometry) }
  val fixFresh = gpsFix != null && (System.nanoTime() - gpsFix!!.atNanos) < 4_000_000_000L &&
      trackAnalysis != null && gpsFix!!.alongM <= trackAnalysis.length + 5 && gpsFix!!.routeKey == trackKey
  val simulatingTrack by vm.simulating.collectAsState()
  // the arrow glides along the route only while the satellites' provider puts the vehicle ON the
  // route (in the simulation: while the guidance's position is on it); the moment it leaves it,
  // the arrow shows the real position as it is (a slip road, a yard, a field), never held on the
  // route nor dragged back to it
  val trackOk = navigating && trackAnalysis != null && coreLoc != null &&
      (fixFresh || (simulatingTrack && app.navmaster.truck.core.Geo.dist(trackAnalysis.pointAt(traveled), coreLoc.coordinates) < 60.0))
  // the real position (the provider's last fix) for the map while the vehicle is off the route
  val rawFix by AppGraph.smartLocation.raw.collectAsState()
  // its direction only while moving (2 m/s): manoeuvring slowly in a yard the course of the fixes
  // swings around and the map spun with it; below that the last good direction stays
  val rawBearing = remember { doubleArrayOf(Double.NaN) }
  val rawLoc = remember(rawFix) { rawFix?.let { l ->
    if (l.hasBearing() && l.hasSpeed() && l.speed >= 2f) rawBearing[0] = l.bearing.toDouble()
    val b = rawBearing[0]
    uniffi.ferrostar.UserLocation(
        GeographicCoordinate(l.latitude, l.longitude), if (l.hasAccuracy()) l.accuracy.toDouble() else 10.0,
        if (!b.isNaN()) uniffi.ferrostar.CourseOverGround(b.toInt().coerceIn(0, 359).toUShort(), 5u.toUShort()) else null,
        java.time.Instant.ofEpochMilli(l.time), null)
  } }
  SideEffect {
    if (trackOk) {
      val f = gpsFix
      if (fixFresh && f != null) {
        if (f.alongM != track.target || f.atNanos != track.targetAt) {
          track.target = f.alongM
          track.targetAt = f.atNanos
        }
        track.speed = f.speedMs.coerceIn(0.0, 100.0)
        track.accuracy = f.accuracyM
      } else {
        if (traveled != track.target) {
          track.target = traveled
          track.targetAt = System.nanoTime()
        }
        track.speed = maxOf(coreLoc?.speed?.value ?: 0.0, measuredSpeed ?: 0.0).coerceIn(0.0, 100.0)
        track.accuracy = coreLoc?.horizontalAccuracy ?: 5.0
      }
      track.speedObj = coreLoc?.speed
    }
  }
  // the arrow last drawn (as drawn: the route's one on its lane), whichever of the two below drew
  // it: the other one goes on from there when it takes over, instead of jumping
  val shownNow = remember { arrayOfNulls<uniffi.ferrostar.UserLocation>(1) }
  // the lane shift of the moment (worked out further down), read in the frames
  val laneShiftNow = remember { doubleArrayOf(0.0) }
  LaunchedEffect(trackOk, trackAnalysis) {
    val a = trackAnalysis
    if (!trackOk || a == null) {
      smoothLoc.value = null
      smoothAlong.value = -1.0
      return@LaunchedEffect
    }
    // back on the route after the free arrow (a recalculation, a stretch off the route): from the
    // point of the route beside the arrow shown, when near (the correction then glides it on)
    track.shown = shownNow[0]?.let { s ->
      val (along, d) = app.navmaster.truck.core.Geo.locate(a.route.geometry, a.cum, s.coordinates)
      if (d < 40.0) along else -1.0
    } ?: -1.0
    track.lastFrame = 0L
    while (true) {
      androidx.compose.runtime.withFrameNanos { t ->
        val next = track.step(t, a)
        // as drawn (on the lane of the driving side): the free arrow goes on from there
        shownNow[0] = DriveCamera.laneShift(next, laneShiftNow[0])
        val cur = smoothLoc.value
        // a new position for the map only when the arrow really moved (4 cm) or turned: standing
        // still the map was redrawn 60 times a second for nothing (the tablet heated and slowed down)
        if (cur == null || next == null || kotlin.math.abs(cur.coordinates.lat - next.coordinates.lat) > 4e-7 ||
            kotlin.math.abs(cur.coordinates.lng - next.coordinates.lng) > 4e-7 || cur.courseOverGround?.degrees != next.courseOverGround?.degrees) {
          smoothLoc.value = next
        }
        smoothAlong.value = track.shown
      }
    }
  }

  // ---- the arrow off the route (a road not on the route, a recalculation under way, before the
  // new route is attached) and with no route at all (the map open while driving): it glides at every
  // frame from fix to fix as it does on the route (FreeTrack). Before, it was drawn where each
  // satellite fix put it, once a second: it hopped along the road 20-25 m at a time
  val freeTrack = remember { FreeTrack() }
  val freeLoc = remember { mutableStateOf<uniffi.ferrostar.UserLocation?>(null) }
  // a counter of the fixes fed: the free arrow, asleep while the vehicle stands, wakes up at the next one
  val fixSeq = remember { androidx.compose.runtime.mutableIntStateOf(0) }
  LaunchedEffect(rawFix) {
    rawFix?.let { l ->
      // when it was measured (a fix arrives some hundreds of ms late)
      val ageNs = if (l.elapsedRealtimeNanos > 0) (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos).coerceIn(0L, 2_000_000_000L) else 0L
      freeTrack.feed(l.latitude, l.longitude, if (l.hasSpeed()) l.speed.toDouble() else Double.NaN,
          if (l.hasBearing()) l.bearing.toDouble() else Double.NaN, if (l.hasAccuracy()) l.accuracy.toDouble() else 10.0, System.nanoTime() - ageNs)
      fixSeq.intValue++
    }
  }
  val freeOn = !trackOk && rawFix != null && !(navigating && simulatingTrack)
  LaunchedEffect(freeOn) {
    if (!freeOn) {
      freeLoc.value = null
      return@LaunchedEffect
    }
    freeTrack.seed(shownNow[0])
    while (true) {
      // the vehicle stands and the arrow is there: no frames (the screen is not redrawn 60 times a
      // second for nothing, parked with the map open) until the next fix
      if (freeTrack.settled()) {
        val seen = fixSeq.intValue
        androidx.compose.runtime.snapshotFlow { fixSeq.intValue }.first { it != seen }
        freeTrack.seed(null)
      }
      androidx.compose.runtime.withFrameNanos { t ->
        val next = freeTrack.step(t) ?: return@withFrameNanos
        shownNow[0] = next
        val cur = freeLoc.value
        // only when it moved (4 cm) or turned: standing still the map is not redrawn for nothing
        if (cur == null || kotlin.math.abs(cur.coordinates.lat - next.coordinates.lat) > 4e-7 ||
            kotlin.math.abs(cur.coordinates.lng - next.coordinates.lng) > 4e-7 || cur.courseOverGround?.degrees != next.courseOverGround?.degrees) {
          freeLoc.value = next
        }
      }
    }
  }

  // ---- the view of roundabouts and junctions (DriveCamera): while the vehicle is in their stretch
  // of the route the camera is driven here, frame by frame, eased in and out (the map holding its
  // direction through a roundabout); Ferrostar's own tracking the rest of the time
  val camWindows = remember(trackAnalysis, settings.roundaboutView, settings.roundaboutViewFromM, settings.roundaboutTilt, settings.roundaboutZoom,
      settings.junctionZoomView, settings.junctionZoomFromM, settings.junctionTilt, settings.junctionZoom, settings.junctionZoomInTown,
      settings.junctionZoomTownFromM) {
    trackAnalysis?.let { DriveCamera.windows(it, settings) } ?: emptyList()
  }
  val camDriver = remember { DriveCamera.Driver() }
  // the vehicle on the lane of the driving side (its arrow, the route's line, the camera), as TomTom GO
  val laneLht = (nav.analysis?.edgeAt(traveled)?.country ?: here?.iso)?.uppercase() in setOf("GB", "IE", "CY", "MT", "IM", "GG", "JE")
  val laneShiftM = if (settings.driveLaneSide) (if (laneLht) -DriveCamera.LANE_SHIFT_M else DriveCamera.LANE_SHIFT_M) else 0.0
  val camShift = androidx.compose.runtime.rememberUpdatedState(laneShiftM)
  laneShiftNow[0] = laneShiftM
  val routeDone = androidx.compose.runtime.rememberUpdatedState(settings.doneRoute)
  val routeTraveled = androidx.compose.runtime.rememberUpdatedState(traveled)
  // the length of the route the position is measured on (the line drawn may be only its end: simulation jumps)
  val routeWholeLength = androidx.compose.runtime.rememberUpdatedState(trackAnalysis?.length ?: 0.0)
  val camPlain = androidx.compose.runtime.rememberUpdatedState(Triple(zoom, tilt, navPadding))
  val camTraveled = androidx.compose.runtime.rememberUpdatedState(traveled)
  val camUiLoc = androidx.compose.runtime.rememberUpdatedState(ui.location)
  // the stretches read at each frame: a change of their values (the buttons, the settings) goes on
  // from the view of the moment, it does not start the stretch's easing again
  val camWindowsNow = androidx.compose.runtime.rememberUpdatedState(camWindows)
  LaunchedEffect(navigating, is3d) {
    if (camDriver.active) {
      camDriver.reset()
      if (navigating) mapState.cameraMode = com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode.FOLLOW_USER_WITH_BEARING
    }
    if (!navigating) return@LaunchedEffect
    while (true) {
      androidx.compose.runtime.withFrameNanos { t ->
        val loc = smoothLoc.value?.let { DriveCamera.laneShift(it, camShift.value) } ?: freeLoc.value ?: camUiLoc.value ?: return@withFrameNanos
        val along = smoothAlong.value.takeIf { it >= 0 } ?: camTraveled.value
        val win = DriveCamera.windowAt(camWindowsNow.value, along)
        val cs = mapState.cameraState
        if (!camDriver.active) {
          if (win == null) return@withFrameNanos
          // only from the plain view that follows the vehicle (not while the driver has moved the map)
          if (mapState.cameraMode != com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode.FOLLOW_USER_WITH_BEARING) return@withFrameNanos
          camDriver.active = true
          camDriver.blend = 0.0
          camDriver.lastNanos = t
          camDriver.window = win
          camDriver.heldBearing = cs.position.bearing
          mapState.cameraMode = com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode.FREE
        }
        // the driver's finger on the map: the map is theirs ("Centra" brings the view back)
        if (cs.moveReason == org.maplibre.compose.camera.CameraMoveReason.GESTURE) {
          camDriver.reset()
          return@withFrameNanos
        }
        if (win != null && win != camDriver.window) {
          // the next stretch (a junction right after a roundabout): its view; through a roundabout
          // the direction is held from where its stretch starts
          if (win.roundabout && camDriver.window?.roundabout != true) camDriver.heldBearing = cs.position.bearing
          camDriver.window = win
        }
        val dt = ((t - camDriver.lastNanos) / 1e9).coerceIn(0.0, 0.1)
        camDriver.lastNanos = t
        camDriver.blend = (camDriver.blend + (if (win != null) dt else -dt) / DriveCamera.EASE_S).coerceIn(0.0, 1.0)
        val e = DriveCamera.ease(camDriver.blend)
        val wv = camDriver.window ?: return@withFrameNanos
        val (z0, t0, pad) = camPlain.value
        val course = loc.courseOverGround?.degrees?.toDouble() ?: cs.position.bearing
        val bearing = if (wv.roundabout && !camDriver.heldBearing.isNaN()) DriveCamera.lerpAngle(course, camDriver.heldBearing, e) else course
        val tiltW = if (is3d) wv.tilt else 0.0
        cs.position = org.maplibre.compose.camera.CameraPosition(
            bearing = bearing,
            target = org.maplibre.spatialk.geojson.Position(loc.coordinates.lng, loc.coordinates.lat),
            tilt = t0 + (tiltW - t0) * e,
            zoom = z0 + (wv.zoom - z0) * e,
            padding = pad,
        )
        // out of the stretch and eased back: Ferrostar follows again, from the same view
        if (win == null && camDriver.blend <= 0.0) {
          camDriver.reset()
          mapState.cameraMode = com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode.FOLLOW_USER_WITH_BEARING
        }
      }
    }
  }
  // the tilt over 60° asked of the tablet's map while driving in 3D, as much as its map allows
  // (again now and then: the map's library sets its own limit back when it redraws its options)
  LaunchedEffect(navigating || idleDrive, is3d) {
    if (!(navigating || idleDrive) || !is3d) return@LaunchedEffect
    while (true) {
      mapMaxTilt = DriveCamera.allowTilt(mapState.cameraState, DriveCamera.ASK_TILT)
      kotlinx.coroutines.delay(2000)
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
    // the map's options made once, not at every frame of the arrow
    val mapOptionsFixed = remember { MapOptions(ornamentOptions = OrnamentOptions(isCompassEnabled = false, isScaleBarEnabled = false)) }
    val routeOverlayFixed = remember {
      RouteOverlayBuilder(
          // the route handed to the map once per route, not at every frame (see MapLines)
          navigationPath = { state ->
            // under the names of roads, towns and motorways (the style's slot), over the roads
            UnderLabels {
              // ahead in blue, behind lighter (or hidden), on the lane of the driving side as the arrow
              val g = state.routeGeometry
              NmDrivenRoute(g, smoothAlong.value.takeIf { it >= 0 } ?: routeTraveled.value, camShift.value, g?.firstOrNull()?.lat ?: 45.0,
                  routeDone.value, "nm-route", Nm.Route, 13f, 3f, wholeLengthM = routeWholeLength.value,
                  vehicle = smoothLoc.value?.coordinates)
            }
          })
    }
    val puckStyleFixed = remember {
      NavigationMapPuckStyle(dotFillColorCurrentLocation = Nm.Route, bearingColor = Nm.RouteBorder, dotRadius = 11.dp, dotStrokeWidth = 4.dp)
    }
    val smooth = smoothLoc.value
    val free = freeLoc.value
    NavigationMapView(
        baseStyle = BaseStyle.Uri(styleUri),
        navigationMapState = mapState,
        // the arrow and the camera follow the position worked out here along the route (see
        // SmoothTrack); "off route" only tells the map to use it as it is, without its own snapping
        uiState = when {
          smooth != null -> ui.copy(location = DriveCamera.laneShift(smooth, laneShiftM),
              routeDeviation = uniffi.ferrostar.RouteDeviation.Deviation(uniffi.ferrostar.DeviationKind.OffStepOnRoute(0.0)))
          // off the route while guided, and with no route: the real position, glided from fix to fix
          // (FreeTrack), drawn as it is (no snapping by the map)
          free != null -> ui.copy(location = free, routeDeviation = uniffi.ferrostar.RouteDeviation.Deviation(uniffi.ferrostar.DeviationKind.OffStepOnRoute(0.0)))
          navigating && !simulatingTrack && rawLoc != null -> ui.copy(location = rawLoc, routeDeviation = uniffi.ferrostar.RouteDeviation.Deviation(uniffi.ferrostar.DeviationKind.OffStepOnRoute(0.0)))
          else -> ui
        },
        mapOptions = mapOptionsFixed,
        routeOverlayBuilder = routeOverlayFixed,
        navigationCameraOptions = cameraOptions,
        locationPuckStyle = puckStyleFixed,
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
        // the grey variants, then the blue route over them, all under the names
        UnderLabels {
          plan.variants.forEachIndexed { i, v ->
            if (i != plan.selected) NmRouteLine(v.route.geometry, "nm-alt-$i", Color(0xFF9AA8B8), 8f, 2f, Color(0xFF5B6878))
          }
          plan.current?.let { NmRouteLine(it.route.geometry, "nm-preview", Nm.Route, 11f, 3f) }
        }
        plan.current?.let {
          // where the toll starts and ends, the tunnels
          RouteFeaturePins(it.analysis)
        }
      }
      // the alerts, always (under the markers of the route and the pins)
      AlertLayers(alertLayer, onPick = { openAlert = it }, onCluster = { at ->
        // a bubble: the map two steps closer, centred on it (the map stops following the vehicle)
        alertScope.launch {
          runCatching {
            // the map is the driver's now: the view of a roundabout or a junction stops
            camDriver.reset()
            mapState.cameraMode = com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode.FREE
            val cam = mapState.cameraState.position
            mapState.cameraState.animateTo(cam.copy(target = at, zoom = (cam.zoom + 2.0).coerceAtMost(14.0)))
          }
        }
      })
      // the markers change with the route, not at every position: worked out once (redoing them
      // each second rebuilt the map sources and made the map stutter)
      val crits = if (navigating) nav.criticalities else plan.current?.criticalities ?: emptyList()
      CritMarkers(crits)
      val limitsAll = if (navigating) nav.limits else plan.current?.limits ?: emptyList()
      // on the map too only what matters to this vehicle (a ban, or a limit passed only just)
      LimitMarkers(remember(limitsAll, garage, alertLayer) {
        val w = garage.active.tripWeightT(garage.loadT)
        val alertKinds = if (alertLayer != null) setOf("ztl", "low_emission_zone", "truck_control", "speed_bump", "danger") else emptySet()
        limitsAll.filter { it.kind != "speed_camera" && it.kind != "speed_camera_mobile" && it.kind !in alertKinds && !it.isSection &&
            app.navmaster.truck.limits.LimitsIndex.relevant(it, garage.active, w) }
      })
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
        val alertCams = alertLayer != null && "fisso" in settings.alertGroups
        val cams = remember(camerasOk, settings.speedCameras, step, alertCams) {
          if (!settings.speedCameras || alertCams) emptyList() else camerasOk.filter { l ->
            l.alongM > traveled - 50 && (settings.enforcementEverywhere || nav.analysis?.edgeAt(l.alongM)?.country?.uppercase() !in CAMERA_ZONE_ONLY)
          }
        }
        val livePins = remember(nav.live, step) { nav.live.filter { it.endM > traveled - 50 } }
        RoadPins(cams, livePins) { ev ->
          nav.analysis?.pointAt(ev.startM.coerceAtLeast(0.0))?.let { it.lat to it.lng } ?: (ev.e.lat to ev.e.lon)
        }
        DrivePoiPins(drivePins) { pin -> if (settings.poiPinTap) openPoi = pin.poi }
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
          section = section?.let { Triple(it, sectionLimit, sectionAvg) },
          live = nav.live, liveAsk = nav.liveAsk, cameraAsk = nav.cameraAsk, onSettings = { sheet = Sheet.SETTINGS }, onReport = { reportOpen = true },
          onQuickReport = { vm.quickReport(nav.analysis?.edgeAt(traveled)?.country ?: here?.iso) },
          pendingReport = nav.pendingReport, onUndoReport = vm::undoReport, liveListening = nav.liveListening,
          stopsCount = plan.stops.size - 1, onStops = { stopsOpen = true }, onPois = { poiOpen = true },
          destination = plan.stops.lastOrNull()?.coordinate, onPoiPins = { drivePins = it },
          satellite = satellite, onSatellite = { satellite = !satellite }, smoothAlong = smoothAlong,
          onSideRoom = { if (sideRoomTarget != it) sideRoomTarget = it },
          tollsAvoided = trip.avoidTolls, onTolls = vm::toggleTollsWhileDriving)
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
      // the route offered aside: lying down at the right, upright above the trip bar; the guidance goes on
      offer?.let { o ->
        RouteOfferCard(o, styleUri, onAccept = vm::acceptOffer, onDismiss = vm::dismissOffer,
            modifier = if (landscape) Modifier.align(Alignment.CenterEnd).statusBarsPadding().navigationBarsPadding()
                .padding(end = 12.dp, top = 8.dp, bottom = 8.dp).fillMaxWidth(0.38f)
            else Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 96.dp).fillMaxWidth(),
            mapHeight = if (landscape) (h * 0.36f).toInt().coerceIn(140, 320) else (h * 0.24f).toInt().coerceIn(150, 300))
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
          onRecenter = { mapState.recenter(idleDrive) },
          onCrit = { openCrit = it },
          idleDrive = idleDrive,
          speedKmh = (location?.speed?.value?.takeIf { it >= 0 } ?: idleSpeedMs)?.let { (it * 3.6).roundToInt() },
          speedWarningKmh = settings.speedWarningKmh,
          tollsAvoided = settings.tollPolicy == app.navmaster.truck.settings.TollPolicy.AVOID,
          onTolls = vm::toggleTollsSetting,
          tracking = mapState.isTrackingUser || DriveCamera.driving.value,
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

    // the card of an alert touched on the map; it closes by itself after a while
    openAlert?.let { a ->
      LaunchedEffect(a) {
        kotlinx.coroutines.delay(20_000)
        openAlert = null
      }
      val me = location?.coordinates
      AlertCard(a, me?.let { app.navmaster.truck.limits.Alerts.dist(it.lat, it.lng, a.lat, a.lon) },
          Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 92.dp, start = 16.dp, end = 16.dp)) { openAlert = null }
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
      WelcomeScreen(here, onOnline = { AppGraph.settings.update { it.copy(onlineWithoutMaps = true) } }) { sheet = Sheet.REGIONS }
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
    /** Without a route, the map as while driving: the speedometer too. */
    idleDrive: Boolean = false,
    speedKmh: Int? = null,
    speedWarningKmh: Int = 0,
    /** The toll button: tolls avoided now (the choice of the trips: the routes on screen are computed again). */
    tollsAvoided: Boolean = false,
    onTolls: () -> Unit = {},
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
      TollButton(tollsAvoided, onTolls)
    }
    // without a route, the speedometer as while driving (no limit: there is no route to read it on)
    if (idleDrive && plan.stops.isEmpty()) {
      SpeedArc(speedKmh, null, speedWarningKmh, null, null, 120.dp, false, Modifier.align(Alignment.BottomStart).padding(bottom = 48.dp))
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
    cameraAsk: RouteLimit? = null,
    onSettings: () -> Unit = {},
    onReport: () -> Unit = {},
    onQuickReport: () -> Unit = {},
    /** The vehicle along the route at every frame (metres; -1 when it is off the route), as the map's arrow. */
    smoothAlong: androidx.compose.runtime.State<Double>? = null,
    pendingReport: app.navmaster.truck.live.LiveKind? = null,
    onUndoReport: () -> Unit = {},
    liveListening: Boolean = false,
    stopsCount: Int = 0,
    onStops: () -> Unit = {},
    onPois: () -> Unit = {},
    section: Triple<RouteLimit, Int?, Int?>? = null,
    destination: GeographicCoordinate? = null,
    onPoiPins: (List<PoiPin>) -> Unit = {},
    /** How much room the right side takes now (the places' column, or across the junction view): the map's view moves aside. */
    onSideRoom: (Dp) -> Unit = {},
    satellite: Boolean = false,
    onSatellite: () -> Unit = {},
    /** The toll button: the trip avoids tolls now; a touch offers the route with the other choice aside. */
    tollsAvoided: Boolean = false,
    onTolls: () -> Unit = {},
) {
  val simulating by vm.simulating.collectAsState()
  val simSpeed by vm.simSpeed.collectAsState()
  // the buttons (report, settings, stops at the left; view, satellite, day/night, voice, end at the
  // bottom right): away while driving, one touch on the map brings them, another touch puts them
  // away (and so does doing nothing for 12 s); shown at the start for a moment
  var controls by remember { mutableStateOf(true) }
  val firstTap = remember { lastMapTap }
  LaunchedEffect(lastMapTap) { if (lastMapTap != firstTap) controls = !controls }
  // a touch on the bar of the controls keeps it open a little longer
  var barTouch by remember { mutableStateOf(0L) }
  LaunchedEffect(controls, barTouch) {
    if (controls) {
      delay(12_000)
      controls = false
    }
  }
  // the lane the vehicle is really in (from the satellites, nav.LaneTracker): on motorways and
  // slip roads, whose line is the middle of the carriageway; elsewhere the lane is not guessed
  val hereEdge = analysis?.edgeAt(traveled)
  val hereLanes = hereEdge?.lanes ?: 0
  val vehicleLane = if (hereEdge != null && (hereEdge.roadClass in setOf("motorway", "trunk") || hereEdge.isRamp) && hereLanes in 2..6)
    vm.laneTracker.lane(hereLanes, false, countryIso?.uppercase() in LEFT_HAND) else null
  val measured by vm.speedMs.collectAsState()
  // junction view and lane guidance only at motorway junctions and complicated multi-lane ones
  val scene = if (!settings.junctionView) null else junctionSceneOf(ui.visualInstruction, ui.progress?.distanceToNextManeuver, analysis, traveled, countryIso, vehicleLane,
      settings.junctionViewMotorwayM.toDouble(), settings.junctionViewRoadM.toDouble(), settings.junctionViewSeconds.toDouble(),
      (ui.location?.speed?.value?.takeIf { it >= 0 } ?: measured ?: 0.0))
  // the junction view closed with ✕ stays closed for that junction (and the lane strip comes back
  // in its place: the closing used to live inside the view, which left the screen without lanes)
  var jvClosed by remember { mutableStateOf<Pair<Any, Long>?>(null) }
  val jvKey = scene?.let { it.route.key to Math.round(it.maneuverAtM / 60.0) }
  val jv = scene?.takeIf { jvKey != jvClosed }
  // the room at the right for the map's view to keep clear: the junction view's column across, else the places' column while it is open
  var poiRoom by remember { mutableStateOf(0.dp) }
  val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
  val sideRoomNow = if (landscape && jv != null) (screenW * 0.42f).dp else poiRoom
  SideEffect { onSideRoom(sideRoomNow) }
  // the roundabout of the next manoeuvre as it is in the graph (worked out once per roundabout, off the main thread)
  val nextType = ui.visualInstruction?.primaryContent?.maneuverType?.name?.uppercase() ?: ""
  val ringAt = if (analysis != null && (nextType.contains("ROUNDABOUT") || nextType.contains("ROTARY")))
    ui.progress?.distanceToNextManeuver?.let { traveled + it } else null
  val ringKey = ringAt?.let { (it / 25.0).toLong() }
  val roundabout by androidx.compose.runtime.produceState<RoundaboutShape?>(null, analysis, ringKey) {
    val an = analysis
    val at = ringAt
    value = if (an == null || at == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
      runCatching { RoundaboutShape.of(JvRoute.of(an), at) }.getOrNull()
    }
  }
  // the lanes to keep, from 2 km before a motorway exit, fork or merge (400 m in town)
  val laneGuide = laneGuideOf(ui.visualInstruction, ui.progress?.distanceToNextManeuver, analysis, traveled)
  // the vehicle's lane among the lanes drawn (the same count, or one exit lane added on the right)
  val stripLane = vehicleLane?.takeIf { v ->
    laneGuide != null && laneGuide.plan.lanes.count { !it.merge }.let { n -> n == hereLanes || (n == hereLanes + 1 && laneGuide.plan.side > 0) }
  }
  // in the wrong lane with the manoeuvre coming: said once per manoeuvre ("spostati a destra")
  val laneKey = laneGuide?.let { (traveled + it.distanceM).toLong() / 100 }
  LaunchedEffect(laneKey, stripLane, laneGuide != null && laneGuide.distanceM < 700) {
    val g = laneGuide ?: return@LaunchedEffect
    val l = stripLane ?: return@LaunchedEffect
    val lanes = g.plan.lanes.filter { !it.merge }
    if (l !in lanes.indices || lanes[l].take || g.distanceM !in 200.0..700.0) return@LaunchedEffect
    val nearest = lanes.indices.filter { lanes[it].take }.minByOrNull { kotlin.math.abs(it - l) } ?: return@LaunchedEffect
    val k = kotlin.math.abs(nearest - l)
    vm.say("Cambia corsia: spostati " + (if (nearest > l) "a destra" else "a sinistra") + (if (k > 1) " di $k corsie" else "") + ".")
  }
  val fastRoad = analysis?.edgeAt(traveled)?.roadClass in setOf("motorway", "trunk")
  val nextLive = live.firstOrNull { it.endM - traveled > -20 && it.startM - traveled < (if (fastRoad) 5000.0 else 2000.0) }
  // the speed the GPS gives, or measured from the last positions when it gives none; none at all
  // while the position is only worked out along the route (a tunnel: the speed of the entry is not
  // the real one) or when the satellites have been silent for 3 s (it stayed frozen on the last value)
  val reckoningNow by AppGraph.smartLocation.reckoning.collectAsState()
  val lastRaw by AppGraph.smartLocation.raw.collectAsState()
  val simulatingNow by vm.simulating.collectAsState()
  var speedStale by remember { mutableStateOf(false) }
  LaunchedEffect(lastRaw) {
    speedStale = false
    kotlinx.coroutines.delay(3_000)
    speedStale = true
  }
  val speedKmh = if (!simulatingNow && (reckoningNow || speedStale)) null
      else (ui.location?.speed?.value?.takeIf { it >= 0 } ?: measured)?.let { (it * 3.6).roundToInt() }
  // the limit to RESPECT here: the lowest of the signs (the route's annotation), the law for this
  // vehicle on this kind of road in this country, and the vehicle's own maximum; one number for the
  // sign, the speedometer, the trip bar and the warnings (SpeedRules)
  val signedKmh = ui.currentAnnotation?.speedLimit?.value(MeasurementSpeedUnit.KilometersPerHour)?.roundToInt()
  val limit = limitAt(vehicle, analysis, traveled, signedKmh, countryIso)
  val limitKmh = limit?.kmh
  androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(10.dp)) {
  // the junction view fits the screen: about a third of the height upright, never taller than wide
  val jvHeight = (maxHeight.value * 0.40f).coerceIn(200f, 400f).coerceAtMost(maxWidth.value * 0.78f).dp
  // the speedometer (DriveHud.SpeedArc): about a third of the short side, never so small it cannot
  // be read nor so big it hides the road; smaller while the junction view is open
  val shortSide = minOf(maxWidth, maxHeight)
  // small while all is well; big for a few seconds when the speed goes over the limit (with an
  // animation), then small again
  val arcFull = (shortSide * 0.36f).coerceIn(150.dp, 230.dp)
  val arcSmall = (shortSide * 0.24f).coerceIn(112.dp, 150.dp)
  var poiPanelOpen by remember { mutableStateOf(false) }
  val overLimit = speedKmh != null && limitKmh != null && speedKmh - limitKmh >= 1
  var arcBig by remember { mutableStateOf(false) }
  LaunchedEffect(overLimit) {
    if (overLimit) {
      arcBig = true
      delay(5_000)
      arcBig = false
    } else arcBig = false
  }
  val arcTarget = when {
    jv != null -> arcSmall
    arcBig -> arcFull
    else -> arcSmall
  }
  val arcW by androidx.compose.animation.core.animateDpAsState(arcTarget, label = "arc")
  val arcH = arcW * 0.733f
  // beside the manoeuvre bar when lying down without the junction view; else at the top right of
  // the map, under the bar and its banners
  val arcBeside = landscape && jv == null
  val muted = ui.isMuted == true
  val sectionAvg = section?.third
  val sectionLimit = section?.second
  val speedArc: @Composable (Modifier) -> Unit = { m ->
    SpeedArc(speedKmh, limit, settings.speedWarningKmh, sectionAvg, sectionLimit, arcW, muted, m,
        onSay = { vm.say(it) })
  }
  // under the manoeuvre bar, RIGHT under it: the lane bar from 2 km (once the junction view is
  // open it carries the lanes itself), upright the junction view, then the banners
  val underManeuver: @Composable () -> Unit = {
    if (laneGuide != null && jv == null) {
      LaneStrip(laneGuide, (if (landscape) Modifier.widthIn(max = 640.dp) else Modifier).fillMaxWidth().padding(top = 6.dp),
          vehicleLane = stripLane)
    }
    if (jv != null && !landscape) JunctionView(jv, night, Modifier.fillMaxWidth().padding(top = 6.dp), height = jvHeight, vehAlong = smoothAlong,
        onClose = { jvClosed = jvKey })
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (nextLimit != null && nextLimitDist != null && nextLimitDist < (if (nextLimit.blocking) 10_000.0 else 5_000.0)) {
        RestrictionBanner(nextLimit, nextLimitDist, vehicleValue(nextLimit, vehicle))
      } else if (nextCrit != null) {
        CritBanner(nextCrit, (nextCrit.startM - traveled).coerceAtLeast(0.0)) { onCrit(nextCrit) }
      }
      if (booth != null) BoothBanner(booth.first, booth.second, booth.third)
      if (camera != null) CameraBanner(camera, (camera.alongM - traveled).coerceAtLeast(0.0), cameraZoneOnly,
          limit = if (camera.value > 0) limitAt(vehicle, analysis, camera.alongM, camera.value.toInt(), countryIso) else null)
      if (section != null) SectionBanner(section.first, traveled, section.second, section.third)
      if (nextLive != null) LiveBanner(nextLive, (nextLive.startM - traveled).coerceAtLeast(0.0), analysis?.edgeAt(nextLive.startM)?.country)
    }
  }
  val is3d = settings.driveView == app.navmaster.truck.settings.DriveView.VIEW_3D
  val nightMode = settings.nightMode
  // the controls needed now and then: in one bar that a touch on the map brings up
  // nearer, farther, more or less tilted: the plain view and the views of roundabouts and
  // junctions together (one of these is often on near a junction), kept in the settings
  // (Mappa e vista), the map following at once
  fun closer(by: Double) {
    barTouch = System.currentTimeMillis()
    val now = AppGraph.settings.settings.value.driveZoom
    val next = (now + by).coerceIn(15.5, 19.5)
    fun r(v: Double) = Math.round(v * 10) / 10.0
    AppGraph.settings.update {
      it.copy(driveZoom = r(next), roundaboutZoom = r((it.roundaboutZoom + by).coerceIn(15.0, 19.5)),
          junctionZoom = r((it.junctionZoom + by).coerceIn(15.0, 19.5)))
    }
    if (next != now && mapState.isTrackingUser) runCatching { if (by > 0) mapState.zoomIn(next - now) else mapState.zoomOut(now - next) }
  }
  fun tilted(by: Int) {
    barTouch = System.currentTimeMillis()
    AppGraph.settings.update {
      it.copy(driveTilt = (it.driveTilt + by).coerceIn(30, DriveCamera.MAX_SETTING_TILT), roundaboutTilt = (it.roundaboutTilt + by).coerceIn(0, 70),
          junctionTilt = (it.junctionTilt + by).coerceIn(0, DriveCamera.MAX_SETTING_TILT))
    }
  }
  val bar = listOf(
      BarButton("Vista", null, if (is3d) "2D" else "3D", Nm.Raised) {
        barTouch = System.currentTimeMillis()
        AppGraph.settings.update {
          it.copy(driveView = if (is3d) app.navmaster.truck.settings.DriveView.VIEW_2D else app.navmaster.truck.settings.DriveView.VIEW_3D)
        }
        mapState.recenter(true)
      },
      BarButton("Zoom +", null, "+", Nm.Raised) { closer(0.2) },
      BarButton("Zoom −", null, "−", Nm.Raised) { closer(-0.2) },
  ) + (if (is3d) listOf(
      BarButton("Più incl.", null, "+°", Nm.Raised) { tilted(5) },
      BarButton("Meno incl.", null, "−°", Nm.Raised) { tilted(-5) },
  ) else emptyList()) + listOf(
      // tolls yes / no: the route with the other choice computed and offered aside, the guidance going on
      BarButton(if (tollsAvoided) "Senza ped." else "Pedaggi", if (tollsAvoided) Icons.Rounded.MoneyOff else Icons.Rounded.Euro, null,
          if (tollsAvoided) Nm.Amber else Nm.Raised) {
        barTouch = System.currentTimeMillis()
        onTolls()
      },
      BarButton(if (satellite) "Mappa" else "Satellite", Icons.Rounded.Layers, null, if (satellite) Nm.Accent else Nm.Raised) {
        barTouch = System.currentTimeMillis()
        onSatellite()
      },
      BarButton(
          when (nightMode) {
            app.navmaster.truck.settings.NightMode.AUTO -> "Auto"
            app.navmaster.truck.settings.NightMode.DAY -> "Giorno"
            app.navmaster.truck.settings.NightMode.NIGHT -> "Notte"
          },
          when (nightMode) {
            app.navmaster.truck.settings.NightMode.AUTO -> Icons.Rounded.BrightnessAuto
            app.navmaster.truck.settings.NightMode.DAY -> Icons.Rounded.LightMode
            app.navmaster.truck.settings.NightMode.NIGHT -> Icons.Rounded.DarkMode
          },
          null, Nm.Raised,
      ) {
        barTouch = System.currentTimeMillis()
        AppGraph.settings.update {
          it.copy(nightMode = when (it.nightMode) {
            app.navmaster.truck.settings.NightMode.AUTO -> app.navmaster.truck.settings.NightMode.DAY
            app.navmaster.truck.settings.NightMode.DAY -> app.navmaster.truck.settings.NightMode.NIGHT
            app.navmaster.truck.settings.NightMode.NIGHT -> app.navmaster.truck.settings.NightMode.AUTO
          })
        }
      },
      BarButton(if (muted) "Muto" else "Voce", if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, null,
          if (muted) Nm.Red else Nm.Raised) {
        barTouch = System.currentTimeMillis()
        vm.toggleMute()
      },
      BarButton("Termina", Icons.Rounded.Close, null, Nm.Red) { vm.stopNavigation() },
  )
  // the manoeuvre bar small far from the manoeuvre (its sign and the distance), whole near it
  // (the seconds chosen at the present speed, at least 250 m) or for a while after a touch
  var barPeek by remember { mutableStateOf(false) }
  LaunchedEffect(barPeek) { if (barPeek) { delay(8_000); barPeek = false } }
  val toManeuverM = ui.progress?.distanceToNextManeuver
  val openAtM = maxOf(250.0, (speedKmh ?: 0) / 3.6 * settings.maneuverOpenS)
  val compactBar = settings.maneuverCompact && !barPeek && toManeuverM != null && toManeuverM > openAtM && laneGuide == null && jv == null
  val maneuverMod = Modifier.animateContentSize().then(if (compactBar) Modifier else Modifier.fillMaxWidth())
  Column(Modifier.fillMaxSize()) {
  Row(Modifier.weight(1f).fillMaxWidth()) {
  Column(Modifier.weight(1f).fillMaxHeight()) {
    if (arcBeside) {
      // lying down: the speedometer beside the manoeuvre bar, the lanes and banners under the bar
      Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
          TopManeuverBar(ui.visualInstruction, ui.progress?.distanceToNextManeuver, maneuverMod, showLanes = false, roundabout = roundabout,
              compact = compactBar, onTap = { barPeek = true })
          underManeuver()
        }
        Spacer(Modifier.width(10.dp))
        speedArc(Modifier)
      }
    } else {
      TopManeuverBar(ui.visualInstruction, ui.progress?.distanceToNextManeuver, maneuverMod, showLanes = false, roundabout = roundabout,
          compact = compactBar, onTap = { barPeek = true })
      underManeuver()
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
      val spm = ui.progress?.let { pr -> if (pr.distanceRemaining > 1) pr.durationRemaining / pr.distanceRemaining else null }
      val route = analysis?.let { it.route.geometry }
      val hereNow = androidx.compose.runtime.rememberUpdatedState(ui.location?.coordinates)
      val source = remember(route, destination) { AppPoiSource(route, { hereNow.value }, destination) }
      val tracking = mapState.isTrackingUser || DriveCamera.driving.value
      // the round buttons at the left: with the controls (a touch on the map) report, settings,
      // stops; always "centre" when the map was moved and the reminder when muted
      val railCount = (if (controls) (if (settings.liveReports) 1 else 0) + 1 + (if (stopsCount > 0) 1 else 0) else 0) +
          (if (!tracking) 1 else 0) + (if (!controls && muted) 1 else 0)
      DriveSide(
          landscape = landscape,
          arcInside = !arcBeside,
          arcHeight = arcH,
          arcWidth = arcW,
          showPoi = jv == null,
          railCount = railCount,
          railSize = if (poiPanelOpen) 46.dp else 56.dp,
          // the bar of the controls is at the bottom of the screen, over the trip bar (never over the vehicle)
          barVisible = false,
          bar = bar,
          barBottom = if (simulating) 84.dp else 6.dp,
          // the simulation's label and the speed limit's sign at the top left: the round buttons stay under them
          leftTopReserve = (if (simulating) 40.dp else 6.dp) + (if (settings.speedLimitSide && limit != null) speedLimitSignHeight(limit) else 0.dp),
          leftTopWidth = maxOf(if (settings.speedLimitSide && limit != null) speedLimitSignWidth(limit) else 0.dp, if (simulating) 124.dp else 0.dp),
          arc = { speedArc(Modifier) },
          poi = { w, h ->
            PoiColumn(
                pois = pois,
                traveledM = traveled,
                source = source,
                settings = settings,
                hasHere = ui.location != null,
                hasDestination = destination != null,
                secondsPerMetre = spm,
                canType = simulating || (speedKmh ?: 0) < 8,
                width = w,
                maxHeight = h,
                onPoi = onPoi,
                onAll = onPois,
                onPins = onPoiPins,
                onOpen = { poiPanelOpen = it },
                onShown = { shown -> poiRoom = if (shown) w + 12.dp else 0.dp },
                onRemember = { text ->
                  AppGraph.settings.update { st -> st.copy(poiRecent = (listOf(text) + st.poiRecent.filter { !it.equals(text, true) }).take(5)) }
                },
            )
          },
          rail = { size ->
            // with the controls: report something on the road (touch: what; long press: police at
            // once, undoable), the settings, the stops; always "centre" when the map was moved by hand
            if (controls && settings.liveReports) {
              RoundBtn(size, Nm.Amber, "Segnala", onLongClick = onQuickReport, onClick = { barTouch = System.currentTimeMillis(); onReport() }) {
                Text("⚠", fontSize = (size.value * 0.46f).sp, color = Color.Black)
              }
            }
            if (controls) {
              RoundBtn(size, Nm.PanelSolid, "Impostazioni", onClick = onSettings) {
                Icon(Icons.Rounded.Settings, null, tint = Nm.Text, modifier = Modifier.size(size * 0.48f))
              }
            }
            if (controls && stopsCount > 0) StopsButton(stopsCount, onStops)
            if (!tracking) {
              RoundBtn(size, Nm.PanelSolid, "Centra", onClick = { mapState.recenter(true) }) {
                Icon(Icons.Rounded.MyLocation, null, tint = Nm.Text, modifier = Modifier.size(size * 0.48f))
              }
            }
            // muted: a small reminder stays while the bar is away
            if (!controls && muted) {
              RoundBtn(maxOf(size * 0.86f, 44.dp), Nm.Red, "Voce disattivata", onClick = { vm.toggleMute() }) {
                Icon(Icons.Rounded.VolumeOff, null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
              }
            }
          },
      )
      if (jv != null) LaunchedEffect(Unit) { onPoiPins(emptyList()) }
      // the speed limit always in sight at the left, under the simulation's label
      if (settings.speedLimitSide && limit != null) {
        SpeedLimitSign(limit, speedKmh, settings.speedWarningKmh,
            Modifier.align(Alignment.TopStart).padding(top = if (simulating) 40.dp else 6.dp))
      }
      if (simulating) {
        SimControls(simSpeed, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            onPrevManeuver = { vm.simManeuver(false) }, onBack = { vm.simJump(-500.0) }, onSlower = vm::simSlower,
            onFaster = vm::simFaster, onAhead = { vm.simJump(500.0) }, onNextManeuver = { vm.simManeuver(true) })
      }
      if (pendingReport != null) {
        PendingReportCard(pendingReport, countryIso, onUndo = onUndoReport,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (simulating) 84.dp else 12.dp))
      } else if (liveAsk != null) {
        LiveAskCard(liveAsk, analysis?.edgeAt(liveAsk.startM)?.country, onAnswer = { yes -> vm.voteLive(liveAsk, yes) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (simulating) 84.dp else 12.dp), listening = liveListening)
      } else if (cameraAsk != null) {
        CameraAskCard(cameraAsk, onAnswer = { yes -> vm.voteCamera(cameraAsk, yes) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (simulating) 84.dp else 12.dp), listening = liveListening)
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
    JunctionView(jv, night, Modifier.weight(0.72f).fillMaxHeight().padding(start = 10.dp, bottom = 8.dp), fill = true, vehAlong = smoothAlong,
        onClose = { jvClosed = jvKey })
  }
  }
    val drivenS = vm.nav.collectAsState().value.drivenS
    // the town the vehicle is in, looked up every few hundred metres off the main thread
    var locality by remember { mutableStateOf<String?>(null) }
    val locKey = ui.location?.coordinates?.let { (it.lat * 300).roundToInt() to (it.lng * 300).roundToInt() }
    LaunchedEffect(locKey, settings.showLocality) {
      val c = ui.location?.coordinates
      locality = if (c == null || !settings.showLocality) null
          else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { AppGraph.addresses.localityAt(c.lat, c.lng) }.getOrNull() }
    }
    val nextStop = vm.nextStopAhead(traveled)
    val remainingM = ui.progress?.distanceRemaining
    val remainingS = ui.progress?.durationRemaining
    TripBar(
        fields = settings.tripFields.mapNotNull { TripField.of(it) }.ifEmpty { listOf(TripField.SPEED, TripField.ARRIVAL, TripField.DIST_LEFT, TripField.TIME_LEFT) },
        data = TripData(
            speedKmh = speedKmh,
            limitKmh = limitKmh,
            toleranceKmh = settings.speedWarningKmh,
            remainingM = remainingM,
            remainingS = remainingS,
            nextStopM = nextStop?.let { it - traveled },
            nextStopS = nextStop?.let { at -> if (remainingM != null && remainingS != null && remainingM > 1) remainingS * (at - traveled) / remainingM else null },
            drivenS = drivenS,
            headingDeg = ui.location?.courseOverGround?.degrees?.toDouble(),
            road = ui.currentStepRoadName,
            place = locality,
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
  // the controls a touch on the map brings up: over the trip bar at the bottom, never over the
  // vehicle in the middle of the map
  ControlsBar(controls, bar, maxWidth, Modifier.align(Alignment.BottomEnd))
  }
}

/**
 * The limit to respect for [v] at [alongM] metres of the route [a] where the signs say [signed] (null:
 * the map has no number), from the road there (its class, its country, a slip road or not).
 */
internal fun limitAt(v: VehicleProfile, a: app.navmaster.truck.routing.RouteAnalysis?, alongM: Double, signed: Int?, fallbackCountry: String?):
    app.navmaster.truck.vehicle.SpeedRules.Limit? {
  val e = a?.edgeAt(alongM.coerceAtLeast(0.0))
  return app.navmaster.truck.vehicle.SpeedRules.at(v, e?.country ?: fallbackCountry, e?.roadClass, signed, ramp = e?.isRamp == true)
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
private val LEFT_HAND = setOf("GB", "IE", "CY", "MT", "IM", "GG", "JE")

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
/**
 * The arrow glides along the route at every frame. The target is the last position on the route
 * (from the satellites, SmartLocationProvider.onRoute; in the simulation the guidance's), carried
 * on at its speed since it came; the arrow chases it gently: never backwards, never with a lurch
 * (the correction is a fraction of the speed), and jumps only when the target is far away (a
 * recalculation, a position lost for a long time). Times are System.nanoTime, the clock of
 * the frames and of the fixes.
 */
private class SmoothTrack {
  var shown = -1.0
  var lastFrame = 0L
  var target = 0.0
  var targetAt = 0L
  var speed = 0.0
  var accuracy = 5.0
  var speedObj: uniffi.ferrostar.Speed? = null
  private var lastBrg = -1.0

  fun step(t: Long, a: app.navmaster.truck.routing.RouteAnalysis): uniffi.ferrostar.UserLocation {
    val dt = if (lastFrame == 0L) 0.0 else ((t - lastFrame) / 1e9).coerceIn(0.0, 0.1)
    lastFrame = t
    // where the vehicle should be now: the last position, carried on at its speed, at most 1.5 s
    // (a receiver that skips a fix now and then must not send the arrow ahead of the vehicle: it
    // waits instead; longer silences are the tunnels, where the provider itself moves the position
    // along, and the speed is taken as falling a little, as it does before a turn)
    val ahead = ((t - targetAt) / 1e9).coerceIn(0.0, 1.5)
    val predicted = target + speed * ahead * (1.0 - 0.1 * ahead)
    val err = predicted - shown
    if (shown < 0 || kotlin.math.abs(err) > 50) {
      shown = predicted
    } else if (err < -12) {
      // clearly ahead of the vehicle (a fix that was too far on, a speed too high, a silence of the
      // receiver carried on too far): back, the faster the farther, up to 40% of the speed + 2 m/s
      // (a few seconds, not a minute as before: a wrong arrow is worse than one sliding back)
      shown += maxOf(err * 1.2, -(0.4 * speed + 2.0)) * dt
    } else {
      // behind the target: a little faster (up to +60% of the speed, +3 m/s); ahead of it: slower,
      // down to standing still
      val corr = if (err >= 0) minOf(err * 1.2, 0.6 * speed + 3.0) else maxOf(err * 1.2, -(speed + 0.5))
      shown += (speed + corr).coerceAtLeast(0.0) * dt
    }
    shown = shown.coerceIn(0.0, a.length)
    val p = a.pointAt(shown)
    val b0 = a.pointAt((shown - 5).coerceAtLeast(0.0))
    val b1 = a.pointAt((shown + 15).coerceAtMost(a.length))
    // standing still the heading stays as it was (the arrow does not turn on the spot)
    if (speed >= 0.8 || lastBrg < 0) lastBrg = ((app.navmaster.truck.core.Geo.bearing(b0, b1) % 360.0) + 360.0) % 360.0
    return uniffi.ferrostar.UserLocation(
        p, accuracy, uniffi.ferrostar.CourseOverGround(lastBrg.toInt().coerceIn(0, 359).toUShort(), 5u.toUShort()),
        java.time.Instant.now(), speedObj)
  }
}

/**
 * Tolls yes / no in one touch, outside the guidance: the choice of the trips (Impostazioni › Percorso
 * e pedaggi); the routes on screen, or still being computed, are computed again with it.
 */
@Composable
private fun TollButton(avoided: Boolean, onClick: () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    RoundAction(if (avoided) Icons.Rounded.MoneyOff else Icons.Rounded.Euro, if (avoided) "Senza pedaggi: tocca per ammetterli" else "Pedaggi ammessi: tocca per evitarli",
        container = if (avoided) Nm.Amber else Nm.Panel, tint = if (avoided) Color.Black else Nm.Text, onClick = onClick)
    Text(if (avoided) "No pedaggi" else "Pedaggi", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 1.dp))
  }
}
