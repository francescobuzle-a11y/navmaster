package app.navmaster.truck.data

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fast, resumable downloads of the offline packages, on any connection (Wi-Fi or mobile data).
 *
 * Every file is fetched in pieces of 16 MB over several connections at once, each piece written in
 * its place in the file. The pieces done are remembered next to the file, so after a dropped
 * connection, a change of network or the app closed only the missing pieces are fetched again. A
 * lost connection is retried until it comes back. Each file is checked (SHA-256) as soon as it is
 * complete, while the others are still downloading.
 */
class FastDownloader(
    private val connections: Int = 6,
    /** Suspends while downloading is not possible (no network, or mobile data with "only Wi-Fi"). */
    private val waitAllowed: suspend () -> Unit,
) {
  class Item(val url: String, val size: Long, val sha256: String, val target: File)

  private val client =
      OkHttpClient.Builder()
          .connectTimeout(20, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .retryOnConnectionFailure(true)
          .build()
  private val noRedirect = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

  /** Where GitHub keeps a file (the address it redirects to, valid for about 40 minutes). */
  private class Direct(val url: String, val at: Long)

  private val direct = ConcurrentHashMap<String, Direct>()

  private fun directUrl(url: String): String {
    direct[url]?.takeIf { System.currentTimeMillis() - it.at < 15 * 60_000L }?.let { return it.url }
    val loc =
        noRedirect.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
          when {
            resp.isRedirect -> resp.header("Location")?.let { resp.request.url.resolve(it)?.toString() }
            resp.isSuccessful -> url
            else -> throw IOException("HTTP ${resp.code}")
          }
        } ?: url
    direct[url] = Direct(loc, System.currentTimeMillis())
    return loc
  }

  private fun segments(size: Long) = ((size + SEG - 1) / SEG).toInt().coerceAtLeast(1)

  private fun segLen(size: Long, i: Int) = min(size, (i + 1L) * SEG) - i * SEG

  /** Downloads (or completes) [items]; [progress] gets the bytes done so far, twice a second. */
  suspend fun fetch(items: List<Item>, progress: (Long) -> Unit) = coroutineScope {
    val done = AtomicLong(0)
    val work = Channel<Pair<Item, Int>>(Channel.UNLIMITED)
    val left = HashMap<Item, AtomicInteger>()
    val checks = Collections.synchronizedList(mutableListOf<Deferred<Unit>>())
    // small files first: they are ready (and checked) while the big ones are still coming
    for (item in items.sortedBy { it.size }) {
      val ok = File(item.target.path + ".ok")
      if (ok.exists() && item.target.length() == item.size) {
        done.addAndGet(item.size)
        continue
      }
      ok.delete()
      val n = segments(item.size)
      val seg = File(item.target.path + ".seg")
      val got =
          if (item.target.length() == item.size && seg.exists())
              seg.readLines().mapNotNull { it.trim().toIntOrNull() }.filter { it in 0 until n }.toSet()
          else emptySet()
      if (got.isEmpty()) {
        seg.delete()
        item.target.parentFile?.mkdirs()
        RandomAccessFile(item.target, "rw").use { it.setLength(item.size) }
      }
      for (i in got) done.addAndGet(segLen(item.size, i))
      val todo = (0 until n).filter { it !in got }
      left[item] = AtomicInteger(todo.size)
      if (todo.isEmpty()) checks += async(Dispatchers.Default) { verify(item) }
      for (i in todo) work.trySend(item to i)
    }
    work.close()
    Log.i(TAG, "download: ${items.size} files, ${done.get() / 1_000_000} MB already there")
    progress(done.get())
    val ticker = launch {
      while (isActive) {
        delay(500)
        progress(done.get())
      }
    }
    val workers = (1..connections).map {
      launch(Dispatchers.IO) {
        for ((item, i) in work) {
          piece(item, i, done)
          synchronized(item) { File(item.target.path + ".seg").appendText("$i\n") }
          if (left.getValue(item).decrementAndGet() == 0) checks += async(Dispatchers.Default) { verify(item) }
        }
      }
    }
    workers.joinAll()
    synchronized(checks) { checks.toList() }.awaitAll()
    ticker.cancel()
    progress(done.get())
  }

  private suspend fun piece(item: Item, i: Int, done: AtomicLong) {
    val start = i.toLong() * SEG
    val end = min(item.size, start + SEG) - 1
    var pos = start
    var failures = 0
    while (pos <= end) {
      waitAllowed()
      try {
        if (failures > 0 && failures % 3 == 0) direct.remove(item.url)
        val url = directUrl(item.url)
        client.newCall(Request.Builder().url(url).header("Range", "bytes=$pos-$end").build()).execute().use { resp ->
          if (resp.code == 403 || resp.code == 404 || resp.code == 410) {
            // the storage address has expired: ask GitHub for a new one
            direct.remove(item.url)
            throw IOException("HTTP ${resp.code}")
          }
          val whole = resp.code == 200 && pos == 0L && end == item.size - 1
          if (resp.code != 206 && !whole) throw IOException("HTTP ${resp.code}")
          RandomAccessFile(item.target, "rw").use { f ->
            f.seek(pos)
            val input = resp.body.byteStream()
            val buf = ByteArray(1 shl 16)
            while (pos <= end) {
              currentCoroutineContext().ensureActive()
              val n = input.read(buf, 0, min(buf.size.toLong(), end - pos + 1).toInt())
              if (n < 0) break
              f.write(buf, 0, n)
              pos += n
              done.addAndGet(n.toLong())
              failures = 0
            }
          }
        }
        if (pos <= end) throw IOException("connessione chiusa")
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        failures++
        Log.w(TAG, "download ${item.target.name} piece $i: $e (attempt $failures)")
        if (failures > 40) throw IOException("Connessione assente da troppo tempo: tocca Scarica per riprendere da dove era arrivato")
        delay(min(30_000L, 1000L * failures))
      }
    }
  }

  private fun verify(item: Item) {
    val t = System.currentTimeMillis()
    val md = MessageDigest.getInstance("SHA-256")
    item.target.inputStream().buffered(1 shl 20).use { input ->
      val buf = ByteArray(1 shl 20)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    val hex = md.digest().joinToString("") { "%02x".format(it) }
    if (!hex.equals(item.sha256, ignoreCase = true)) {
      item.target.delete()
      File(item.target.path + ".seg").delete()
      throw IllegalStateException("File ${item.target.name} danneggiato: tocca Scarica, verrà riscaricato solo lui")
    }
    File(item.target.path + ".ok").writeText(hex)
    Log.i(TAG, "download: ${item.target.name} complete and checked (${System.currentTimeMillis() - t} ms)")
  }

  companion object {
    private const val TAG = "NavMasterData"
    const val SEG = 16L * 1024 * 1024
  }
}
