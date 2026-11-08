package app.navmaster.truck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** github.com: the addresses that answer first, then all the others (system and GitHub's extra ones), no duplicates; other hosts untouched. */
class GithubDnsTest {
  @Test
  fun githubAddressesAnsweringFirst() {
    val a = GithubDns.lookup("github.com").map { it.hostAddress }
    assertTrue(a.size >= 6)
    assertEquals(a.size, a.toSet().size)
    assertTrue("140.82.112.3" in a)
    val other = GithubDns.lookup("example.com").map { it.hostAddress }
    assertTrue(other.none { it.startsWith("140.82.11") })
  }
}
