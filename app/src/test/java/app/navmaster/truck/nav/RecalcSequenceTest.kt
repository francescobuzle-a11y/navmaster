package app.navmaster.truck.nav

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recalculations while driving: one at a time, the newest wins, "recalculating" off only at its end. */
class RecalcSequenceTest {
  @Test
  fun newestWinsAndTheOlderIsCancelled() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val applied = Collections.synchronizedList(mutableListOf<String>())
    val busy = AtomicInteger(0)
    val idle = AtomicInteger(0)
    val done = CountDownLatch(1)
    val seq = RecalcSequence(scope, Dispatchers.IO, onBusy = { busy.incrementAndGet() }, onIdle = { idle.incrementAndGet(); done.countDown() })
    val firstCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    // R1: a slow reroute (the wrong turn)
    seq.launch { n ->
      try {
        delay(5_000)
        if (seq.isLatest(n)) applied += "R1"
      } catch (e: kotlinx.coroutines.CancellationException) {
        firstCancelled.set(true)
        throw e
      }
    }
    // R2: a stop added two seconds later, quick: its route is the one applied
    seq.launch { n -> if (seq.isLatest(n)) applied += "R2" }
    assertTrue(done.await(3, TimeUnit.SECONDS))
    Thread.sleep(100)
    assertEquals(listOf("R2"), applied.toList())
    assertTrue(firstCancelled.get())
    assertEquals(2, busy.get())
    // "recalculating" off once, at the end of the newest
    assertEquals(1, idle.get())
    scope.cancel()
  }

  @Test
  fun anOlderEndingLateNeitherAppliesNorEndsTheNewer() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val applied = Collections.synchronizedList(mutableListOf<Long>())
    val idle = AtomicInteger(0)
    val release = CountDownLatch(1)
    val seq = RecalcSequence(scope, Dispatchers.IO, onBusy = {}, onIdle = { idle.incrementAndGet() })
    // a search that does not see the cancellation at once (the engine checks its token now and then)
    seq.launch { n ->
      release.await(2, TimeUnit.SECONDS)
      if (seq.isLatest(n)) applied += n
    }
    val started = CountDownLatch(1)
    seq.launch { n ->
      started.countDown()
      if (seq.isLatest(n)) applied += n
    }
    release.countDown()
    assertTrue(started.await(3, TimeUnit.SECONDS))
    Thread.sleep(200)
    assertEquals(listOf(2L), applied.toList())
    assertEquals(1, idle.get())
    scope.cancel()
  }

  @Test
  fun cancelledByTheStop() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val applied = AtomicInteger(0)
    val idle = AtomicInteger(0)
    val seq = RecalcSequence(scope, Dispatchers.IO, onBusy = {}, onIdle = { idle.incrementAndGet() })
    seq.launch { n -> delay(300); if (seq.isLatest(n)) applied.incrementAndGet() }
    seq.cancel()
    Thread.sleep(600)
    assertEquals(0, applied.get())
    // the stop itself puts "recalculating" off (NavExtras reset)
    assertEquals(0, idle.get())
    assertFalse(seq.isLatest(1))
    scope.cancel()
  }
  @Test
  fun theNewestEndingWithNoRouteIsReported() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val noRoute = Collections.synchronizedList(mutableListOf<Long>())
    val done = CountDownLatch(1)
    val seq = RecalcSequence(scope, Dispatchers.IO, onBusy = {}, onIdle = { done.countDown() }, onNoRoute = { noRoute += it })
    // R1: the wrong-turn reroute, cancelled by R2 before it hands its route over
    seq.launch { n -> delay(5_000); seq.handedOver(n) }
    // R2: a stop added that fails (no route to it): the old route stays, R1 is lost
    seq.launch { _ -> runCatching { throw IllegalStateException("nessun percorso") } }
    assertTrue(done.await(3, TimeUnit.SECONDS))
    Thread.sleep(100)
    assertEquals(listOf(2L), noRoute.toList())
    scope.cancel()
  }

  @Test
  fun theNewestHandingItsRouteOverIsNotReported() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val noRoute = AtomicInteger(0)
    val done = CountDownLatch(1)
    val seq = RecalcSequence(scope, Dispatchers.IO, onBusy = {}, onIdle = { done.countDown() }, onNoRoute = { noRoute.incrementAndGet() })
    seq.launch { n -> delay(5_000); seq.handedOver(n) }
    seq.launch { n -> seq.handedOver(n) }
    assertTrue(done.await(3, TimeUnit.SECONDS))
    Thread.sleep(100)
    assertEquals(0, noRoute.get())
    scope.cancel()
  }
}
