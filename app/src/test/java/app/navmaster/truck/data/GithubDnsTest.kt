package app.navmaster.truck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** github.com first through the front-ends that answered during the incident, then the system's addresses; other hosts untouched. */
class GithubDnsTest {
  @Test
  fun githubFirstThroughThePreferredFrontEnds() {
    val a = GithubDns.lookup("github.com").map { it.hostAddress }
    assertEquals("140.82.112.3", a.first())
    assertTrue(a.size >= 6)
    assertEquals(a.size, a.toSet().size)
    val other = GithubDns.lookup("example.com").map { it.hostAddress }
    assertTrue(other.none { it.startsWith("140.82.11") })
  }
}
