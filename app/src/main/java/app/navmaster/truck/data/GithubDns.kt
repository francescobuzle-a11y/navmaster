package app.navmaster.truck.data

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * The addresses of github.com, where the data packages and the catalog are: first GitHub's
 * front-ends that answered during the incident of 2026-11-08 ("increase in timeouts for some
 * unauthenticated users": from WindTre the European ones, 140.82.121.x, accepted the connection and
 * then answered nothing, while the American ones answered at once), then those of the system's DNS.
 * Only the redirect to a file goes there (a few hundred bytes): the file itself comes from GitHub's
 * distribution network, near the tablet. An address that does not connect makes OkHttp try the next.
 * The addresses are in GitHub's published range for the web (140.82.112.0/20).
 */
object GithubDns : Dns {
  private val preferred = listOf("140.82.112.3", "140.82.113.3", "140.82.114.3", "140.82.112.4", "140.82.113.4", "140.82.114.4")

  override fun lookup(hostname: String): List<InetAddress> {
    val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
    if (hostname != "github.com") return system.ifEmpty { throw UnknownHostException(hostname) }
    val first = preferred.map { ip -> InetAddress.getByAddress(hostname, ip.split('.').map { it.toInt().toByte() }.toByteArray()) }
    return (first + system).distinctBy { it.hostAddress }
  }
}
