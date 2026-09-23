package app.navmaster.truck.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.navmaster.truck.AppGraph
import app.navmaster.truck.limits.RouteLimit
import app.navmaster.truck.nav.NavViewModel
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

@Composable
fun MainScreen(vm: NavViewModel) {
  KeepScreenOnDisposableEffect()
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

  val permissions =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
          arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
              Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.FOREGROUND_SERVICE_LOCATION)
      else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
          arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
              Manifest.permission.POST_NOTIFICATIONS)
      else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
    vm.setLocationPermission(result[Manifest.permission.ACCESS_FINE_LOCATION] == true)
  }
  LaunchedEffect(Unit) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      vm.setLocationPermission(true)
    } else {
      launcher.launch(permissions)
    }
  }

  val ui by vm.navigationUiState.collectAsState()
  val scene by vm.scene.collectAsState()
  val garage by AppGraph.profiles.garage.collectAsState()
  val installed by AppGraph.regions.installed.collectAsState()
  val download by AppGraph.regions.state.collectAsState()
  val navigating = ui.isNavigating()

  // automatic night mode, checked every minute
  var night by remember { mutableStateOf(MapStyles.isNight()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(60_000)
      night = MapStyles.isNight()
    }
  }
  val region = installed.let { list -> list.firstOrNull { it.id == "italia" } ?: list.firstOrNull() }
  val styleUri = remember(region?.dir?.absolutePath, night) { MapStyles.styleUri(context, region, night) }

  // Garmin-like camera: tilted, the vehicle low on the screen so the road ahead is visible
  val h = configuration.screenHeightDp
  val w = configuration.screenWidthDp
  val cameraOptions =
      NavigationCameraOptions(
          browsingZoom = 15.0,
          navigationZoom = 16.6,
          navigationTilt = 55.0,
          browsingPadding = PaddingValues(0.dp),
          navigationPadding =
              if (landscape) PaddingValues(top = (h * 0.30f).dp, end = (w * 0.42f).dp)
              else PaddingValues(top = (h * 0.40f).dp),
      )
  val mapState = rememberNavigationMapState()

  var showVehicle by remember { mutableStateOf(false) }
  var showRegions by remember { mutableStateOf(false) }

  // limits follow the route after a recalculation
  val geometry = ui.routeGeometry
  LaunchedEffect(geometry?.size, geometry?.lastOrNull()) {
    if (navigating && geometry != null) vm.refreshLimitsFor(geometry)
  }

  val traveled = if (navigating) scene.activeRouteLength - (ui.progress?.distanceRemaining ?: 0.0) else 0.0
  val nextLimit = scene.activeLimits.firstOrNull { it.alongM - traveled > -15 }
  val nextLimitDist = nextLimit?.let { it.alongM - traveled }

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
        AppGraph.tts.tts?.speak(
            "Attenzione: tra ${Fmt.distanceText(d).replace("km", "chilometri").replace(" m", " metri")}, ${l.label} ${l.signValue ?: ""}. Il mezzo non passa, verificare la segnaletica.",
            TextToSpeech.QUEUE_ADD, null, "limit-$key")
      }
    }
  }

  Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
    NavigationMapView(
        baseStyle = BaseStyle.Uri(styleUri),
        navigationMapState = mapState,
        uiState = ui,
        mapOptions = MapOptions(ornamentOptions = OrnamentOptions(isCompassEnabled = false, isScaleBarEnabled = false)),
        routeOverlayBuilder =
            RouteOverlayBuilder(
                navigationPath = { state ->
                  state.routeGeometry?.let {
                    BorderedPolyline(points = it, idPrefix = "nm-route", color = NmRoute, lineWidth = 13f, borderWidth = 3f)
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
        onMapLongClick = { coordinate, _ ->
          if (!navigating) vm.selectDestination(coordinate, "Punto sulla mappa")
          NavigationMapClickResult.Consume
        },
    ) { _ ->
      scene.planned?.let {
        BorderedPolyline(points = it.route.geometry, idPrefix = "nm-preview", color = NmRoute, lineWidth = 11f, borderWidth = 3f)
      }
      LimitMarkers(if (navigating) scene.activeLimits else scene.planned?.limits ?: emptyList())
      scene.destination?.let { DestinationPin(it) }
    }

    if (navigating) {
      NavigatingOverlay(vm, ui, garage.active, nextLimit, nextLimitDist, landscape, mapState)
    } else {
      BrowsingOverlay(vm, scene, garage, landscape, onVehicle = { showVehicle = true }, onRegions = { showRegions = true })
    }

    if (installed.isEmpty() && !showRegions) {
      Box(Modifier.fillMaxSize().background(Color(0xE6101418)).statusBarsPadding(), contentAlignment = Alignment.Center) {
        WelcomeCard {
          RegionsCard(installed, download, AppGraph.regions::download, AppGraph.regions::delete, null, Modifier.widthIn(max = 520.dp).padding(16.dp))
        }
      }
    }
    if (showRegions) {
      Box(Modifier.fillMaxSize().background(Color(0x99000000)).statusBarsPadding(), contentAlignment = Alignment.Center) {
        RegionsCard(installed, download, AppGraph.regions::download, { AppGraph.regions.delete(it); AppGraph.engine.reset() },
            { showRegions = false }, Modifier.widthIn(max = 520.dp).padding(16.dp))
      }
    }
    if (showVehicle) {
      Box(Modifier.fillMaxSize().background(Color(0x99000000)).statusBarsPadding(), contentAlignment = Alignment.Center) {
        VehicleSheet(
            garage,
            onSelect = AppGraph.profiles::select,
            onLoad = AppGraph.profiles::setLoad,
            onSave = AppGraph.profiles::save,
            onClose = {
              showVehicle = false
              if (scene.destination != null) vm.planRoute()
            },
        )
      }
    }
  }
}

@Composable
private fun BrowsingOverlay(
    vm: NavViewModel,
    scene: app.navmaster.truck.nav.SceneState,
    garage: app.navmaster.truck.vehicle.Garage,
    landscape: Boolean,
    onVehicle: () -> Unit,
    onRegions: () -> Unit,
) {
  val location by vm.location.collectAsState()
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      SearchPanel(
          near = location?.coordinates,
          onPick = { vm.selectDestination(it.coordinate, it.title) },
          modifier = Modifier.weight(1f).widthIn(max = 560.dp),
      )
      RoundButton("🗺") { onRegions() }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
      Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(if (landscape) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        scene.error?.let {
          Card { Text(it, color = Color(0xFFFF8A80), fontSize = 16.sp) }
        }
        if (scene.planning) {
          Card { Text("Calcolo del percorso per ${garage.active.name}…", color = Color.White, fontSize = 17.sp) }
        }
        scene.planned?.let { p ->
          PlannedRouteCard(p, scene.destinationLabel, onStart = { vm.start(false) }, onSimulate = { vm.start(true) }, onCancel = { vm.clearDestination() })
        }
        if (scene.planned == null && !scene.planning) {
          VehicleChip(garage, onVehicle)
          if (scene.destination == null) {
            Text("Tieni premuto sulla mappa per scegliere la destinazione", color = Color(0xCCFFFFFF), fontSize = 14.sp,
                modifier = Modifier.background(Color(0x99000000), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp))
          }
        }
      }
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
    landscape: Boolean,
    mapState: com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState,
) {
  val simulating by vm.simulating.collectAsState()
  val speedKmh = ui.location?.speed?.value?.let { (it * 3.6).roundToInt() }
  val limitKmh = ui.currentAnnotation?.speedLimit?.value(MeasurementSpeedUnit.KilometersPerHour)?.roundToInt()
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(10.dp)) {
    TopManeuverBar(ui.visualInstruction, ui.progress?.distanceToNextManeuver, Modifier.fillMaxWidth())
    if (nextLimit != null && nextLimitDist != null && nextLimitDist < (if (nextLimit.blocking) 10_000.0 else 5_000.0)) {
      RestrictionBanner(
          nextLimit,
          nextLimitDist,
          vehicleValue(nextLimit, vehicle),
          Modifier.padding(top = 8.dp).align(if (landscape) Alignment.Start else Alignment.CenterHorizontally),
      )
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
      SpeedPanel(speedKmh, limitKmh, vehicle.topSpeedKmh, Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp))
      Column(Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RoundButton(if (ui.isMuted == true) "🔇" else "🔊") { vm.toggleMute() }
        RoundButton("◎") { mapState.recenter(true) }
        RoundButton("✕", danger = true) { vm.stopNavigation() }
      }
      if (simulating) {
        Text("SIMULAZIONE", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp).background(NmAmber, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp))
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
      else -> null
    }

@Composable
private fun RoundButton(label: String, danger: Boolean = false, onClick: () -> Unit) {
  FilledIconButton(
      onClick = onClick,
      modifier = Modifier.size(64.dp),
      colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (danger) Color(0xFFC62828) else NmPanel),
  ) { Text(label, fontSize = 24.sp, color = Color.White) }
}

