package app.navmaster.truck.ui

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
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeedUnit
import com.stadiamaps.ferrostar.maplibreui.NavigationMapClickResult
import com.stadiamaps.ferrostar.maplibreui.NavigationMapPuckStyle
import com.stadiamaps.ferrostar.maplibreui.NavigationMapView
import com.stadiamaps.ferrostar.maplibreui.routeline.BorderedPolyline
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
      "settings", "settings_poi", "settings_map" -> Sheet.SETTINGS
      "regions" -> Sheet.REGIONS
      "search", "search_guided" -> Sheet.SEARCH
      else -> Sheet.NONE
    })
  }
  val searchGuided = initialSheet == "search_guided"
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
  var countryHintClosed by remember { mutableStateOf(false) }
  val styleUri = remember(installed, night, satellite) { MapStyles.styleUri(context, installed, night, satellite) }

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
  // the buttons show up when the map is touched and go away by themselves
  var lastMapTap by remember { mutableStateOf(System.currentTimeMillis()) }
  // a point long-pressed on the map, waiting for "go / pass here / avoid"
  var pendingPoint by remember { mutableStateOf<GeographicCoordinate?>(null) }

  val traveled = if (navigating) (nav.routeLength - (ui.progress?.distanceRemaining ?: 0.0)).coerceAtLeast(0.0) else 0.0
  val nextLimit = nav.limits.firstOrNull { it.alongM - traveled > -15 }
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
        vm.say("Attenzione: tra ${Fmt.distanceText(d).replace("km", "chilometri").replace(" m", " metri")}, ${l.label} ${l.signValue ?: ""}. " +
            "Il mezzo non passa, verificare la segnaletica.")
      }
    }
  }

  Box(Modifier.fillMaxSize().background(Nm.Bg)) {
    // a new map view when the tablet turns: the old one kept drawing at the old size (a blank strip
    // on one side in portrait); the camera state survives, it is kept outside
    key(landscape) {
    NavigationMapView(
        baseStyle = BaseStyle.Uri(styleUri),
        navigationMapState = mapState,
        uiState = ui,
        mapOptions = MapOptions(ornamentOptions = OrnamentOptions(isCompassEnabled = false, isScaleBarEnabled = false)),
        routeOverlayBuilder =
            RouteOverlayBuilder(
                navigationPath = { state ->
                  state.routeGeometry?.let {
                    BorderedPolyline(points = it, idPrefix = "nm-route", color = Nm.Route, lineWidth = 13f, borderWidth = 3f)
                  }
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
          if (i != plan.selected) BorderedPolyline(points = v.route.geometry, idPrefix = "nm-alt-$i", color = Color(0xFF8C97A3), lineWidth = 8f, borderWidth = 2f)
        }
        plan.current?.let { BorderedPolyline(points = it.route.geometry, idPrefix = "nm-preview", color = Nm.Route, lineWidth = 11f, borderWidth = 3f) }
      }
      CritMarkers(if (navigating) nav.criticalities else plan.current?.criticalities ?: emptyList())
      LimitMarkers(if (navigating) nav.limits else plan.current?.limits ?: emptyList())
      StopMarkers(plan.stops.map { it.coordinate })
    }
    }

    if (navigating) {
      val booth = nav.analysis?.nodes?.firstOrNull { it.alongM > traveled - 20 }
          ?.let { b -> Triple(b, nav.analysis?.boothRole(b), b.alongM - traveled) }
          ?.takeIf { it.third <= 2500 }
      NavigatingOverlay(vm, ui, garage.active, nextLimit, nextLimitDist, nextCrit, booth, traveled, nav.pois, landscape, mapState,
          settings, nav.analysis, night, lastMapTap, onCrit = { openCrit = it }, onPoi = { openPoi = it })
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
            onDismiss = vm::dismissPrompt)
      }
    } else {
      BrowsingOverlay(
          vm, plan, garage.active.name, garage.active.type.icon, landscape,
          onSearch = { sheet = Sheet.SEARCH },
          onVehicle = { sheet = Sheet.VEHICLE },
          onSettings = { sheet = Sheet.SETTINGS },
          onRegions = { sheet = Sheet.REGIONS },
          onSatellite = { satellite = !satellite },
          satellite = satellite,
          onRecenter = { mapState.recenter(false) },
          onCrit = { openCrit = it },
          tracking = mapState.isTrackingUser,
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

    pendingPoint?.let { pt ->
      PointChooser(
          navigating = navigating,
          modifier = Modifier.align(Alignment.Center),
          onVia = { if (navigating) vm.addStopDuringNav(pt, "Passa di qui") else vm.addVia(pt); pendingPoint = null },
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
        vm.selectDestination(f.coordinate, f.title)
      }, onClose = { sheet = Sheet.NONE })
      Sheet.VEHICLE -> VehicleEditor(onClose = {
        sheet = Sheet.NONE
        if (plan.stops.isNotEmpty()) vm.planRoutes()
      })
      Sheet.SETTINGS -> SettingsScreen(onClose = { sheet = Sheet.NONE; vm.refreshPois() }, onRegions = { sheet = Sheet.REGIONS },
          initialPage = initialSheet?.takeIf { it.startsWith("settings_") }?.substringAfter("settings_"))
      Sheet.REGIONS -> RegionsScreen(here, onClose = { sheet = Sheet.NONE })
      Sheet.NONE -> {}
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
) {
  Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
    // search bar
    Row(
        Modifier.align(Alignment.TopStart).fillMaxWidth(if (landscape) 0.5f else 1f).heightIn(min = 64.dp).shadow(10.dp, RoundedCornerShape(32.dp))
            .clip(RoundedCornerShape(32.dp)).background(Nm.Panel).border(1.dp, Nm.Line, RoundedCornerShape(32.dp)).clickable(onClick = onSearch)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Rounded.Search, null, tint = Nm.Muted, modifier = Modifier.size(28.dp))
      Spacer(Modifier.width(12.dp))
      Text("Dove andiamo?", color = Nm.Muted, fontSize = 19.sp, modifier = Modifier.weight(1f))
      Row(
          Modifier.clip(RoundedCornerShape(20.dp)).background(Nm.Raised).clickable(onClick = onVehicle).padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(vehicleIcon, fontSize = 18.sp)
        Spacer(Modifier.width(6.dp))
        Text(vehicleName, color = Nm.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp))
      }
    }
    // map buttons
    // map buttons: only what is useful now (the vehicle is already in the search bar, the countries
    // are in the settings); with a route on screen, only the satellite view
    Column(Modifier.align(Alignment.TopEnd), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (plan.stops.isEmpty()) RoundAction(Icons.Rounded.Settings, "Impostazioni", onClick = onSettings)
      RoundAction(Icons.Rounded.Layers, "Satellite", container = if (satellite) Nm.Accent else Nm.Panel, onClick = onSatellite)
    }
    if (!tracking) RoundAction(Icons.Rounded.MyLocation, "Centra", Modifier.align(Alignment.BottomEnd), onClick = onRecenter)

    if (plan.stops.isNotEmpty()) {
      PlanPanel(
          plan, vehicleName,
          modifier = Modifier.align(if (landscape) Alignment.BottomStart else Alignment.BottomCenter)
              .then(if (landscape) Modifier.fillMaxWidth(0.46f).fillMaxHeight(0.84f) else Modifier.fillMaxWidth().heightIn(max = 560.dp)),
          onSelect = vm::selectVariant,
          onCrit = onCrit,
          onUnavoid = vm::unavoid,
          onAddStop = { if (plan.addingStop) vm.cancelAddingStop() else vm.startAddingStop() },
          onRemoveStop = vm::removeStop,
          onRemoveArea = vm::removeAvoidArea,
          onStart = { vm.start(false) },
          onSimulate = { vm.start(true) },
          onCancel = vm::clearPlan,
      )
    } else {
      Text("Cerca un indirizzo o tieni premuto sulla mappa", color = Color(0xCCFFFFFF), fontSize = 14.sp,
          modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp))
    }
  }
}

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
    onCrit: (Criticality) -> Unit,
    onPoi: (RoutePoi) -> Unit,
) {
  val simulating by vm.simulating.collectAsState()
  // buttons: shown for a few seconds after the map is touched (and at the start), then only the
  // ones that are needed right now (the "centre" one when the map was moved by hand)
  var controls by remember { mutableStateOf(true) }
  LaunchedEffect(lastMapTap) {
    controls = true
    delay(8_000)
    controls = false
  }
  val jv = if (settings.junctionView) junctionSceneOf(ui.visualInstruction, ui.progress?.distanceToNextManeuver, analysis, traveled) else null
  val speedKmh = ui.location?.speed?.value?.let { (it * 3.6).roundToInt() }
  val limitKmh = ui.currentAnnotation?.speedLimit?.value(MeasurementSpeedUnit.KilometersPerHour)?.roundToInt()
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(10.dp)) {
    TopManeuverBar(ui.visualInstruction, ui.progress?.distanceToNextManeuver, Modifier.fillMaxWidth())
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (nextLimit != null && nextLimitDist != null && nextLimitDist < (if (nextLimit.blocking) 10_000.0 else 5_000.0)) {
        RestrictionBanner(nextLimit, nextLimitDist, vehicleValue(nextLimit, vehicle))
      } else if (nextCrit != null) {
        CritBanner(nextCrit, (nextCrit.startM - traveled).coerceAtLeast(0.0)) { onCrit(nextCrit) }
      }
      if (booth != null) BoothBanner(booth.first, booth.second, booth.third)
    }
    if (jv != null && !landscape) JunctionView(jv, night, Modifier.fillMaxWidth().padding(top = 8.dp))
    Box(Modifier.weight(1f).fillMaxWidth()) {
      if (jv != null && landscape) {
        JunctionView(jv, night, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.44f).padding(top = 4.dp))
      }
      SpeedPanel(speedKmh, limitKmh, vehicle.topSpeedKmh, Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp), settings.speedWarningKmh)
      // places along the route: a narrow panel at the edge (the right one unless the driver chose
      // the left), under the manoeuvre bar, never in the middle of the road ahead
      val atRight = settings.poiRailSide != app.navmaster.truck.settings.PoiSide.LEFT
      if (jv == null) {
        PoiRail(pois, traveled, settings.poiRailCount, settings.poiRailSeconds, settings.poiRailOpacity, atRight, narrow = !landscape,
            modifier = Modifier.align(if (atRight) Alignment.TopEnd else Alignment.TopStart).padding(top = if (atRight) 4.dp else 34.dp), onPoi = onPoi)
      }
      Column(Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
          horizontalAlignment = Alignment.End) {
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
        Text("SIMULAZIONE", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 6.dp).background(Nm.Amber, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp))
      }
    }
    BottomTripBar(ui.currentStepRoadName, ui.progress?.distanceRemaining, ui.progress?.durationRemaining, Modifier.fillMaxWidth())
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
  val ok = rememberGeoJsonSource(GeoJsonData.JsonString(points(limits.filter { !it.blocking }.map { it.lat to it.lon })))
  val bad = rememberGeoJsonSource(GeoJsonData.JsonString(points(limits.filter { it.blocking }.map { it.lat to it.lon })))
  CircleLayer(id = "nm-limits-ok", source = ok, color = const(NmAmber), radius = const(7.dp), strokeColor = const(Color.White), strokeWidth = const(2.dp))
  CircleLayer(id = "nm-limits-bad", source = bad, color = const(NmRed), radius = const(11.dp), strokeColor = const(Color.White), strokeWidth = const(3.dp))
}

