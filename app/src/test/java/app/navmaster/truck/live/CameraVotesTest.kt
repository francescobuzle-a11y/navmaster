package app.navmaster.truck.live

import app.navmaster.truck.live.CameraVotes.Vote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drivers' answers about the fixed speed cameras: who counts, when a camera is left out, the messages. */
class CameraVotesTest {
  private val day = 24 * 3600_000L
  private val now = 1_800_000_000_000L
  private val lat = 45.15020
  private val lon = 10.37920

  private fun v(dev: String, yes: Boolean, daysAgo: Double, dLat: Double = 0.0) =
      Vote("id-$dev-$yes-$daysAgo-$dLat", lat + dLat, lon, yes, dev, now - (daysAgo * day).toLong())

  @Test
  fun nothingSaid() {
    val s = CameraVotes.statusOf(emptyList(), lat, lon, "me", now)
    assertEquals(0, s.yes); assertEquals(0, s.no); assertFalse(s.hidden); assertNull(s.mine)
  }

  @Test
  fun twoDriversWhoDidNotSeeItLeaveItOut() {
    val one = CameraVotes.statusOf(listOf(v("a", false, 1.0)), lat, lon, "me", now)
    assertFalse("one driver is not enough", one.hidden)
    val two = CameraVotes.statusOf(listOf(v("a", false, 1.0), v("b", false, 3.0)), lat, lon, "me", now)
    assertTrue(two.hidden)
    // as many who saw it: it stays
    val even = CameraVotes.statusOf(listOf(v("a", false, 1.0), v("b", false, 3.0), v("c", true, 2.0), v("d", true, 5.0)), lat, lon, "me", now)
    assertFalse(even.hidden); assertTrue(even.confirmed)
  }

  @Test
  fun onlyTheLatestAnswerOfADeviceCounts() {
    // the same tablet saying "no" three times is one driver; then "yes" replaces it
    val list = listOf(v("a", false, 10.0), v("a", false, 9.0), v("a", false, 8.0), v("a", true, 1.0))
    val s = CameraVotes.statusOf(list, lat, lon, "me", now)
    assertEquals(1, s.yes); assertEquals(0, s.no); assertFalse(s.hidden)
  }

  @Test
  fun myOwnNoOnTwoDaysLeavesItOutForMe() {
    val sameDay = CameraVotes.statusOf(listOf(v("me", false, 2.0), v("me", false, 2.01)), lat, lon, "me", now)
    assertFalse("twice the same day is once", sameDay.hidden)
    val twoDays = CameraVotes.statusOf(listOf(v("me", false, 5.0), v("me", false, 2.0)), lat, lon, "me", now)
    assertTrue(twoDays.hidden); assertEquals(false, twoDays.mine)
    // a "yes" in between starts again
    val again = CameraVotes.statusOf(listOf(v("me", false, 5.0), v("me", true, 3.0), v("me", false, 2.0)), lat, lon, "me", now)
    assertFalse(again.hidden)
  }

  @Test
  fun farOrOldAnswersDoNotCount() {
    // 60 m away: another camera; three years ago: too old
    val s = CameraVotes.statusOf(listOf(v("a", false, 1.0, 0.00055), v("b", false, 1.0, 0.00055), v("c", false, 3 * 365.0), v("d", false, 3 * 365.0)),
        lat, lon, "me", now)
    assertEquals(0, s.no); assertFalse(s.hidden)
  }

  @Test
  fun messagesGoAndComeBack() {
    val vote = Vote("abcdef0123456789", 45.1502, 10.3792, false, "dev123", now)
    val back = CameraVotes.fromPayload(CameraVotes.payload(vote), now)
    assertEquals(vote, back)
    // what ntfy returns for the topic: one JSON message per line, the answer as text inside
    val line = """{"id":"x1","time":${now / 1000},"event":"message","topic":"navmaster-eu1-cam","message":${
      kotlinx.serialization.json.JsonPrimitive(CameraVotes.payload(vote).toString())}}"""
    val keep = """{"id":"x0","time":${now / 1000},"event":"open","topic":"navmaster-eu1-cam"}"""
    val read = CameraVotes.parseTopic(keep + "\n" + line + "\n")
    assertEquals(listOf(vote), read)
    // the file of the answers keeps them all
    assertEquals(listOf(vote), CameraVotes.decode(CameraVotes.encode(listOf(vote))))
  }

  @Test
  fun badMessagesAreLeftOut() {
    val ok = CameraVotes.payload(Vote("abcdef0123456789", 45.0, 10.0, true, "dev", now))
    fun with(k: String, value: kotlinx.serialization.json.JsonElement) =
        kotlinx.serialization.json.JsonObject(ok.toMutableMap().also { it[k] = value })
    assertNotNull(CameraVotes.fromPayload(ok, now))
    assertNull(CameraVotes.fromPayload(with("a", kotlinx.serialization.json.JsonPrimitive("new")), now))
    assertNull(CameraVotes.fromPayload(with("la", kotlinx.serialization.json.JsonPrimitive(-80.0)), now))
    assertNull(CameraVotes.fromPayload(with("i", kotlinx.serialization.json.JsonPrimitive("x")), now))
    assertNull(CameraVotes.fromPayload(with("d", kotlinx.serialization.json.JsonPrimitive("")), now))
  }
}