@Composable
@MaplibreComposable
private fun LimitMarkers(limits: List<RouteLimit>) {
  fun fc(list: List<RouteLimit>) =
      """{"type":"FeatureCollection","features":[""" +
          list.joinToString(",") { """{"type":"Feature","geometry":{"type":"Point","coordinates":[${it.lon},${it.lat}]},"properties":{}}""" } +
          "]}"
  val ok = rememberGeoJsonSource(GeoJsonData.JsonString(fc(limits.filter { !it.blocking })))
  val bad = rememberGeoJsonSource(GeoJsonData.JsonString(fc(limits.filter { it.blocking })))
  CircleLayer(id = "nm-limits-ok", source = ok, color = const(NmAmber), radius = const(8.dp), strokeColor = const(Color.White), strokeWidth = const(2.dp))
  CircleLayer(id = "nm-limits-bad", source = bad, color = const(NmRed), radius = const(11.dp), strokeColor = const(Color.White), strokeWidth = const(3.dp))
}

@Composable
@MaplibreComposable
private fun DestinationPin(c: GeographicCoordinate) {
  val src = rememberGeoJsonSource(
      GeoJsonData.JsonString("""{"type":"Feature","geometry":{"type":"Point","coordinates":[${c.lng},${c.lat}]},"properties":{}}"""))
  CircleLayer(id = "nm-dest", source = src, color = const(Color(0xFFD50000)), radius = const(10.dp), strokeColor = const(Color.White), strokeWidth = const(3.dp))
}