@Composable
@MaplibreComposable
private fun CritMarkers(list: List<Criticality>) {
  val warn = rememberGeoJsonSource(GeoJsonData.JsonString(points(list.filter { it.severity == Severity.WARN }.map { it.lat to it.lon })))
  val crit = rememberGeoJsonSource(GeoJsonData.JsonString(points(list.filter { it.severity == Severity.CRITICAL }.map { it.lat to it.lon })))
  CircleLayer(id = "nm-crit-warn", source = warn, color = const(Nm.Amber), radius = const(9.dp), strokeColor = const(Color(0xFF111111)), strokeWidth = const(3.dp))
  CircleLayer(id = "nm-crit-bad", source = crit, color = const(Nm.Red), radius = const(11.dp), strokeColor = const(Color.White), strokeWidth = const(3.dp))
}

@Composable
@MaplibreComposable
private fun StopMarkers(stops: List<GeographicCoordinate>) {
  val src = rememberGeoJsonSource(GeoJsonData.JsonString(points(stops.map { it.lat to it.lng })))
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
) {
  Panel(modifier.widthIn(max = 420.dp).padding(16.dp), padding = 16.dp) {
    Title("Punto sulla mappa", size = 20)
    Caption("Il passaggio va da solo nel punto del viaggio dove allunga meno.", size = 13)
    Spacer(Modifier.height(10.dp))
    BigButton("Passa di qui", Modifier.fillMaxWidth(), Icons.Rounded.AddLocationAlt, onClick = onVia)
    if (!navigating) {
      Spacer(Modifier.height(8.dp))
      BigButton("Vai qui (nuova destinazione)", Modifier.fillMaxWidth(), Icons.Rounded.Navigation, BtnStyle.SECONDARY, onClick = onGo)
      Spacer(Modifier.height(8.dp))
      BigButton("Evita questa zona", Modifier.fillMaxWidth(), Icons.Rounded.Block, BtnStyle.SECONDARY, onClick = onAvoid)
    }
    Spacer(Modifier.height(8.dp))
    BigButton("Annulla", Modifier.fillMaxWidth(), style = BtnStyle.GHOST, onClick = onDismiss)
  }
}
