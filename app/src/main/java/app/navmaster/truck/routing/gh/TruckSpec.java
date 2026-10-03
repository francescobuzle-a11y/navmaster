package app.navmaster.truck.routing.gh;

import java.util.ArrayList;
import java.util.List;

/**
 * The vehicle and the choices of the trip, as GraphHopper needs them: the measures of the vehicle
 * chosen in the app (height, width, length, weight with the load, axle load, dangerous goods) and the
 * trip options (tolls, ferries, unpaved roads, zones to avoid).
 *
 * Plain Java, no Android: also used by the routing test that runs on the computer.
 */
public final class TruckSpec {
  /** A lorry or a bus: roads closed to lorries (hgv=no) are closed to it. False for campers and vans. */
  public boolean hgv = true;
  public double heightM = 4.0;
  public double widthM = 2.55;
  public double lengthM = 16.5;
  /** Weight with the load, tonnes. */
  public double weightT = 40.0;
  public double axleLoadT = 11.5;
  /** Dangerous goods on board (ADR): roads with hazmat=no are closed. */
  public boolean hazmat = false;
  /**
   * ADR tunnel restriction code of the load: 'B', 'C', 'D' or 'E' (0 = none). Goods with code B may
   * not use tunnels of category B to E, code C tunnels C to E, and so on.
   */
  public char tunnelCode = 0;
  /** Goods dangerous for water: roads with hazmat:water=no are closed. */
  public boolean hazmatWater = false;
  /** Top speed of the vehicle, km/h (the time of each road is worked out with it). */
  public double topSpeedKmh = 90.0;
  public boolean avoidTolls = false;
  public boolean avoidFerries = false;
  public boolean avoidUnpaved = true;
  /** Roads signed for lorries (hgv=designated) as a tie-breaker. */
  public boolean preferTruckRoutes = false;
  /** The shortest route rather than the fastest. */
  public boolean shortest = false;
  /** Zones to avoid: each one a closed ring of [lat, lon]. */
  public final List<double[][]> avoidZones = new ArrayList<>();

  @Override
  public String toString() {
    return "TruckSpec{hgv=" + hgv + ", h=" + heightM + ", w=" + widthM + ", l=" + lengthM + ", t=" + weightT +
        ", axle=" + axleLoadT + ", hazmat=" + hazmat + (tunnelCode != 0 ? "/" + tunnelCode : "") + (hazmatWater ? "/water" : "") + ", v=" + topSpeedKmh + ", tolls=" + !avoidTolls +
        ", ferries=" + !avoidFerries + ", unpaved=" + !avoidUnpaved + ", zones=" + avoidZones.size() + "}";
  }
}
