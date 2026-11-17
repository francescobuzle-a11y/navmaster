package app.navmaster.truck.nav

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The recalculations of the guided route, one at a time and the newest wins: [launch] cancels the
 * one under way (its search stops at once with the engine's token), waits for it to end and runs
 * the new one with its number; only the newest ([isLatest]) hands its route to the guidance, and
 * only its end calls [onIdle] ("recalculating" off; [onBusy] puts it on). [cancel]: none of those
 * under way counts any more (the guidance stopped, started again, or the driver chose a route).
 * The newest that ends without handing a route over ([handedOver] not called for it: it failed, or
 * it found nothing) calls [onNoRoute] with its number, before [onIdle]: the guidance keeps the old
 * route, so what waited for a new one (the off-route check) must be free to try again. Before, a
 * wrong-turn reroute cancelled by a stop added that then failed left the off-route check blocked
 * for the rest of the trip.
 *
 * Before, each recalculation ran by itself: the one ending last won even when it was older (a stop
 * just added lost again), and the first to end put "recalculating" off while another still ran.
 */
internal class RecalcSequence(
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
    private val onBusy: () -> Unit,
    private val onIdle: () -> Unit,
    private val onNoRoute: (seq: Long) -> Unit = {},
) {
  private val last = AtomicLong(0)
  /** The number of the last recalculation that handed its route to the guidance. */
  private val handed = AtomicLong(0)
  private var job: Job? = null

  /** [work] as the newest recalculation (it gets its number, for [isLatest]). */
  @Synchronized
  fun launch(work: suspend (seq: Long) -> Unit) {
    val seq = last.incrementAndGet()
    // after the new number (an older one ending now does not put it off again), before the start
    onBusy()
    val previous = job
    previous?.cancel()
    job = scope.launch(context) {
      try {
        previous?.join()
        work(seq)
      } finally {
        // under the same lock as a new number and its onBusy: never "off" after a newer "on"
        synchronized(this@RecalcSequence) {
          if (isLatest(seq)) {
            if (handed.get() != seq) onNoRoute(seq)
            onIdle()
          }
        }
      }
    }
  }

  fun isLatest(seq: Long): Boolean = last.get() == seq

  /** The recalculation [seq] handed its route to the guidance (see [onNoRoute]). */
  fun handedOver(seq: Long) = handed.set(seq)

  @Synchronized
  fun cancel() {
    last.incrementAndGet()
    job?.cancel()
    job = null
  }
}
