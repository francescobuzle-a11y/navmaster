package app.navmaster.truck.data

import android.util.Log
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The addresses of github.com, where the data packages and the catalog are, in the order of those
 * that answer. During the incident of 2026-11-08 ("increase in timeouts for some unauthenticated
 * users") some of GitHub's front-ends accepted the connection and then answered nothing: from
 * WindTre the European ones (140.82.121.x) and, at times, one of the American ones. OkHttp moves to
 * the next address only when a connection fails, not when an answer never comes, so the addresses
 * are tried first: a tiny request to each, in parallel, for at most [PROBE_S] seconds, every
 * [VALID_MIN] minutes; those that answered go first (the quickest first), then all the others.
 * Only the redirect to a file goes to github.com (a few hundred bytes): the file itself comes from
 * GitHub's distribution network, near the tablet. The extra addresses are in GitHub's published
 * range for the web (140.82.112.0/20).
 */
object GithubDns : Dns {
  private const val TAG = "NavMasterData"
  private const val PROBE_S = 4L
  private const val VALID_MIN = 10L
  private val extra = listOf("140.82.112.3", "140.82.113.3", "140.82.114.3", "140.82.112.4", "140.82.113.4", "140.82.114.4")

  @Volatile private var good: List<InetAddress> = emptyList()
  @Volatile private var goodAt = 0L

  override fun lookup(hostname: String): List<InetAddress> {
    val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
    if (hostname != "github.com") return system.ifEmpty { throw UnknownHostException(hostname) }
    val all = (system + extra.map { address(hostname, it) }).distinctBy { it.hostAddress }
    if (System.currentTimeMillis() - goodAt > VALID_MIN * 60_000) probe(all)
    return (good + all).distinctBy { it.hostAddress }
  }

  @Synchronized
  private fun probe(all: List<InetAddress>) {
    if (System.currentTimeMillis() - goodAt <= VALID_MIN * 60_000) return
    val answered = ConcurrentHashMap<String, Long>()
    val started = System.currentTimeMillis()
    val threads = all.map { a ->
      thread(name = "github-probe", isDaemon = true) {
        try {
          val client = OkHttpClient.Builder().dns { listOf(a) }.callTimeout(PROBE_S, TimeUnit.SECONDS)
              .followRedirects(false).followSslRedirects(false).build()
          client.newCall(Request.Builder().url("https://github.com/robots.txt").head().build()).execute().use {
            if (it.code in 200..399) answered[a.hostAddress ?: ""] = System.currentTimeMillis() - started
          }
        } catch (e: Exception) { }
      }
    }
    threads.forEach { it.join((PROBE_S + 1) * 1000) }
    good = all.filter { answered.containsKey(it.hostAddress) }.sortedBy { answered[it.hostAddress] }
    goodAt = System.currentTimeMillis()
    // (in the unit tests without Android, Log is not there)
    runCatching { Log.i(TAG, "github.com: rispondono ${good.size} indirizzi su ${all.size} (${good.joinToString { it.hostAddress ?: "" }})") }
  }

  private fun address(host: String, ip: String) = InetAddress.getByAddress(host, ip.split('.').map { it.toInt().toByte() }.toByteArray())
}
