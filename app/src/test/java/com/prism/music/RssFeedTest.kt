package com.prism.music

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.model.Song
import com.prism.music.data.podcast.RssException
import com.prism.music.data.podcast.RssFeed
import com.prism.music.data.podcast.RssGuard
import com.prism.music.data.podcast.RssProblem
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone

/** Feeds as they come: honest and odd ones, and hostile ones. The parser runs on Android's own XML parser here too. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RssFeedTest {
    private val base = "https://pod.example.com/shows/feed.xml".toHttpUrl()

    private fun parse(xml: String, at: okhttp3.HttpUrl = base) = RssFeed.parse(ByteArrayInputStream(xml.toByteArray()), at)

    private fun problemOf(xml: String): RssProblem? = runCatching { parse(xml) }.exceptionOrNull().let { (it as? RssException)?.problem }

    /** Every "&" looked for its ";" to the far end of the text: minutes of work for a feed that's nothing but "&". */
    @Test fun textFullOfAmpersandsTakesNoTime() {
        val text = "&".repeat(1_000_000)
        val started = System.nanoTime()
        assertEquals(text, RssFeed.decodeEntities(text))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 3_000)
    }

    @Test fun entitiesReadAsTheyDidBefore() {
        assertEquals("a & b < c", RssFeed.decodeEntities("a &amp; b &lt; c"))
        assertEquals("AAé…", RssFeed.decodeEntities("&#65;&#x41;&eacute;&hellip;"))
        assertEquals("fish & chips; R&B", RssFeed.decodeEntities("fish & chips; R&B"))
        assertEquals("&; &nosuch; &", RssFeed.decodeEntities("&; &nosuch; &"))
        // The longest it reads, and one past that.
        assertEquals("A", RssFeed.decodeEntities("&#000000065;"))
        assertEquals("&#0000000065;", RssFeed.decodeEntities("&#0000000065;"))
    }

    @Test fun anRss2FeedWithITunesTags() {
        val f = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>The Show</title>
                <link>https://pod.example.com/</link>
                <description><![CDATA[<p>All about <b>things</b>.</p>]]></description>
                <itunes:author>A Host</itunes:author>
                <itunes:image href="https://img.example.com/cover.jpg"/>
                <image><url>https://img.example.com/other.jpg</url><title>Not the show's title</title></image>
                <item>
                  <title>Episode 2 &amp; more</title>
                  <guid isPermaLink="false">ep-2</guid>
                  <pubDate>Tue, 02 Jan 2024 10:00:00 +0000</pubDate>
                  <itunes:duration>1:02:03</itunes:duration>
                  <description>Second.</description>
                  <enclosure url="https://cdn.example.com/2.mp3" length="123" type="audio/mpeg"/>
                  <itunes:image href="https://img.example.com/2.jpg"/>
                </item>
                <item>
                  <title>Episode 1</title>
                  <guid>ep-1</guid>
                  <pubDate>Mon, 01 Jan 2024 10:00:00 GMT</pubDate>
                  <itunes:duration>125</itunes:duration>
                  <content:encoded><![CDATA[First <a href="javascript:alert(1)">click</a>]]></content:encoded>
                  <enclosure url="https://cdn.example.com/1.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>""",
        )
        assertEquals("The Show", f.title)
        assertEquals("A Host", f.author)
        assertEquals("All about things.", f.description)
        assertEquals("https://img.example.com/cover.jpg", f.image)
        assertFalse(f.partial)
        assertEquals(2, f.items.size)
        val second = f.items[0]
        assertEquals("ep-2", second.key)
        assertEquals("Episode 2 & more", second.title)
        assertEquals("https://cdn.example.com/2.mp3", second.audioUrl)
        assertEquals(3723, second.durationSec)
        assertEquals("https://img.example.com/2.jpg", second.image)
        assertEquals("Second.", second.description)
        assertEquals(utc(2024, 1, 2, 10), second.publishedAt)
        assertEquals(125, f.items[1].durationSec)
        // The tag and its address are gone: only its words stay.
        assertEquals("First click", f.items[1].description)
    }

    @Test fun anAtomFeed() {
        val f = parse(
            """<feed xmlns="http://www.w3.org/2005/Atom">
                <title>Atom Show</title><subtitle>About atoms</subtitle><author><name>Ann</name></author><logo>logo.png</logo>
                <entry>
                  <id>urn:ep:1</id><title>One</title><published>2024-03-04T05:06:07Z</published>
                  <summary type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Inside <em>xhtml</em></p></div></summary>
                  <link rel="alternate" href="https://pod.example.com/one"/>
                  <link rel="enclosure" type="audio/ogg" href="/audio/one.ogg"/>
                </entry>
            </feed>""",
        )
        assertEquals("Atom Show", f.title)
        assertEquals("Ann", f.author)
        assertEquals("About atoms", f.description)
        assertEquals("https://pod.example.com/shows/logo.png", f.image)
        val e = f.items.single()
        assertEquals("urn:ep:1", e.key)
        assertEquals("https://pod.example.com/audio/one.ogg", e.audioUrl)
        assertEquals(utc(2024, 3, 4, 5, 6, 7), e.publishedAt)
        assertEquals("Inside xhtml", e.description)
    }

    @Test fun anRss1Feed() {
        val f = parse(
            """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
                <channel><title>Old Show</title><description>Old</description></channel>
                <image><url>https://img.example.com/old.png</url></image>
                <item><title>One</title><dc:date>2020-05-06T07:08:09+02:00</dc:date><link>https://cdn.example.com/one.mp3</link></item>
            </rdf:RDF>""",
        )
        assertEquals("Old Show", f.title)
        assertEquals("https://img.example.com/old.png", f.image)
        assertEquals("https://cdn.example.com/one.mp3", f.items.single().audioUrl)
        assertEquals(utc(2020, 5, 6, 5, 8, 9), f.items.single().publishedAt)
    }

    @Test fun whatRealFeedsGetWrong() {
        val f = parse(
            """

            <rss version="2.0"><channel>
              <title>Odd &nbsp;Show &copy; 2024</title>
              <itunes:image href="cover.jpg"/>
              <item>
                <title>Undeclared prefix, bad length, relative link</title>
                <itunes:duration>about an hour</itunes:duration>
                <pubDate>sometime last week</pubDate>
                <enclosure url="audio/ep.mp3?token=1" type="audio/mpeg; charset=binary"/>
              </item>
              <item>
                <enclosure url="https://cdn.example.com/no%20title.m4a" type="application/octet-stream"/>
                <title></title>
              </item>
              <item><title>A bare & ampersand</title><enclosure URL="http://cdn.example.com/plain.mp3" TYPE="audio/mpeg"/></item>
              <item><title>No type</title><enclosure url="https://cdn.example.com/x.opus"/></item>
              <item><title>Video only</title><enclosure url="https://cdn.example.com/x.mp4" type="video/mp4"/></item>
              <item><title>No type, not audio</title><enclosure url="https://cdn.example.com/x.pdf"/></item>
              <item><title>A playlist</title><enclosure url="https://cdn.example.com/x.m3u8" type="audio/x-mpegurl"/></item>
            </channel></rss>""",
        )
        assertEquals("Odd Show © 2024", f.title)
        assertEquals("https://pod.example.com/shows/cover.jpg", f.image)
        assertEquals(4, f.items.size)
        assertEquals("https://pod.example.com/shows/audio/ep.mp3?token=1", f.items[0].audioUrl)
        assertEquals(0, f.items[0].durationSec)
        assertNull(f.items[0].publishedAt)
        // No title: the file's name.
        assertEquals("no title", f.items[1].title)
        assertEquals("A bare & ampersand", f.items[2].title)
        // http is asked for over https (Prism has no cleartext).
        assertEquals("https://cdn.example.com/plain.mp3", f.items[2].audioUrl)
        assertEquals("https://cdn.example.com/x.opus", f.items[3].audioUrl)
    }

    /** The parser's token mode loses a bare "&" and what follows it once an entity it doesn't know went by. */
    @Test fun aBareAmpersandSurvivesAnUnknownEntityBeforeIt() {
        val f = parse(
            """<rss><channel><title>Odd&nbsp;Show</title>
              <item><title>AT&T &copy; Rock & Roll &nbsp;x&y</title><enclosure url="https://c.example.com/a.mp3?a=1&b=2" type="audio/mpeg"/></item>
            </channel></rss>""",
        )
        assertEquals("Odd Show", f.title)
        assertEquals("AT&T © Rock & Roll x&y", f.items.single().title)
        assertEquals("https://c.example.com/a.mp3?a=1&b=2", f.items.single().audioUrl)
    }

    @Test fun aFeedThatBreaksHalfWayKeepsWhatCameBefore() {
        val f = parse("""<rss><channel><title>T</title><item><title>Good</title><enclosure url="https://c.example.com/a.mp3" type="audio/mpeg"/></item><item><title>Bro""")
        assertEquals("Good", f.items.single().title)
        assertTrue(f.partial)
    }

    @Test fun datesInTheFormsFeedsUse() {
        val r = RssFeed.DateReader()
        val at = utc(2024, 1, 2, 10)
        assertEquals(at, r.read("Tue, 02 Jan 2024 10:00:00 +0000"))
        assertEquals(at, r.read("Tue, 02 Jan 2024 10:00:00 GMT"))
        assertEquals(at, r.read("Tue, 02 Jan 2024 10:00:00 UT"))
        assertEquals(at, r.read("Tue,  2 Jan 2024 05:00:00 EST"))
        assertEquals(at, r.read("02 Jan 2024 10:00:00 +0000"))
        assertEquals(at, r.read("Tue, 02 Jan 2024 10:00 +0000"))
        assertEquals(at, r.read("2024-01-02T10:00:00Z"))
        assertEquals(at, r.read("2024-01-02T10:00:00.123456Z"))
        assertEquals(at, r.read("2024-01-02T12:00:00+02:00"))
        assertEquals(at, r.read("2024-01-02 10:00:00"))
        assertEquals(utc(2024, 1, 2), r.read("2024-01-02"))
        assertEquals(utc(2024, 1, 2), r.read("Jan 2, 2024"))
        assertNull(r.read("next Tuesday"))
        assertNull(r.read(""))
        assertNull(r.read(null))
        assertNull("before podcasts", r.read("Mon, 01 Jan 0001 00:00:00 GMT"))
        assertNull("far in the future", r.read("Mon, 01 Jan 2999 00:00:00 GMT"))
    }

    @Test fun lengths() {
        assertEquals(3723, RssFeed.durationOf("1:02:03"))
        assertEquals(3723, RssFeed.durationOf("62:03"))
        assertEquals(3723, RssFeed.durationOf("3723"))
        assertEquals(3723, RssFeed.durationOf(" 3723.5 "))
        assertEquals(0, RssFeed.durationOf("-3"))
        assertEquals(0, RssFeed.durationOf("1:2:3:4"))
        assertEquals(0, RssFeed.durationOf("NaN"))
        assertEquals(0, RssFeed.durationOf("999999999999"))
        assertEquals(0, RssFeed.durationOf(null))
    }

    @Test fun htmlComesOutAsPlainText() {
        assertEquals("Hello world", RssFeed.plain("<p>Hello <b>world</b></p>", 100))
        assertEquals("Line one\nLine two", RssFeed.plain("Line one<br/>Line two", 100))
        assertEquals("keep", RssFeed.plain("""<script>alert("x")</script>keep<style>p{}</style>""", 100))
        assertEquals("keep", RssFeed.plain("""<SCRIPT src="x">alert(1)</ScRiPt>keep<!-- <b>not this</b> -->""", 100))
        assertEquals("a < b & c", RssFeed.plain("a < b &amp; c", 100))
        assertEquals("Hello", RssFeed.plain("&lt;p&gt;Hello&lt;/p&gt;", 100))
        assertEquals("it’s é €", RssFeed.plain("it&rsquo;s &#233; &#x20AC;", 100))
        // A tag that never ends takes the rest with it, and a script that never ends too.
        assertEquals("start", RssFeed.plain("start<a href='", 100))
        assertEquals("start", RssFeed.plain("start<script>never ends", 100))
        // Only attributes held the link: it isn't kept.
        assertEquals("click", RssFeed.plain("""<a href="javascript:alert(1)" onclick="evil()">click</a>""", 100))
        // Direction overrides and control characters out; a joiner (it matters in many scripts) stays.
        assertEquals("abc‍", RssFeed.plain("a‮b\u0007c‍", 100))
        assertEquals("abcde", RssFeed.plain("abcdefghij", 5))
        assertNull(RssFeed.plain("<p> </p>", 100))
    }

    // ---------------------------------------------------------------- Hostile feeds

    @Test fun aDtdIsRefusedAndItsEntitiesNeverExpanded() {
        val laughs = """<?xml version="1.0"?>
            <!DOCTYPE rss [
              <!ENTITY a "aaaaaaaaaa">
              <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
              <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
              <!ENTITY d "&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;">
              <!ENTITY e "&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;">
              <!ENTITY f "&e;&e;&e;&e;&e;&e;&e;&e;&e;&e;">
              <!ENTITY g "&f;&f;&f;&f;&f;&f;&f;&f;&f;&f;">
              <!ENTITY h "&g;&g;&g;&g;&g;&g;&g;&g;&g;&g;">
              <!ENTITY i "&h;&h;&h;&h;&h;&h;&h;&h;&h;&h;">
            ]>
            <rss><channel><title>&i;</title><item><title>&i;</title><enclosure url="https://c.example.com/a.mp3" type="audio/mpeg"/></item></channel></rss>"""
        assertEquals(RssProblem.NOT_A_FEED, problemOf(laughs))
    }

    @Test fun anExternalEntityIsRefused() {
        val xxe = """<?xml version="1.0"?>
            <!DOCTYPE rss [ <!ENTITY secret SYSTEM "file:///data/data/com.prism.music/shared_prefs/discord.xml"> ]>
            <rss><channel><title>&secret;</title><item><title>&secret;</title><enclosure url="https://c.example.com/a.mp3" type="audio/mpeg"/></item></channel></rss>"""
        assertEquals(RssProblem.NOT_A_FEED, problemOf(xxe))
        val external = """<!DOCTYPE rss SYSTEM "https://evil.example.com/x.dtd"><rss><channel><item><enclosure url="https://c.example.com/a.mp3" type="audio/mpeg"/></item></channel></rss>"""
        assertEquals(RssProblem.NOT_A_FEED, problemOf(external))
    }

    /** Not where a DTD may be: the parser stops there as at any break, and nothing it would have declared is ever used. */
    @Test fun aDtdInsideTheFeedDeclaresNothing() {
        assertEquals(
            RssProblem.NOT_A_FEED,
            problemOf("""<rss><!DOCTYPE rss [ <!ENTITY a "expanded"> <!ENTITY s SYSTEM "file:///etc/hosts"> ]><channel><title>&a;&s;</title><item><title>&a;</title><enclosure url="https://c.example.com/a.mp3" type="audio/mpeg"/></item></channel></rss>"""),
        )
        val late = parse("""<rss><channel><title>T</title><item><title>Before</title><enclosure url="https://c.example.com/a.mp3" type="audio/mpeg"/></item><!DOCTYPE x [ <!ENTITY a "expanded"> ]><item><title>&a;</title><enclosure url="https://c.example.com/b.mp3" type="audio/mpeg"/></item></channel></rss>""")
        assertEquals("Before", late.items.first().title)
        assertTrue(late.items.none { it.title.orEmpty().contains("expanded") })
    }

    @Test fun anUndeclaredEntityStaysWords() {
        val f = parse("""<rss><channel><title>&secret; show</title><item><title>&lol;</title><enclosure url="https://c.example.com/a.mp3" type="audio/mpeg"/></item></channel></rss>""")
        assertEquals("&secret; show", f.title)
        assertEquals("&lol;", f.items.single().title)
    }

    @Test fun linksThatArentWebLinksAreDropped() {
        val f = parse(
            """<rss><channel><title>T</title>
              <itunes:image href="file:///data/data/com.prism.music/files/x"/>
              <image><url>content://media/external/images/1</url></image>
              <item><title>js</title><enclosure url="javascript:alert(1)" type="audio/mpeg"/></item>
              <item><title>JS mixed case</title><enclosure url="JaVaScRiPt:alert(1)" type="audio/mpeg"/></item>
              <item><title>file</title><enclosure url="file:///sdcard/x.mp3" type="audio/mpeg"/></item>
              <item><title>content</title><enclosure url="content://media/external/audio/1" type="audio/mpeg"/></item>
              <item><title>data</title><enclosure url="data:audio/mpeg;base64,AAAA" type="audio/mpeg"/></item>
              <item><title>intent</title><enclosure url="intent://x#Intent;end" type="audio/mpeg"/></item>
              <item><title>ftp</title><enclosure url="ftp://example.com/x.mp3" type="audio/mpeg"/></item>
              <item><title>own network</title><enclosure url="http://192.168.1.1/reboot.mp3" type="audio/mpeg"/></item>
              <item><title>this phone</title><enclosure url="http://localhost:47320/x.mp3" type="audio/mpeg"/></item>
              <item><title>an address in disguise</title><enclosure url="http://2130706433/x.mp3" type="audio/mpeg"/></item>
              <item><title>a login</title><enclosure url="https://user:pass@example.com/x.mp3" type="audio/mpeg"/></item>
              <item><title>no enclosure</title><description>Nothing to play</description></item>
              <item><title>fine</title><enclosure url="https://c.example.com/a.mp3" type="audio/mpeg"/><itunes:image href="javascript:alert(1)"/></item>
            </channel></rss>""",
        )
        assertEquals(listOf("fine"), f.items.map { it.title })
        assertNull(f.items.single().image)
        assertNull(f.image)
    }

    @Test fun aFeedWithNoAudioIsSaidToHaveNone() {
        assertEquals(RssProblem.NO_EPISODES, problemOf("""<rss><channel><title>T</title><item><title>Just text</title></item></channel></rss>"""))
        assertEquals(RssProblem.NO_EPISODES, problemOf("""<rss><channel><title>T</title></channel></rss>"""))
    }

    @Test fun whatIsntAFeedIsSaidNotToBeOne() {
        assertEquals(RssProblem.NOT_A_FEED, problemOf("""<!doctype html><html><head><title>A site</title></head><body>Hi</body></html>"""))
        assertEquals(RssProblem.NOT_A_FEED, problemOf("""<html><body>Hi</body></html>"""))
        assertEquals(RssProblem.NOT_A_FEED, problemOf("""{"this": "is json"}"""))
        assertEquals(RssProblem.NOT_A_FEED, problemOf(""))
        assertEquals(RssProblem.NOT_A_FEED, problemOf("""<rss><channel><title>Broken"""))
        assertEquals(RssProblem.NOT_A_FEED, runCatching { RssFeed.parse(ByteArrayInputStream(ByteArray(4096) { (it % 251).toByte() }), base) }.exceptionOrNull().let { (it as? RssException)?.problem })
    }

    /** Stands for a feed that never ends, or a gzip bomb once it's unpacked. */
    private class Endless(head: String, private val repeat: String) : InputStream() {
        private var current = head.toByteArray()
        private var at = 0
        var served = 0L
            private set

        override fun read(): Int {
            if (at >= current.size) { current = repeat.toByteArray(); at = 0 }
            served++
            return current[at++].toInt() and 0xFF
        }
    }

    @Test fun aHugeFeedIsReadOnlySoFarAndKeepsItsNewest() {
        val item = """<item><title>E</title><guid>g</guid><description>${"x".repeat(2_000)}</description><enclosure url="https://c.example.com/a.mp3" type="audio/mpeg"/></item>"""
        val s = Endless("<rss><channel><title>Huge</title>", item)
        val f = RssFeed.parse(s, base)
        assertTrue(f.partial)
        assertEquals("Huge", f.title)
        assertTrue("it read ${s.served} bytes", s.served <= RssFeed.MAX_BYTES + 1)
        assertTrue(f.items.isNotEmpty())
    }

    @Test fun tooManyItemsAreCutOff() {
        val item = """<item><title>E</title><enclosure url="https://c.example.com/a.mp3" type="audio/mpeg"/></item>"""
        val f = RssFeed.parse(Endless("<rss><channel><title>Many</title>", item), base)
        assertEquals(RssFeed.MAX_ITEMS, f.items.size)
        assertTrue(f.partial)
    }

    @Test fun oneEnormousTextAndDeepNestingAreBounded() {
        val big = parse("""<rss><channel><title>${"t".repeat(2_000_000)}</title><description>${"d".repeat(1_000_000)}</description><item><title>E</title><enclosure url="https://c.example.com/a.mp3" type="audio/mpeg"/></item></channel></rss>""")
        assertEquals(RssFeed.MAX_TITLE, big.title!!.length)
        assertEquals(RssFeed.MAX_SHOW_DESCRIPTION, big.description!!.length)
        val deep = "<rss><channel><title>Deep</title><item><title>E</title><enclosure url=\"https://c.example.com/a.mp3\" type=\"audio/mpeg\"/></item>" + "<x>".repeat(200_000)
        assertEquals("E", parse(deep).items.single().title)
        // No end tags and no items at all.
        assertEquals(RssProblem.NOT_A_FEED, runCatching { parse("<rss>" + "<a>".repeat(200_000)) }.exceptionOrNull().let { (it as? RssException)?.problem } ?: fail("no problem"))
    }

    @Test fun aLinkLongerThanAnyRealOneIsDropped() {
        val long = "https://c.example.com/" + "a".repeat(5_000) + ".mp3"
        assertEquals(RssProblem.NO_EPISODES, problemOf("""<rss><channel><item><title>T</title><enclosure url="$long" type="audio/mpeg"/></item></channel></rss>"""))
    }

    // ---------------------------------------------------------------- Addresses

    private fun addressProblem(s: String): RssProblem? = runCatching { RssGuard.feedAddress(s); null }.getOrElse { (it as RssException).problem }

    @Test fun whatCanBePastedAsAFeedAddress() {
        assertEquals("https://pod.example.com/feed.xml", RssGuard.feedAddress(" https://pod.example.com/feed.xml\n").toString())
        // Asked for over https, whatever was pasted.
        assertEquals("https://pod.example.com/feed.xml", RssGuard.feedAddress("http://pod.example.com/feed.xml").toString())
        assertEquals("https://pod.example.com/feed.xml", RssGuard.feedAddress("pod.example.com/feed.xml").toString())
        assertEquals("https://pod.example.com:8443/feed.xml", RssGuard.feedAddress("pod.example.com:8443/feed.xml").toString())
        assertEquals("https://pod.example.com/feed.xml", RssGuard.feedAddress("feed://pod.example.com/feed.xml").toString())
        assertEquals("https://pod.example.com/feed.xml", RssGuard.feedAddress("feed:https://pod.example.com/feed.xml").toString())
        assertEquals("https://pod.example.com/feed.xml", RssGuard.feedAddress("itpc://pod.example.com/feed.xml").toString())
        assertEquals("https://pod.example.com/f?id=1&t=x", RssGuard.feedAddress("https://pod.example.com/f?id=1&t=x").toString())
    }

    @Test fun whatCantBe() {
        for (bad in listOf(
            "", "   ", "javascript:alert(1)", "JAVASCRIPT:alert(1)", "file:///sdcard/feed.xml", "content://media/1", "data:text/xml,<rss/>",
            "ftp://example.com/feed", "intent://x", "two words.example.com", "https://user:pass@example.com/feed", "https://" + "a".repeat(3_000) + ".com",
            "http://", "https://exa mple.com/",
        )) assertEquals("\"${bad.take(60)}\"", RssProblem.BAD_ADDRESS, addressProblem(bad))
        for (own in listOf(
            "http://localhost/feed", "https://127.0.0.1/feed", "http://192.168.1.1/feed", "http://10.0.0.5:8080/feed", "http://172.16.0.1/feed", "http://169.254.169.254/latest",
            "http://[::1]/feed", "http://[fe80::1]/feed", "http://[fc00::1]/feed", "http://[::ffff:192.168.1.1]/feed", "http://0.0.0.0/feed", "http://100.64.0.1/feed",
            "http://2130706433/feed", "http://0x7f.0.0.1/feed", "http://127.1/feed", "http://nas/feed", "http://printer.local/feed", "http://router.lan/feed", "http://x.internal/feed", "http://foo.localhost/feed",
        )) assertEquals(own, RssProblem.OWN_NETWORK, addressProblem(own))
    }

    @Test fun publicNamesAndAddressesAreFine() {
        for (ok in listOf("feeds.example.com", "bad.cafe", "fade.de", "8.8.8.8", "93.184.216.34", "2606:4700:4700::1111")) assertFalse(ok, RssGuard.isPrivateHost(ok))
        assertNotNull(RssGuard.link("/a.mp3", base))
        assertEquals("https://pod.example.com/a.mp3", RssGuard.link("/a.mp3", base))
        assertEquals("https://cdn.example.com/a.mp3", RssGuard.link("//cdn.example.com/a.mp3", base))
    }

    @Test fun anEpisodeFromAFeedIsMarkedAsOne() {
        assertTrue(Song("rss:abc:def", "x").isRss)
        assertFalse(Song("rss:abc:def", "x").isLocal)
        assertFalse(Song("local:1", "x").isRss)
        assertFalse(Song("dQw4w9WgXcQ", "x").isRss)
    }

    private fun utc(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis
}
