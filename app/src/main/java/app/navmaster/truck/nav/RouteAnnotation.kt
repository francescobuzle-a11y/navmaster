package app.navmaster.truck.nav

import com.stadiamaps.ferrostar.core.annotation.AnnotationPublisher
import com.stadiamaps.ferrostar.core.annotation.DefaultAnnotationPublisher
import com.stadiamaps.ferrostar.core.annotation.Speed
import com.stadiamaps.ferrostar.core.annotation.SpeedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the route says about each stretch of road (the OSRM "annotation" GhGuide writes from
 * GraphHopper's graph): the speed limit, the speed, the length and the time.
 */
@Serializable
data class RouteAnnotation(
    /** The speed limit of the stretch ({"speed": 50, "unit": "km/h"}, {"none": true}, {"unknown": true}). */
    @SerialName("maxspeed") @Serializable(with = SpeedSerializer::class) val speedLimit: Speed? = null,
    /** The speed of travel on the stretch, in metres per second. */
    val speed: Double? = null,
    /** The length of the stretch in metres. */
    val distance: Double? = null,
    /** The time to drive it, in seconds. */
    val duration: Double? = null,
)

/** Ferrostar reads the annotation of the stretch the vehicle is on, and its speed limit, with this. */
fun routeAnnotationPublisher(): AnnotationPublisher<RouteAnnotation> =
    DefaultAnnotationPublisher(
        json = Json { ignoreUnknownKeys = true },
        serializer = RouteAnnotation.serializer(),
        speedLimitMapper = { it?.speedLimit },
    )
