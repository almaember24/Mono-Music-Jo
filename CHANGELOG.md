# Changelog

What changed in each release. Older releases are described on the [Releases page](https://github.com/Yigder/Prism/releases).

## 1.8.3.1

### Fixes

- **Songs that stop with "HTTP 403" partway through** (#408) now get a fresh link from another source and play on. Downloads that hit it start over with a new link instead of retrying the refused one.
- **Music videos that stopped at the same spot every time** (#411). When part of the video's picture was already saved in Downloads, the stream carried on from where that part ended, and the two didn't fit together.
- **A new playlist shows in Library at once** (#414). YouTube takes a minute or two to list it, and until now Library waited for that. Its cover comes in once YouTube has it.
- **Add to playlist's list stays put on a fast swipe** (#401), instead of pushing the whole sheet up and bouncing.
- **The screen stays on while a Replay story plays** (#412).
- **Opening a song's menu from Search's typed list keeps the list** (#395). It closed behind the menu.
- **Search finds songs and videos where YouTube Music needs Premium** (#396, South Korea). Signed in, a song or video search that comes back empty is asked again with your account.
- **KuGou finds lyrics when the artist's name is written in another case** (#403), like KAIRO.
- **Synced lyrics no longer jump at each new line** (#399), and the sung line's letters (the tails of g and y, accents, Glow's light) are no longer cut off.
- **Restoring a backup, applying a Look or signing in** no longer has a setting changed a moment before undo part of it.

## 1.8.3

### Your library, smarter

- **Smart playlists.** Library → Playlists → Smart playlist. A playlist made of rules that fills itself and keeps up as your library changes. Rules on the sound (BPM, a key that goes with another, energy, loudness), your library (liked, downloaded, a file on this phone, a tag, genre, artist, album) and your listening (times played, last played, added, never played), meeting all of them or any. Sort and a limit, three starters to pick from ("Fast and liked", "Not played in a while", "Goes with what's playing"), and "Save a copy as a playlist" for a fixed copy. They stay in Prism and in your backups; nothing goes to YouTube.
- **Tempo, key and energy in one place.** What Mix and DJ mode measure is now kept where smart playlists can use it, and energy is measured in the same scan with no extra work. Songs not measured yet are left out of sound rules; the playlist says how many and has **Measure now** (the songs on the phone, and the others on Wi-Fi).
- **Tags** (your own labels, like "gym" or "rainy day"). From any song's menu → Add a tag. A song can have several. Library → Tags shows each tag as a page you can play, shuffle, rename or delete. They stay on the phone and in your backups.
- **Select many** (#381). Hold a song in a list to start picking, tap more, or All. Then Play next, Add to queue, Add to playlist, Add a tag, Like or Unlike, Download or Remove download, and Remove from this playlist. On a YouTube Music playlist, removing asks first, since it changes the playlist on your account.
- **Search your library.** The search button at the top of Library, or "In your library" in Search. Songs, playlists, tags, artists and albums as you type, with filters for liked, downloaded and on this phone.
- **Titles in other alphabets found in Latin letters** (#363). "tori no uta" finds とりのうた. Japanese kana, Korean, Russian and Greek work right away; Japanese kanji once Prism's Japanese dictionary is downloaded. Made in the background, a few songs at a time.
- **Songs from the playlists you choose** (#382). Library → Songs → Songs from: your likes, or any of your playlists together, each song once. Only what the tab shows changes; nothing gets liked.
- **Find duplicates.** A playlist's ⋯ menu → Find duplicates: songs that are in it twice, also as a video and its song, or two uploads of the same track. Live versions and remixes are kept apart. Remove the extra copies in one go, with a check first.
- **A file on your phone as a song's version** (#243). Play a different version now lists the matching files on your phone as "Your file", and "Choose a file on this phone" picks any other. The song then plays from the file; Original goes back.

### New

- **Lock screen: Repeat and Up next.** Customize Prism → Player → Lock screen. Each one is its own switch, off by default. Up next shows the next three songs and can't be tapped: the lock screen still only controls what's playing.
- **A pinned album or playlist as one tile on Home.** Its menu → Pin to Home as one tile, or Customize Home → its menu → One tile. A cover with a play button, instead of its song list.
- **Uploads: upload and delete.** The upload button on the Uploads page sends a song file from your phone to your YouTube Music uploads (MP3, M4A, WMA, FLAC and Ogg, up to 300 MB). A song's menu there has Delete from your uploads, with a check first, since it can't be undone.
- **Smart Downloads can use less space:** 256 MB and 512 MB join the choices.
- **TV: seek with Left and Right on the player.** Press Up from the controls to the progress line, then Left or Right moves 10 seconds; hold it and it speeds up to 30.

### Fixes

- **Opening and closing mixes no longer stutters with Show genres on** (#228). Genres were looked up while the screen waited; they now come in when they're ready.
- **After Prism is closed by the phone or crashes, the song carries on near where it was** (#388), not from the start. The place is kept every 10 seconds while playing.
- **The next song's animated cover shows** (#384). It stayed invisible after the first one.
- **Songs played from a file on your phone get the check mark everywhere** (#385), not only on some lists.
- **Downloading a music video as audio saves the video's sound** (#390), not the album track, unless "Songs, not videos" is set to Everywhere.
- **"Songs, not videos: Everywhere" works on more Home playlists** (#383), and songs that YouTube lists as episodes get ⏮ and ⏭ instead of ⏪ and ⏩.
- **Plays reach a brand account's YouTube history** (#377).
- **Casting to Sonos and other speakers keeps going in the background** (#239). Prism now holds on while the phone sleeps, and a speaker that can't be reached while Android blocks the network no longer counts as a missed song.
- **The lock screen player on vivo and Xiaomi (HyperOS) phones.** Its page now shows the extra permission those phones ask for, with a button to it, and says so when the phone didn't let the player come up.
- **Moods & genres opens at once** after the first time: it's kept for a day, and shown offline too.
- **TV: lyrics without timing stay where you scrolled,** instead of jumping back to the top.
- **Dragging songs in the queue** moves the song once, when you let go, instead of at every row it passes. A fast drag no longer leaves it where it was.
- **ONNX Runtime's start-up helper is gone.** Sing's voice separation library added one that ran at every start, though only Sing uses it.

## 1.8.2.1

### Faster and lighter

- **Background work keeps to a budget** (#373). Song scans, video sync and Sing getting songs ready now slow down when nobody needs them: about one core's worth while music plays out of sight, a little for a minute after you pause and leave, then nothing until you're back. A OnePlus 11 had Prism closed by Android three times for using the processor in the background; on a Galaxy A16, leaving Prism paused right after starting a new album went from about 115% of a core in the first minute to 14%, and the first minute of a new album playing with the screen off from about 145% to between 60% and 90%.
- **Fewer scans out of sight.** With the screen off, Prism scans only the next song, not the next three. The rest wait until you're back or until they're next.
- **Videos are measured ahead only if you watch them.** A listener who hasn't picked Video in 14 days no longer pays for it. The first video after that starts at the song's own time, which can be off by the video's intro; the songs after it are measured ahead again.
- **Far less memory churned by scans.** The loudness step uses 0.2 MB instead of 24 MB, the key step 8 MB instead of 23 MB, with the same results. The phone's garbage collector ran about a quarter as much during scans.
- **Sing's helper thread sleeps while Sing is off.** It woke about four times a second for nothing.
- **The player opens more smoothly** (#354, #343). Its cover and background are ready before it opens, and it now slides without a fade on top. On a Galaxy A16 the worst frame of an opening went from about 60 to about 50 ms (63 to 47 ms in Performance mode).
- **Frosted glass costs less.** It works from a smaller copy of what's behind it. Scrolling a long playlist on a Galaxy A16, the typical frame went from about 32 to 25 ms and the share of slow frames from about 30% to under 10%; Home barely moved.
- **Quick picks come sooner** (#371). Prism asks for what it needs at once, instead of one thing after another, and today's set is kept for the next time you open Prism.
- **Music video lookups are quicker** (#373). Looking one up ahead of time no longer asks for your account's Premium stream, which a video never has.

### New

- **Speed dial: your plays first** (#372). Customize Home. Tiles that change as you listen. Off by default.
- **New folder in Library.** Playlists → New folder makes a folder before anything's in it; until now one could only be made while moving a playlist.
- **Search in Library** (#357). Playlists, Albums and Artists get a search at the top once there are 8 or more. Playlists finds the ones inside folders too, and folders by name.
- **A scrollbar you can hold, on Library's long lists and Search's results** (#376). It shows while the list moves, is wide enough for a finger, and only the bar itself takes touches, so everything else along the edge still works.
- **Translations in Immersive mode** (#358). Your lyric translation shows under the lines, as on the lyrics page, with the switch in its menu too.

### Fixes

- **Quick picks:** pulling down to refresh now really gives you other songs, and the section stays on screen while the new ones load, or if they can't be made (#371). After signing out or switching accounts, Home no longer keeps showing the last account's lists.
- **Undo after picking a glass style** now takes all of it back. It could leave part of the choice behind.
- **Premium quality:** music videos looked up in the background could stop Prism asking for 256 kbps for an hour. They don't count any more, the pause is much shorter on an account that has had 256 kbps, and it ends when you change networks or sign-ins. If one of your account's ways of asking has no 256 kbps, Prism tries the others (#373).
- **Premium quality says what's happening.** Settings tells you when YouTube isn't sending your account 256 kbps, and when YouTube no longer takes your sign-in, instead of songs just playing at the usual quality (#373).
- **Your own photo as a background works in every language** (#378). In German, Italian, Russian, Polish, Swedish, Hungarian, Malay and Filipino, the picture was saved under a translated name, so Prism didn't see it as a picture and showed Ambient (black with Pure black on). Photos you already picked come back by themselves, in your saved Looks too.
- **Prism in other languages:** Replay no longer lists songs of unknown genre as a genre of their own, unknown albums sort last in Local files, and excluded artists get round pictures.
- **Google Takeout's history is recognized in any language** (#375). The file's name changes with your account's language (`historial-de-reproducciones.json` in Spanish), so Prism now goes by what's in it.
- **Japanese kanji no longer turn into Chinese pinyin in romanized lyrics** (#370). Some sources, KuGou above all, write a Japanese song's kanji in Chinese forms, and a line of only kanji could pass for Chinese. Prism now decides for the whole song and reads them as Japanese.
- **Replay's summary card no longer cuts its title off** ("TODA" for Today). The title now shrinks until it fits on its line, in every period and language, and so do the story's big words.
- **An album's description is easier to read.** Left-aligned in its own quiet box under Play and Shuffle, closed to a few lines with Show more, and the Wikipedia and license credit on its own small line with tappable links instead of raw addresses.
- **Play next on a song already in your queue** moves it up instead of adding a second copy (#368).
- **The queue lists songs in the order they really play.** A song you added to the queue was shown first, but played after the rest of the list, so Next seemed to skip it (#368).
- **Crossfade:** Play next, or taking a song out of the queue, just before a blend no longer sends it to the wrong song or cuts it off (#368).
- **The current lyric,** when you move it away from the progress bar, sits midway between its neighbors on the player instead of low down against the next one (#361).
- **Playback diagnostics say more:** what Premium quality asked for and got, a summary of your sign-in (never its secrets), what happened to each play sent to your YouTube history, and for each crossfade whether the ending song lost time, how the two crossed over and how long the next one took to start (#364, #372, #373, #377).

## 1.8.2

### Lock screen

- **Prism's player on your lock screen.** Customize Prism → Player → Lock screen. It's off until you turn it on, and Android's own media controls stay as they are, on or off: this sits above them only while music is playing.
- **Four layouts, shown as example cards:** Cover, Full cover, Lyrics (the words as they're sung) and Quiet. They follow your Look: font, light, corners.
- **It only controls what's playing.** Like, previous, play and pause, next, seek and volume; no library, search, song menu or settings can be reached while the phone is locked.
- **Leaving it:** swipe up to unlock, or tap the cover to unlock and open the full player. On a phone with an under-screen fingerprint reader, Android only takes your finger after that swipe or tap. It closes once you've unlocked, leaving you where you were, with the music still playing. Calls and alarms stay above it.
- **Your choices:** show or hide volume, the heart and where the sound is going; stay while paused for 10 minutes, until the phone sleeps, or not at all; "Hide the song's name and cover while locked".
- **Light on the phone:** Performance mode always uses Quiet, with no blur or movement. It draws nothing with the screen off.
- **It asks for "Display over other apps" when you turn it on** (Android 10 and up), because Android only lets an app come up by itself as the screen goes off with that permission. Prism never draws over other apps with it. Without it, some phones can still bring the player up a moment after waking, and others won't show it at all (a Pixel doesn't). From Android 13, Android may first say "App was denied access", because Prism doesn't come from a store. The way through is on the setting's page: Prism's App info → ⋮ at the top right → Allow restricted settings, then allow the permission.

### New

- **No repeats this session** (#159). Settings → Playback & sound → What plays next. Off until you turn it on. Autoplay and
  radios then skip every song that's played this session, even one you skipped after a few seconds. When a radio has
  nothing new left it moves on to the radio of another song you played, then your own music; only after that do the songs
  you heard longest ago come round again, and Prism tells you.
- **New to you.** About 30 songs you've never played, new every Monday, found on your phone from the radios of your
  favorites. Never played means ever, including imported histories. Add it from Customize Home.
- **Deep cuts.** Album tracks you've never played by artists you love, leaving out their top songs and live or remixed
  versions. Add it from Customize Home.
- **On this day.** What you played a year ago today. With nothing played that day, the section doesn't show. Add it from
  Customize Home.
- **Tune your radios.** Settings → Recommendations & Replay. Sliders for familiar or new, hits or deep cuts, calm or
  energetic, all in the middle (as before) until you move them. Calm or energetic can only go by songs Prism has already
  scanned, so most songs you haven't heard stay where they are.
- **Crossfades up to 30 seconds** (#351). Playback & sound → Crossfade → Length now goes past 12. Automatic length is unchanged.
- **"Songs only" for skipping silence** (#325). Off, Songs only, Podcasts only or Songs and podcasts; the ⓘ says what each one does.
- **Longest song for Autoplay** (#299). Playback & sound → What plays next: keep hour-long mixes out of Autoplay. Songs you pick
  or queue yourself, and radios you start, play whatever their length.
- **No videos on mobile data** (#281). With Data saver on, Downloads, storage & data → Mobile data. Music videos show as
  unavailable there, except ones you've downloaded, and a video playing when Wi-Fi drops carries on as audio. The next song on Wi-Fi
  is a video again.
- **Gestures below the lyrics** (#282). Controls → Gestures: while the lyrics are open, your swipes, double tap and long press
  work on the controls below them. Scrolling the lyrics stays just that.
- **The lyrics, steadier** (#280). Player → Lyrics and the queue. "Reopen on the last page": the player opens on the cover, the
  lyrics or the queue, whichever you left it on, and Back closes the player from those pages. "Steadier lyrics": the controls take a
  longer swipe to come and go, and after a scroll the lyrics go back to the sung line sooner when it's close.
- **The cover flies back too** (#223). With "Cover flies into place" on, a second switch: closing the player, the cover flies back
  into the mini player the way it came.
- **More room for a full-bleed picture** (#293). Player → Cover: over a full-bleed cover or video, the controls close up, a hidden
  volume bar leaves no blank space, and the picture reaches further down and fades later. And "Video buttons wait for a tap": the small
  buttons on a music video stay hidden until you tap it.
- **A tidier queue** (#294, #331). Player → Lyrics and the queue, each its own switch: "Song lengths in the queue"; "Move songs you've
  played", with handles among the played songs; "Tidier queue", which takes the × off every song (swipe it away, or use its ⋯)
  and the Queue button off the bottom row while the queue's open (Back or pulling its title down leaves it).
- **Artist pages work offline.** A page you've opened stays on your phone, so it opens without a connection as it was
  when you last saw it, with a note saying so. What isn't on your phone is dimmed and doesn't play. For an artist you
  haven't opened, Prism makes a page from your downloaded, liked and played songs and their albums. Android Auto's
  artist folders list what can play offline too.
- **Your uploads in Library** (#332, #101). If you've uploaded music to YouTube Music, an Uploads tile shows up in Library
  (signed in) with your songs, albums and artists, to browse, sort and search. If you have no uploads, nothing changes.
  This one was built without an account that has uploads, so if it's missing or looks wrong for you, please tell us.
- **Podcasts by RSS.** Podcasts → Add a podcast by RSS: paste a show's feed address, and it's kept on your phone and in
  your backups, with its cover, description and episodes, newest first. Episodes play straight from the podcast's own
  server, no YouTube and no account needed, with everything episodes do here: they pick up where you left off, speed from
  the player's menu, 15 and 30 second skips. New episodes show up when you open the show. Secure (https) servers on the
  public internet only. Not yet: downloading these episodes, new-episode alerts.
- **Song details** (#211). A song's menu or the player's ⋯ → Song details: its plays on YouTube Music (views, for a music video), the date
  it was released and how many times you've played it. Only what YouTube really says shows up: a video has the day it
  went up on YouTube instead of a release date, and anything it doesn't say isn't there.
- **Saved while playing** (#159). Songs that reached your phone in full as they played, listed in one place so you can
  play them with no connection. Open it from the Downloads page's ⋯, or add its tile to Library with the edit button.
  Prism still clears these as it needs the room; download a song to keep it for good.
- **More on artist pages, if you want it.** Three new switches in Customize Prism → Home & Library → Artist pages, all off
  until you turn them on. *You and this artist:* your first play, plays, time and top song, counted on your phone and
  never sent anywhere. *Essentials for you:* their top songs, moved by what you play. *Bio from Wikipedia:* a short bio in
  your language, credited and linked, kept for offline. It only shows when Prism is sure it's the right article (the
  article has to be about a musician and name that artist's own albums or songs), so some artists won't have one.
- **Explore.** At the end of Search, before you type: YouTube Music's charts by country, moods and genres, new releases
  and what's trending, in one place. Which charts there are depends on the country. Search's Arrange moves or hides the
  line that leads here; if you've arranged Search before, turn it on there.
- **Lyrics from your own music files** (#344). Settings → Lyrics → Your music files → "Read lyrics from your music files"
  (off until you turn it on). Lyrics saved in a file's tags, synced or plain, work with no internet: FLAC, Ogg, Opus, MP3
  and M4A. They take their place in your lyrics sources' order, last to start with: move them up to use them first.
- **Leave folders out of Local files** (#207, #249). Local files → Edit → Choose folders, or Settings → Downloads,
  storage & data → Folders in Local files. Untick a folder and it stops showing, with the folders inside it. Nothing
  happens to the files themselves.
- **Skip songs when a playlist downloads** (#221). A song's ⋯ on a playlist or album → Skip in downloads, or the new
  button in Failed downloads. A skipped song stays out of whole-list downloads and isn't tried again, and the list counts
  as downloaded without it. You can still download that song by itself.
- **Keep a playlist downloaded** (#298, thanks to stuntsnakee, PR #304). A playlist's ⋯ → Keep downloaded, or Settings →
  Downloads, storage & data → Keep playlists downloaded. New songs come down by themselves, following Download on Wi-Fi
  only, and skipped or failed songs are left alone. Nothing is deleted when a song leaves the playlist.
  A download you delete stays deleted; deleting every download also stops keeping every playlist, so they don't all come back.

- **Cover gradient, a new player background** (#232, #338). The cover's own colors as a solid gradient, with nothing blurred:
  its main color at the top, deepening toward the bottom. "Darkness at the bottom" sets how far it goes, and the colors stay
  dark enough for the white text whatever the cover. Customize Prism, Player, Look, where the backgrounds are now example cards.
- **Set your glass** (#252). Blur and Frost sliders on the Glass page, whose choices are now real pieces of each glass, and an
  explanation of what really differs between them. Clear has no Blur: it has next to none to set. At 100% they're what you have
  today. They need Android 12's blur; below it the bars are solid and the sliders don't show.
- **Glass on more of the screen** (#326, off unless you turn it on). Cards, chips and round buttons get a lit edge and follow
  Frost, without a live blur, and the Undo bar becomes real glass while it's up. Performance mode turns it off. A first step.

### Fixes

- **"Player stuck playing with no progress"**: when a song's position stops moving (seen at 0:00 over Bluetooth), Prism now
  tries the song again from the same place, twice, instead of giving up on it, and says what to try in plain words if it
  still won't go. The cause on that phone isn't known yet; Share diagnostics now records it.
- A skip just as a song was recovering from a failure (a lost decoder, a cached part or download that couldn't be read) no
  longer starts the next song partway through, at the place the last one had reached.
- A song YouTube offers in copy-protected formats no longer tries to play them, it uses a format that can play (idea from
  CodeWormer's PR #240).
- **Speakers and TVs on your network can be found again, Sonos among them** (#239). Android stopped Prism reading what a
  speaker says about itself, because speakers only speak plain HTTP, so none of them were listed. Prism now reads them
  on the local network only; everything else it asks for stays secure. There's no Sonos here to try it on: this was
  checked against a real Sonos One SL's own description. If a speaker still doesn't show, Share diagnostics now says why.
  A device can only speak for itself: Prism takes an answer only for the address it came from and sends a device's actions only to it.
- **Liquid Glass · Clear and Regular no longer draw the same on the docked tab bar** (#252). There Clear is now a thin frost that
  lets what's behind it show. In dark mode Clear also loses the extra black Prism laid over it everywhere. Regular is unchanged.
- **Liquid Glass follows Prism's theme, not the phone's dark mode.** With Prism in dark and the phone in light (or the other way
  round) it picked the other theme's values.
- **Typing in Search and in Settings' search keeps up on slower phones** (#355).
- **A song YouTube won't play no longer stops the music.** Prism says which song it was and goes on to the next, also
  when the next songs are still on their way from Autoplay; Next, Previous and the scrubber work again if it did have to stop.
- **Prism TV: Sing's lyrics stay on screen.** The album cover could take their place and stay there.
- **Prism TV: leaving Sing goes back to the player.** After a long song with the remote put down, the cover could come up
  instead, as if the buttons had stopped working.
- **Prism TV: the song's title in the side menu is no longer cut off at the bottom.** Now playing also fits every song
  title and text size now, with all its buttons on screen, and the side panels no longer let it show through.
- **Play and Shuffle from a playlist's long-press menu start right away** (#356): they used to wait for the whole playlist
  to load, showing nothing. Now they start on its first songs and the rest follows, shuffled in among what's to come.
- **Covers inside your own music files show in the notification and in the car** (#350), not just in the player.
- **Speed dial shows all of YouTube Music's tiles** with "YouTube Music's only" on (#349): a short last page stays.
- **New choice: Quick picks from Prism or YouTube Music** (#349, Customize Home → Options); YouTube's start a radio too
  with "Quick picks start a radio" on.
- **An episode YouTube won't play now says what to do**: a show you added to your YouTube Music account by RSS can stop
  with "Video not available". Prism says so plainly, offers the RSS way above, and moves on to the next thing in your
  queue if there is one.

## 1.8.1.1

A bug-fix update.

### Update banner

- **The update banner shows up more reliably, on phones and TVs.** Prism now looks for a new version each time it comes
  to the front (at most hourly): it used to look only when Home was first drawn, and an app that stays open for days
  might not look again. And when GitHub's service turns the check away, which happens on shared connections (mobile
  networks, VPNs), Prism reads the version from GitHub's release page instead of quietly showing nothing.

### Changed

- **Smart Downloads starts off on a new install** (#339). It downloads the songs you're likely to play ahead (up to
  1 GB, on Wi-Fi), so it now asks first: Home offers it once after setting up. If you already use Prism nothing
  changes: it stays on, or off, as you had it.

### Fixes

- **Search no longer closes as it opens** (#346, #330): in some languages Prism's own "For you" moods and YouTube's had
  the same name, and two headings with one name closed the app. Moods & genres, artist and genre pages, podcasts and
  Search's lists were made safe the same way.
- **The Glass setting says what Regular and Clear do** (#324): Regular blurs what's behind it, Clear keeps it sharp
  with only a light tint (it said "Regular is fuller", which read the other way round).
- **The DJ button can leave the queue** (#275): Customize Prism → Player → Lyrics and the queue → "DJ button in the
  queue", for more room at the queue's top. DJ mode can still be one of the player's buttons. On, as before, until
  turned off.
- **Play a different version shows the version you picked (#328).** Its cover, title, artists, album (the player's
  album link too) and length now show wherever the song does: the player, the mini player, the queue, lists, the song
  menu, the notification, Android Auto and widgets. The song is still the one you liked, saved and played; "Use the
  original" puts everything back. A music video that plays as itself (no song of its length) gets its song's cover
  before it starts, and its song's album.
- **"Show lyrics" can leave the player's menu (#345).** With the Lyrics button off the player, the ⋯ menu's "Show
  lyrics" couldn't be hidden. Edit this menu lists it now: switched off, it waits under More, as long as the player
  opens the lyrics another way (the current lyric, a swipe up on the controls or a cover gesture); while it's the only
  way, it stays and says so.
- **Lyrics in the middle stay clear of the progress bar (#341).** A long sung line, placed in the middle of a page made
  short by a taller progress bar (Ribbons, the waveform), ran down to the bar; it now moves up into the room above as
  far as it needs to, and settles again when the controls come or go.
- **Even gaps under the player when parts are hidden (#329).** The hidden volume bar left a blank band between the
  controls and the buttons; its room is shared evenly between the gaps now, so nothing else moves.
- **The Undo bar stays off the Play button.** Changing the look with the player open brought up its Undo bar over the
  lower half of the Play button for a few seconds, on many phones. Over the player it now sits at the top.
- **Undo in the queue puts the song back where you can see it** (Reddit). Taken out and put back after the song before
  it had finished, or while shuffled once the next song had been lined up, it could land among the songs already played
  or at the end of the shuffled order. It goes back beside the songs it was with, on its side of the playing song.
- **The band behind the navigation buttons works on Android 15 and later (#250).** Android 15 ignores the color Prism
  gave it, so "Band behind the navigation buttons" did nothing there; the player draws the band itself now, only behind
  three-button navigation (none with gestures).
- **Song scans make way for your scrolling on slower phones (#343).** On phones with few cores or little memory, or with
  Performance mode on, Prism's song scans (loudness and tempo, for Normalize and Crossfade) wait while you touch or scroll
  the screen and for 2 seconds after. A scan that a Crossfade needs near the end of a song still goes straight on.
- **DJ mode's head start only runs when the phone is left alone (#343).** While charging on Wi-Fi it scans one song at a
  time, at the lowest priority, and stops as soon as the screen comes on or music plays. It used to scan three at once.
- **Opening the player is lighter with animated covers (#343).** The player carries on with the same video decoder
  instead of making a new one each time it opens: 10 opens used to make 10 decoders, now 1.
- **No glass work when glass is off (#343).** With Performance mode on or glass turned off, Prism no longer copies the
  page for glass that isn't drawn.
- **No see-through glass below Android 12.** Android 9 to 11 have no blur, so the tab bar and mini player are solid there,
  with no glass work behind them.
- **Screen readers settle on Android 8 and 9.** A moving background made Prism report a screen change about 4 times a
  second on every tab, with nothing changing. A still screen now reports nothing.

## 1.8.1

### Bring your history

- **Bring your Spotify history and library, no Spotify sign-in needed.** Request your data on Spotify's Privacy page
  (Account data, or Extended streaming history for every play since you joined) and pick the zip it comes in, or the
  files in it: Settings → Recommendations & Replay → Bring your history. Prism shows what's in it (plays, songs, the
  dates, Liked Songs, playlists, saved albums, followed artists) and you choose what to bring: your plays, Liked Songs
  (liked in Prism; signed in, a switch also likes them on your YouTube Music account, off unless you turn it on, and
  syncing your library leaves them liked either way), playlists (as Prism playlists), saved albums (into your library) and followed artists (starred).
- **Your YouTube Music history from Google Takeout** (watch-history.json) and **your Last.fm scrobbles** (through the
  Last.fm account you connected) come in the same way. Plays Prism itself sent to YouTube or Last.fm aren't counted twice.
- **Each song is found on YouTube Music** in the background, on Wi-Fi unless you allow mobile data, a few at a time
  with a quiet notification. A sure match counts at once; one Prism isn't sure of (another length, a live or remixed
  version) waits in **Needs a look**, where you play the top three and pick, or pass it over. Nothing found is tried
  again weekly. Every match is remembered, so nothing is looked up twice and your picks stay.
- **Replay and Rewind count Prism's own plays only, unless you say so**: "Count history from" has a switch per history
  you brought in. With them off, Replay is exactly as before; with one on, Replay and its story say "Includes Spotify".
- **Imported plays shape your mixes, radio and Quick picks** (turned on by your first import, a switch of its own), so
  a long history helps from the first day.
- Everything is read on your phone and kept there (it's in backups with your history); the only requests are the song
  searches matching needs and Last.fm's own. Remove a history any time: its plays leave Replay and your mixes, and
  what it added to your library stays.
- **Keep it current: bring a newer download any time, and only what's new comes in.** The page says so before you
  bring it ("312 new plays since your last import (Oct 3)", with the download's dates and how many plays are already
  here); the same or an older download says "Nothing new in this download". Plays already brought in are passed over,
  even between Spotify's two histories (the same song within about a minute is the same play, also when one names a
  featured artist the other leaves out, while two songs that only share a title stay two plays; and an Extended play
  takes the place of the Account data one), and only songs Prism hasn't seen are matched: your matches and picks stay.
  Newly liked songs are liked, newly saved albums and followed artists come in, and a playlist brought before gets its
  new songs added to the same Prism playlist (songs removed from it on Spotify stay in Prism's copy; none is made
  twice, even after removing a history and bringing it again). Last.fm asks only for scrobbles since the last import.
  Afterwards Prism says what came in ("Added 3 liked songs and 12 playlist songs."), also when no plays were new.
- **"Last Spotify import: Oct 3 (31 days ago)"** on the page, with **Request a fresh download** (Spotify's Privacy
  page, or Google Takeout), and a gentle reminder to bring a fresh one every 1, 3 or 6 months (3 unless you change it,
  or Off): a quiet notification, only once you've imported Spotify or YouTube Music history, never for anyone else.
- **YouTube Music (live): keep your YouTube Music history in step** (signed in, off unless you turn it on in Bring
  your history). Every few hours Prism reads your account's History page and counts the songs played since its last
  look, in the YouTube Music app, on the website, on a speaker, as their own source; Prism's own plays (which it reports
  to that history) and plays a Takeout already brought are left out. It starts when you turn it on (Takeout brings the
  past); times are approximate, a song played twice between two looks may count once, and Takeout stays the complete
  history (one brought later takes the live plays it has out). Signing out stops it; five failures running pause it for
  a day. **"Count history from" now lists every source**: the ones you haven't brought in are greyed, with Bring it in
  (or Turn on for the live ones).

### Spotify (optional, your own key)

- **Connect Spotify through its official Web API, with a developer app of your own.** Not a regular "Sign in with
  Spotify" but a workaround: a developer app can only be connected by five people until Spotify approves it for full
  access, which only large companies get, so Prism can't have one Spotify login for everyone. Spotify lets a developer app in
  Development Mode have only five users, and its owner needs Spotify Premium, so each listener makes one for themselves
  (free, about three minutes): Settings → Devices & connections → Spotify walks you through it (the dashboard link,
  the redirect address to copy), then you paste its Client ID and sign in on Spotify's own page. Prism never sees your
  password and has no secret of its own; the access stays on this phone, sealed by a key only it holds, never in
  backups. Off unless you connect, and every feature below has its own switch, off too.
- **Recent plays**: what you play in Spotify comes into your history every few hours as its own source, "Spotify
  (live)", with its own switch under Count history from and its own Remove. Plays your data download already brought
  aren't counted twice.
- **Top songs and artists**: Spotify's own measure of your last four weeks, six months and year shapes your mixes,
  radio and Quick picks (read on connecting, then weekly; Replay doesn't count it).
- **Your Spotify playlists in Library**, marked Spotify, playing through their YouTube Music matches (Spotify's ISRCs
  make the matches surer). **Keep in step** reads one again when you open it, and all daily on Wi-Fi.
- **Playlists you follow or saved show too** (Lofi Girl's, Spotify's own "Made for you" ones), greyed after yours with
  "Not shared by Spotify": Spotify keeps their songs from developer apps. Tap one for the way to bring it (in Spotify,
  ⋯ → Add to other playlist → New playlist, then Refresh now) or the simpler TuneMyMusic move (Library → Import
  playlists), Open in Spotify, or Hide from Library.
- **Liked Songs both ways** (off until you've seen its counts: "42 to like here, 17 to like on Spotify"): each like is
  carried over once, unliking never is, and likes you brought from a data download stay out of it.
- **Quiet when Spotify is**: a feature Spotify doesn't answer says so and the rest of Prism carries on; a "too many
  requests" is waited out, and five failures running pause that feature for a day. Only Spotify's own addresses are
  asked. Spotify no longer gives developer apps its mixes (Discover Weekly, Daily Mixes), editorial playlists, new
  releases or the songs of playlists you only follow, so those aren't offered.

### New

- **Your own MP3, AAC and Ogg files can play in place of the stream** (#314). Turn on Downloads, storage & data →
  "Use your own song files" (off by default; it doesn't need lossless playback): files on your phone that are surely
  the same song (same title, artist and length) play from the file and show in Downloads as "MP3 · 320 kbps, from your
  file", as on the player. A lossless file of the same song still comes first, and a file worse than the stream (under
  128 kbps, 96 for Opus, or under 44.1 kHz) is left alone. Your files are never changed; turn it off and those songs
  stream again at once.
- **The progress bar can take the crossfade's shape** (Customize Prism → Player → Progress bar, off unless turned on).
  With Crossfade on, the end of a song's bar tapers the way its volume falls in the blend, and the next song's bar
  grows in the way its volume rises: Smooth rounds off, Straight is a wedge, Gentle an S, DJ cut stays full and then
  drops. Its length is the real blend's (an automatic one shows once the blend is planned); the knob rides the shape.
  The waveform and Ribbons keep the crossfade mark; nothing changes where songs don't blend (an album's tracks,
  casting, episodes, live streams).
- **"Show background" goes all the way to dark** (#309): the player's Show background slider (Blurred cover and Your
  background) now runs from a fully dark player at 0% to the brightest background at 100%. Whatever you had set looks
  exactly as before (only its percent moved along the longer slider).
- **Album names in Downloads can go** (#310): Customize Prism → Home & Library → "Album names in Downloads", or Downloads'
  sort menu → Show album names. Off, each download shows just its artist under the title. On unless you turn it off.
- **Each artist's name on the player can open their page** (#289): Customize Prism → Player → Title and times →
  Artists' names → "Each name opens its artist". With more than one artist, a tap on a name goes straight to that
  artist; a tap between the names still asks which. Off unless you pick it.
- **The "Now playing" background can fade between songs** (#283): with a screen on the Now playing background,
  Backgrounds has "Now playing fades between songs", which fades the cover into the next song's at the pace your colors
  change between songs. Off unless you turn it on (and off with Reduce motion).
- **Add to playlist says which playlists already have it** (#246): a check and "Already has this song" (or "Already
  has 3 of these 12" for an album) beside each playlist that holds it. Prism goes by what's on the phone: your Prism
  playlists, every playlist you've opened (Prism keeps a copy for offline), and what you add from the sheet, so nothing
  extra is asked from YouTube; a playlist never opened in Prism has no mark until you open it.
- **New releases from your favorite artists only** (#311): the New releases page has a "Favorite artists only" chip
  (the artists you've starred; Home's New releases follows it too) and a row of your artists, the ones you play most
  first, to see one artist's releases at a time. Starred artists' new releases already come with a notification
  (Settings → Recommendations & Replay), and Customize Home can move New releases to the top of Home.
- **An explicit filter** (#216): Settings → Playback & sound → What plays next → Explicit songs. **Show them** is as before;
  **Skip them** keeps explicit songs (YouTube Music's "E") in lists but never plays them: radios, autoplay and mixes leave
  them out, a tapped one gives way to the next clean song, and the queue passes over any that got in; **Hide them** also
  leaves them out of search, Home and album, playlist, Liked songs and Downloads lists. It goes by YouTube Music's own
  mark, so songs kept on the phone without it (older likes, your own files) still play.
- **Change a song's or an album's cover** (#305, and Reddit): a song's menu (or the player's ⋯) → Change cover, and an
  album's menu or its page's ⋯. Pick a photo, one of the covers of the song's other versions (a lyric video's
  thumbnail swapped for the album's), or **Use the original** to go back. An album's cover shows on its songs too. It's
  used wherever the song shows: lists, the player and mini player, the notification and lock screen, widgets and
  Android Auto. Kept on your phone and in backups (with your library); nothing changes on YouTube Music.
- **A White player background** (#320): Customize Prism → Player → Look → Background → White, for the light theme.
  The player turns white with dark type and controls (the split light keeps its colors, a white play button turns
  black), and the status bar's icons go dark over it. With the lyrics or the queue open it turns to the dark blurred
  cover, as Color mesh does; a tablet's or a turned phone's wide player keeps the color mesh. Only when picked.
- **A font installed on your phone** (#318): Customize Prism → Text → "A font installed on your phone" (and one for the
  lyrics) lists the phone's own fonts, each written in itself, no file needed (Android 10 and later; fonts with Latin
  letters). Fonts a phone adds from its own font store (Samsung's) aren't listed to apps by Android: the System font
  is the font picked for the phone.
- **Pause your history** (#296): Settings → Recommendations & Replay → History → Pause history, for 1 hour, 1 day or
  until you turn it back on. Meanwhile nothing you play or search is kept: no plays for Replay, Rewind and History, no
  recent searches, nothing sent to your YouTube Music history and, like Spotify's private session, no Last.fm scrobbles;
  skipped suggestions aren't learned from either. What's already kept stays, and likes still count. Replay says when
  history is paused, with a button to turn it back on.
- **Choose when a song counts as played** (#263): "Counts as a play after" at once, 5, 10 (as always), 30 seconds or a
  minute, for Replay, Rewind and History. Signed in, "Same for your YouTube Music history" (off by default) makes your
  account's history wait for it too, so a song skipped at once is in neither.
- **Replay graphs a day by its hours, a week or a month by its days**, and up to half a year by weeks (#287, #286):
  until now only a stretch of more than a month had a graph. Each says its busiest hour, day, week or month.
- **Home's Replay card can show the last 14 days or the last 12 weeks** instead of this year's months (#286):
  Customize Prism → Home & Library → Replay card → "Bars on the card" (Months unless changed).
- **Replay and Rewind stories in your font** (#287, #269): Recommendations & Replay → Replay → Story type, "Your font"
  sets the stories in the font you picked for Prism instead of the poster type (Poster type unless changed).
- **Last.fm: see what's there before bringing it.** Tapping Last.fm now shows how many scrobbles there are and their
  dates ("4,210 scrobbles, Mar 3, 2019 to Nov 1, 2026", or the new ones since your last import), from two small
  requests, then Bring them.

### Fixes

- **Edge-to-edge cover and edge-to-edge video are two switches again.** 1.8.0 made them one, so the button on a playing
  video also turned the cover's edge-to-edge look on and off. Customize Prism → Player → Cover has a switch for each, and
  the video's button changes only the video.
- **Your own photo background stays in Performance mode** (Reddit: "only a black background now"). Performance mode
  drew every screen plain, your photo too; a still picture costs next to nothing, so it stays now (the drawn and moving
  backgrounds still pause). The Backgrounds page also says when Performance mode is why a pick doesn't show.
- **Your sign-ins stay out of Android's own backup.** Android's backup (to Google, or copied to a new phone) took your
  Discord and Last.fm connections and Prism Connect's pairings along; they're left out now. Your YouTube Music sign-in
  travels with your settings, so a copy restored onto a new phone signs out of it once: sign in again there. Prism's
  own backups are as before.
- **Add to playlist only says "Added" when the songs got there.** It took an unclear answer from YouTube Music as
  success and never checked a whole album's or queue's add; now it counts what went in ("12 songs added to …"), says
  "Already in …" or "Couldn't add to …" otherwise, and the playlist's page shows the new songs when you open it.
- **Add to playlist from a long press on an album's cover** works when one of the album's songs can't go in (the others
  still do), and uses the album kept on the phone when YouTube can't be reached.
- **"Couldn't add to library" on some albums** (from a long press, or on the album's page): an album is now saved the way
  its own page's library button does it.
- **New from your artists missed releases** from artists you play a lot when you follow many: your starred and most
  played artists are always looked at, and the others take turns.
- **Podcasts' "New episodes" opened empty** though it showed a count and a length: its episodes are listed now.
- **Songs played from Speed dial have their album** (to go to, and "From the album") (#312).
- **"Songs, not videos": a playlist's music videos downloaded as their songs show as downloaded** (#315).
- **Quick picks change once at most after Prism opens** (#297): they come in when they're ready and stay until you pull
  to refresh.
- **YouTube's sections on Home stay when YouTube's language changes** (#277): a section kept by its English title is
  found again under its Spanish one, where YouTube gives it a link to more.
- **Speed dial fills its last page** from what you play instead of leaving it out (#295); "YouTube Music's only" still
  shows whole pages of YouTube's own.
- **Speed dial keeps YouTube's songs** (#266): a song tile that starts another kind of radio was taken for a station,
  and of several sections headed with your name, the one with songs in it is now taken for Speed dial (it had only
  playlists and albums for some). A diagnostics log's Home line now says what's in each section.
- **Pull down on Liked songs** to bring in likes made elsewhere (#306).
- **The update banner shows sooner** (Prism looks for a new version every hour, not every six), and a second tap while
  an update is downloading or waiting for Android's installer doesn't start another (#278).
- **Swiping Prism away while it's paused** takes its notification with it (#274).
- **"Hey Google, play … on Prism" plays what you asked for, in the car too** (Reddit: Android Auto's voice button did
  nothing). Everything used to be looked up as one song: now an artist plays their top songs, an album the album, "my
  road trip playlist" your own playlist by name, "my liked songs" and "my downloads" yours, "shuffle …" shuffles, a song
  its radio, and anything else plays Search's top result. When nothing is found, what was playing keeps playing (it
  used to stop) and the car says Prism couldn't find it. The car's search button lists your own playlists and liked
  songs first, then Search's top results, songs, albums, artists and playlists. With the phone locked, the phone's
  voice request plays too.
- **Word-by-word lyrics line up with the singing more often** (#301). Their timings (Better Lyrics, LyricsPlus and the
  other word sources are Apple Music's) are made for Apple's copy of a song, and some of YouTube's start a little sooner
  or later. When the song's line-by-line lyrics (YouTube Music's own, or LRCLIB's) put every line the same amount
  earlier or later, the words are moved by it, once per song. Word timings that can't be the singing (every word the
  same length, or outside their own line) show as line-by-line lyrics instead, so a source with real ones comes first.
  The lyrics' timing sheet still moves a song's lyrics by hand.
- **Tapping a lyric line while the song is paused** moves the lyrics there too (the music moved, the lyrics stayed) (#308).
- **The play button's triangle sits in the middle of its circle** (it looked a little to the right) (#313).
- **The playing bars beside the current song stand still while it's paused** (#302).
- **Karaoke-style lyrics: no pale edge round the sung words**: the words still to come aren't drawn under them any more
  (#303). For a softer fill, pick your own color (a soft grey, say) for "The line being sung" under Lyrics → Text.
- **The color mesh behind the player visibly moves at Motion 100%** (a loop is three times as quick and goes farther),
  and the setting says when Performance mode or the phone's animations switch hold it still (#284).
- **Search no longer opens scrolled to the bottom** the first time after Prism starts (#279).
- **Player cover**: Full width meets the screen's sides in the Standard layout too (on a phone too short for a square
  that wide, its top and bottom are cropped a little), it no longer shrinks when paused, and a swipe down anywhere
  around the cover closes the player (in the Cover and Minimal layouts the room above the cover took nothing) (#276).
- **Tab bar**: the docked bar no longer shows bright lines at the screen's sides with Frosted or Liquid glass (#290),
  and with Labels "Selected" the current tab's name sits under its icon, as with "Always" (#291).
- **Smart Shuffle's "Add to …"** says "Already in …" when the song is in the playlist already, as Add to playlist does.
- **Start radio on the song that's playing** keeps it playing where it is and lines the radio up after it (it started
  the song again) (#292).
- **New option**: Customize Prism → Tabs & mini player → Mini player → "Play/pause button", to leave play / pause off
  the mini player (on by default; a tap still opens the player).

## 1.8.0

### Prism TV

- **Prism on Android TV and Google TV**, in the same app: install it on the TV and it opens with screens made for a
  remote. Home's rows, Search (with the remote's microphone), your Library, album, playlist and artist pages, and a
  full-screen player with big word-by-word lyrics, the queue, music videos and Sing (on TVs fast enough for it).
- **Your phone signs the TV in and becomes its remote.** Scan the TV's code with your phone (or type the code shown):
  the two pair over your Wi-Fi with an encrypted link, your phone offers to sign the TV in to YouTube Music (it asks
  first, and only sends to the TV you paired), and from then on the phone's device list has a remote for the TV: play,
  pause, skip, seek, volume, add songs. Your phone can send the TV your Look, too.
- **Playing on the TV from your phone stays in sync**, as casting does: pick the TV under Prism devices in Play on and
  the phone stays connected. Its player shows the TV's song, place and play / pause ("Playing on" the TV), and
  everything on the phone acts on the TV: play, pause, skip, seek, any song, album, playlist or radio, Play next, Add
  to queue, shuffle, repeat, volume. What's done on the TV (its remote, a song ending, a pick from its queue) shows on
  the phone. Pick This phone (or Stop casting) and the music comes back to the phone where the TV was, still playing;
  if the TV goes (switched off, Wi-Fi gone) the phone pauses there and says so. Moving music between two phones is as
  before.
- **Ambient screen**: after a few minutes without the remote while music plays, the TV dims to the song, its cover and
  its lyrics, drifting slowly so it doesn't burn in. Also there as a screensaver ("Prism") where the TV lets you pick one.
- **Lyrics over a music video**: the video plays on under them, smoothly (it goes straight to the TV's video layer, so
  the lyrics over it are drawn again only when they move), and the song's title, progress and Timing fade a couple of
  seconds after the last key while it plays; any key brings them back.
- **Getting Prism onto a TV**: type code **8986606** into the Downloader app on the TV for the newest Prism, or turn on
  Settings → Speakers, TVs & browser → **Share Prism with a TV** and type the address it shows: your phone hands the TV
  its own copy over Wi-Fi (only while that page is open). After that, updates come to the TV like on a phone.
- **Bring your Prism to the TV**: from your phone, send the TV your listening history, likes and Prism playlists
  (and your Look, if you like), so its Home, Replay and mixes know you from the first day.
- **Make the TV's Home yours**: every row the phone's Home has (Quick picks, Speed dial, Prism's mixes, your own pinned
  rows…) can be on the TV too; Settings → Home → Customize Home shows, hides and moves them with the remote.
- **Prism's look on a TV**: the frost of what's playing behind every screen, glass panels and the split light round
  what has focus, drawn so a mid-range TV stays smooth; Settings → Colors offers White light for plain monochrome
  highlights.
- **Lyrics on the TV like the phone's**: word by word, the sung word growing bolder, lines settling smoothly; a Timing
  control right on the lyrics, "Back to now" after looking around, and Settings → Lyrics → **Lyrics style** with the
  phone's six styles and a live example of each.
- **Sing on more TVs**: Prism times Sing on the TV once and picks the best way it keeps up with; on slower TVs it
  starts at once with a quick version while the full one gets ready, beginning where the song is playing.
- **The screen stays on** while a music video or Sing plays on the TV (the ambient screen is for music only).
- Devices paired for handoff before 1.8.0 pair once more (the link is encrypted now).

### For the holidays

- **Harvest (November) and Holiday (December) Looks**: Prism dressed for the season, with its own illustrations
  (leaves, acorns, pumpkins, pies, a turkey; snow, stars, trees, ornaments, lights) and colors (warm browns and golds; red and green), only in
  their month. The first time you open Prism in each, it asks once whether to turn on **Holiday Mode**; "No" means not
  again that year, and Looks → For the season has it any time.
- **Holiday mixes from Prism**: in November Thanksgiving Dinner, Friendsgiving, Football Sunday, Fall Jazz, Autumn
  Lo-fi and more; in December Christmas Classics, Christmas Jazz, R&B, Hip-Hop, Rock and Country Christmas, Navidad
  Latina, Kids' Christmas, Merry Lo-fi, New Year's Eve Party and more. A Home row appears by itself in season (phone,
  TV and the car), and the mixes are made on Wi-Fi ahead of time, so they open at once.

### Search and playing

- **Search finds the song a title is known for**: "upside down" leads with Jack Johnson, "yesterday" with the Beatles
  and "halo" with Beyoncé, by how much each is played, not with a soundtrack, tribute or cover of the same name.
- **Play on lists your Prism devices first**, so picking your TV plays in Prism there rather than in the TV's own Cast
  player (which is listed only while Prism isn't open on the TV).

### Settings, made clearer

- **Every Settings page was gone through**: settings that worked together are one choice now, and each lives in one
  place. What plays when the queue ends, music videos (play them / songs in radios / songs everywhere), Crossfade (Off ·
  Automatic · Fixed length), Trim silence, the same loudness for every song, Glass (Off · Frosted · Liquid), how screens
  change, the buttons at the top of Home, and the light-mode accent are each a single choice. Nothing you had set
  changes on the update, and Settings search still finds a setting by its old name. Two switches are gone: the cast
  mark now always shows while you cast, and the lyrics look is the same on the player's lyric line and in immersive
  mode as on the lyrics page.
- **The player, your way**: Customize Prism → Player → **What's on the player** is a drawing of your player: tap any
  part to hide it (cover, title, artist, like, buttons, progress bar, times, volume, device line…), tap it again to bring
  it back. Only play / pause always stays, and nothing hidden is gone: its action moves into the ⋯ menu.
- **The player's ⋯ menu** is in groups (This song, Lyrics, Playing, Help) with four quick tiles at the top, and
  **Edit this menu** lets you pin up to four tiles and fold the rest away under More. With the defaults it has exactly
  the actions it had.
- **Lyrics in four parts** (#253): Customize Prism → Lyrics starts from one of the six looks, then Animation, Text,
  Background and Effects are each chosen on their own, with your look shown live. Karaoke and Bold card animate more
  smoothly (they take well under half the work per frame they did).
- **Desk mode** (#265, off unless you turn it on; Customize Prism → Player): while the phone charges and music plays,
  the TV's ambient screen instead of the phone going to sleep.
- **Tablets: choose where the tabs sit** (#264): down the side as before, or along the bottom.
- **A smaller download** (#268): 1.7.9's 31 languages had made Prism 32.6 MB; they're stored compressed now, and the
  whole app, TV included, is about 26 MB.

### Sound (#210)

- **Same loudness evens out big jumps**: a quiet recording is lifted as far as its own peaks allow (it used to stop at
  6 dB, leaving some songs far quieter than the one before). Songs measured before 1.8.0 are measured once more.
- **Trim silence only touches a song's start and end** (renamed from Skip silence): pauses inside a song stay whole
  (they used to be shortened everywhere); a quiet intro of more than 10 seconds plays whole. Podcasts still have every
  pause shortened.
- **A crossfade that matches tempo never repeats a moment of the song** (it could replay half a beat while lining the
  beats up).

### Fixes

- Settings were saved one change at a time but not always in order: two quick taps could end on the wrong choice.
- Hiding the status bar no longer moves the whole player up; only the bar goes.
- With shuffle on, Undo after taking a song out of the queue puts it back where it was in the order.
- The Looks page offers each season's Look only in its own month.
- Back from Last.fm's sign-in opens Last.fm's page.

## 1.7.9.3

### Fixes

- **Songs only your account can play stopped after about a minute again** (#260): YouTube began answering the older
  TV app Prism asks for them "The page needs to be reloaded" for most songs, so they came from the web app, whose
  links stop after the first megabyte. Prism now asks the way yt-dlp does for signed-in users (the older TV app as a
  Samsung TV, then YouTube's embedded player), and asks the older TV app twice before giving up on it.
- **New listeners with large text couldn't get past the welcome screen**: with a large font or display size its list
  pushed the buttons off the bottom, and the page didn't scroll. It scrolls now, opens on its buttons when it doesn't
  fit, and the buttons grow with their words instead of cutting them off.
- **A cleared or edited queue could come back after a restart**: the queue was only saved when the song changed, on
  play / pause, shuffle or repeat, and the last save as Prism closed could be dropped. Every change to what's lined up is
  kept now, and the last save always lands.
- **The full-bleed cover jumped on every new song** (#244): it dropped to the square cover while the next picture
  loaded, then grew back. It stays full now, showing the last picture until the new one is in.
- **"Shrink when paused" did nothing in Performance mode** (Reddit): Performance mode turns Reduce motion on, which
  took the shrink with it; it only follows your own Reduce motion now. With full-bleed artwork (where the banner hides
  the shrinking cover) the switch says so.
- **Sonos speakers didn't show up to play on** (#239, Reddit): Sonos answers as a "ZonePlayer" with its renderer inside,
  which Prism didn't ask for. It does now, and names the speaker by its room.
- **Prism could take seconds to show Home on slower phones**: the first screen waited for the downloads to read their
  list and cache from storage (3.4 s on a slow phone in 1.7.8's check). They start in the background now, and screens
  show what's downloaded as soon as it's read.
- **Home's date and greeting stayed as they were when it opened**: left open overnight, it still said "Friday …
  Good evening" on Saturday morning. They're checked again when Prism comes back and each minute it's on screen.
- **Speed dial's last page could hold a single tile** (#267): its pages are whole now.
- **Tab icons were a little smaller with labels "Selected"** (#272): they're the same size in every label style.
- **The docked tab bar bent what was behind it** (#273): with Liquid Glass on, the flat docked bar worked like a lens,
  smearing the page across the tabs and showing its edges at the screen's sides. It's frosted glass now (the floating
  bars keep Liquid Glass).

## 1.7.9.2

### Requests

- **Lyrics saved into your music files can keep their word timings and the translation** (Reddit): Settings → Lyrics &
  Sing → Your music files has two new switches, both off. "Keep word timings" saves karaoke lyrics as enhanced LRC
  (Poweramp and other karaoke players light them word by word); "Include the translation" puts each line's
  translation under it, in your translation language.
- **Search's chips in your order** (#224): Search → Arrange Search (at the bottom) → Chips. Hold one to move it; Search opens on the
  first, so "On this phone" (or Songs, or any other) can be where it starts.
- **The mini player's progress, your way** (#231): Customize Prism → Tabs & mini player → Mini player has a progress
  bar color (your light as before, your accent, plain, or the cover's color) and, with Fill, how strong the fill is,
  up to nearly solid on frosted glass.
- **The playing song in the album's color** (Reddit): Customize Prism → Color → Artist & album pages → "Playing song
  in the page's color" (off by default). On album, playlist and artist pages, the playing song's bars and title take
  the color of the page's picture instead of your light.
- **Swipe a new release into the queue** (#248): on New from your artists' page, swipe a release right to play it
  next or left to add it to the end of the queue, as with songs.
- **Slide between screens** (#208): Customize Prism → Glass & backgrounds → Motion has a new switch (off by default,
  under "Fades between screens"). On, a new page slides in from the side and Back (and the back gesture) slides it
  away, mirrored in right-to-left languages.
- **A smaller player over the lyrics and the queue** (#230): Customize Prism → Player → Lyrics and the queue has a new
  choice, Small: the progress bar, a smaller play row and the buttons stay, so the list keeps most of the screen
  ("Full screen" already hides them, with a bar at the bottom to bring them back).
- **Gestures anywhere on the player** (#259): Settings → Controls & gestures → Gestures has a new switch (off by
  default). On, your swipes, double tap and long press work below the title too, in the space around the buttons and
  bars (which keep their own taps).
- **The ring around the player's play button can go** (#231): Customize Prism → Player → Look → "Ring on the play button".

### Fixes

- **Speed dial showed only your own plays in other languages** (#163): YouTube's own Speed dial ("Listen again") was
  found by its English title or an old layout YouTube has dropped, so in Spanish it was never found and "Speed dial:
  YouTube Music's only" did nothing. It's now the shelf headed with your name, in any language.
- **Quick picks went missing now and then** (#162): Home stopped after three pages, and YouTube puts Quick picks
  anywhere from the first to the fourth. Home now reads on until it has Quick picks and Speed dial (when they're on
  Home), and a shelf of your own history is never taken for Quick picks.
- **"Between songs" didn't fade the light "From the cover"** (#254): with your light taking the cover's colors, the
  progress bar, Play's ring and the picked cards changed to the next song's colors at once, so Fade and Slow fade
  looked like a cut. The light now fades with the accent (at once with Reduce motion or Performance mode).
- **Glass on Android 9 to 11 showed what was behind it sharp**: Liquid Glass needs Android 12's blur, so the tab bar
  and mini player looked like Home's tiles were drawn over them. Below Android 12 the glass is frosted now, with a
  wash thick enough to read through.
- **"Play next" and "Add to queue" on an album or playlist said so in English** in every language: translated now.
- **Search's chips were in English in every language** (Top results, Songs, Videos, Albums…): they're translated now.

## 1.7.9.1

### Fixes

- **Customize Prism → Icons & shapes closed Prism in every language but English** (#255): its examples asked for
  Prism's drawings by their translated names ("Inicio"). Two smaller slips of the same kind are fixed too: an artist's
  releases could show "Album" where the artist's name goes, and a shared single could say "Album", outside English.
- **Japanese lyrics romanized as Chinese** (#256): when Google's romanization was busy (it rests after many requests,
  translations included), Prism fell back to Android's own, which reads kanji as Chinese and leaves kana as they are
  ("xīn の zàng"), and kept that. A line Google doesn't answer now waits for the next try instead, one refusal no longer
  spoils the whole song, a word the Japanese dictionary doesn't know sends its line to Google, and songs spelled the old
  way are spelled again.
- **Repeat stays on after the song is swiped away** (#241): swiping the song off the mini player and closing Prism turned
  repeat (and shuffle) off.
- **A video in landscape can go back to the player** (#245): turned sideways, a music video fills the screen, and its
  exit (or Back) put it straight back in full screen, with no way out but turning the phone. Now it goes back to the
  player (the video in the cover's place, with every control) until the phone turns or the video is switched off.
- **The lyrics glide to the next line** (#258): on slower phones a line change looked like a jump (the move barely
  started, then rushed through in a few frames). It now starts at once and eases out over half a second.
- **The song playing is marked in Home's lists** (#242), Quick picks among them, as in a playlist; TalkBack says
  "Now Playing" on it.

### Options (Reddit and GitHub)

Each is off until you pick it, so nothing changes otherwise.

- **Keep an album's songs whole** (#247): Settings → Playback & sound → Crossfade. An album playing through never blended;
  this also keeps two of its songs whole when a playlist or shuffle puts them side by side.
- **No dark band behind the navigation buttons** (#250): Customize Prism → Player → Show on the player → untick "Band
  behind the navigation buttons" (three-button navigation), and the player shows through.
- **Albums or singles in Library → Albums** (Reddit): All, Albums, or Singles & EPs, when the library has both.
- **Any Home section as a 3 × 3 grid** (Reddit): Customize Home → a section → 3 × 3 shows it as YouTube Music's Speed
  dial does, pages of nine square covers, uncropped ("Listen again" among them).

- **The volume bar like the progress bar**: Customize Prism → Player → "Volume bar like the progress bar" gives the volume
  bar the progress bar's style and thickness (it kept its own height).
- **No grey handle on the player**: Customize Prism → Player → Show on the player → untick "Handle at the top". A swipe down
  still closes the player.
- **Hide the status bar only on the player**: Customize Prism → Tabs & mini player → "Hide it only on the player". The
  status bar goes while the player, its lyrics or the queue are open, and comes back on Home and the other tabs.

## 1.7.9

### Prism in 31 languages

All of Prism (every screen, setting, menu, notification, widget and the car) is now in Spanish, Portuguese (Brazil
and Portugal), French, German, Italian, Dutch, Swedish, Polish, Czech, Romanian, Hungarian, Greek, Russian, Ukrainian,
Turkish, Arabic, Persian, Hebrew, Hindi, Bengali, Urdu, Indonesian, Malay, Vietnamese, Thai, Filipino, Japanese,
Korean and Chinese (Simplified and Traditional), as well as English.

- **It follows your phone's language**, or pick one in Settings → Language (on Android 13 and up also in the phone's
  Settings → Apps → Prism → Language). Prism restarts in the language you pick.
- **Right to left:** in Arabic, Persian, Hebrew and Urdu the whole of Prism is laid out mirrored.
- **Dates, times, counts and genres** follow the language too ("3 nov.", "1 canción / 5 canciones", Arabic's six
  plural forms), and long words break with a hyphen instead of mid-word.
- The translations were made by Claude, an AI, so a word here and there may read oddly: corrections are very
  welcome on GitHub (CONTRIBUTING.md → Translations explains how).

### Recommendations that keep changing

Reddit: "the radio always plays the same music", "the Daily Mixes play the same songs". Mixes, radio and Quick picks
were rebuilt so they keep finding music around what you play, and stop going round in circles.

- **Daily Mixes change every day.** Each mix is now half songs you know and half new ones (it was mostly the same
  favourites). The songs you know are drawn from everything that fits the mix, not the same top 30 each time, and
  what was in the last few days' mixes, or what you've had on repeat this week, rests for a while. In a simulated week
  of listening, tested against the old way: what's left from the day before went from 100% to under 5%, and a week
  brings five times as many different songs.
- **New music from more places.** New songs come from a few of the day's songs, a different few each day, through
  YouTube Music's radio of them, their Related tab ("You might also like") and their similar artists' top songs. A song
  several of them agree on comes first. Nothing you've played in the last 90 days counts as new.
- **No artist takes over.** A mix takes its artists in turn, never plays one twice in a row, and keeps each to a few
  songs (a one-artist mix gives that artist more).
- **Skips count.** A suggestion (from a mix, radio, autoplay or Smart Shuffle) skipped within its first moments isn't
  suggested again for two months (one you know, after two skips); heard through, it's welcome again. An artist whose
  suggestions you keep skipping comes up less. Settings → Recommendations & Replay → "Forget skipped suggestions"
  starts over.
- **Radio sounds like radio.** With Autoplay from "Both", Prism Music's radio now leads, and only your own songs that
  belong with what's playing come in between (your favourites with nothing to do with it no longer do: rock or opera
  no longer brings NSYNC). Nothing heard in the last four hours is added again, and each batch spreads its artists.
- **Quick picks, the time-of-day mix and the mixes from your music** rotate the same way, with Quick picks' new songs
  coming from three of your main artists that change day to day.

### Playlists and versions (Reddit)

- **Play a different version.** A song playing as the wrong recording (someone's cover in place of the album track)
  can be swapped: a song's menu and the player's ⋯ → "Play a different version" lists the other recordings YouTube has
  of that title, with album and length (slowed and sped-up edits last). The one you pick plays for that song from then
  on, everywhere, downloads included; the song keeps its own lyrics, likes and history. "Original" goes back.
- **A slider on long playlists.** Scrolling a playlist of 60 songs or more shows a slider on the right edge: drag it to
  jump through the playlist, with the song's number beside your finger.
- **Genres can be turned off on playlists**: the sort menu's "Show genres", or Settings → Customize Prism → Home & Library →
  "Genres on playlists". Off, a playlist has no genre chips or genre labels and keeps its own order.
- **The lyrics' source beside Sing again, as an option**: Customize Prism → Lyrics → Look → "Lyrics source button". With the lyrics open, a glass button at their lower left, the size of Sing's, says where they come from
  ("LRCLIB · Karaoke") and opens the Lyrics source sheet. Off by default; the line at the lyrics' end stays either way.

### Small requests (GitHub)

- **Search in Local files** (#225): a search box above the sections; every word typed has to turn up in the title,
  the artists or the album, as on playlists. Play and Shuffle at the top play what it found.
- **"Room for sung songs" can be Unlimited** (#238): one step past 4 GB.
- **A gesture set to Nothing doesn't move anything** (#222): the mini player stays still when swiped toward a way with
  no gesture, and the player's cover too (it used to slide and spring back).
- **Fades between screens can be turned off** (#227, Customize Prism → Glass & backgrounds → Motion): pages then change
  at once, so a tap can't land on the page that's fading out. On by default, as before.

### Fixes

- **"Response code: 400" at the end of a song** (#206, Reddit): when the player asked for a song's stream from its very
  last byte, YouTube answered with an error instead of "nothing left", so the song buffered, showed the 400 and the
  next one was slow to start. Prism now answers that itself as the end of the song.
- **The widget's play button can take another color** (Reddit): hold a Prism widget → Settings (or Customize Prism →
  Icons & shapes → Home-screen widgets) → Play button: your light (as before), white, the cover's color or one of
  yours, for the play button and the progress line. Android 12 and later.
- **Settings → Lyrics' examples come to rest**: they play their line three times when the page opens or a setting
  changes, then hold part-way through. Looping for as long as the page was open, they kept a screen reader from ever
  settling there on Android 9 (and kept the phone drawing).
- **Back to a song's own version, it plays again**: with "Play a different version", both recordings were kept under
  the song in the song cache, and switching back while the other one was still loading mixed the two, so the song gave
  a playback error on every tap. Each version now keeps its own cached part, a download still coming down is never read
  mid-song (picking a version starts a song's unfinished download over too), and a cached part or download Prism can't
  read is cleared and the song streamed afresh at once (which also mends a song this already happened to).
- **The accent from the cover is the cover's color** (Reddit): a few pixels of another color no longer decide it (a deep
  blue night with two pink lights gave a pink accent). A color now has to fill a real part of the picture. A
  black-and-white cover gives a grey accent, as it looks (its slight tint used to turn into a color); "Always colorful"
  still gives one a color.
- **Removing a playlist's downloads asks first** (#212): the download button on a playlist or album whose songs are
  all downloaded removes them, so it now says how many and waits for Delete. TalkBack names it "Remove downloads".
- **Update from Settings** (#201): Settings → Backup & about → Check for updates shows the new version right there,
  ready to install, so updating works with the Home tab turned off.
- **Scrolling up the lyrics can leave the controls away** (#214): Settings → Player → Lyrics and the queue →
  "Scrolling up shows the controls" (on, as before). Off, going back to an earlier chorus keeps the whole screen; a tap
  or the bar at the bottom brings them. With "Full screen" a scroll never brings them.
- **Widgets show the new song at once** (#217): they waited for its cover to load, and every update meanwhile started
  that over, so the old song stayed up for a while. The title changes straight away; the cover follows.
- **Backups move between Prism copies** (#204): a backup restores into any Prism, whatever its app id (the test copy,
  or a copy renamed so a phone's media island shows it). It used to say it was "from a different app".
- **A music video no longer jumps ahead** (#203): switching to the video before Prism knew how far it's shifted from
  the song, the video was moved to the matching moment once that was worked out, even half a minute later, which
  sounded like a skip. Now that only happens right after switching; later the video plays on and the lyrics follow it.
- **Music videos show their album cover** (Reddit): a music video playing as a song showed a still from the video as
  its cover. It now takes its song's album cover (the same title by the same artist, even when the video is an edit of
  a different length), on the player, the notification, widgets and in the car, without the music pausing.
- **Songs loaded ahead play offline** (#213): with Load ahead on, the next songs were saved whole but not marked as
  whole, so going offline Prism still asked the network for them and they didn't play. They're marked now.
- **"Songs, not videos → Everywhere" finds more songs** (#202): an artist's video may now run a minute and a half past
  its song (A$AP Rocky's "Punk Rocky" runs 62 s over), and a label channel's 'Artist - "Song" Label' upload finds the
  artist's song. A video of two songs still plays as it is.
- **Sliders show their number** (#209): the ones that only said a word ("Balanced", "Airy") now say both
  ("Airy · 140%"), and Backgrounds' Softness shows one too, so you can come back to exactly the same place.
- **Long tab names fit the floating bar** (#237): "Liked songs" on a floating bar with labels got cut off; a name now
  gets a little smaller to fit its tab, and keeps its full height with big text.
- **"From the cover" is the cover's colors** (#233 #235 #236): a black-and-white or mostly white cover gave Prism's
  rainbow light; it now gives a silver one, and a cover with one color gives shades of it. A speck of color on a small
  part of the picture no longer counts.
- **YouTube's words in Prism's language** (#234): song credits' headings and other text YouTube writes came in the
  phone's language while Prism was in English. They now follow the language Prism is in (Settings → Language).
- **Opening a mix from Home the first time** (#228) is in Prism's startup profile now, so it's ready instead of being
  worked out on the spot; "Fades between screens" off makes opening and closing pages lighter still.

## 1.7.8

### Sound, your way

Settings → Playback & sound. Every default sounds as 1.7.7 did.

- **Speed & pitch** (Reddit): the Speed button under the player (and the player's ⋯) opens a sheet with speed from 0.5×
  to 2× and pitch up to 12 semitones up or down, each on its own, plus quick picks: Slowed, Sped up, A little faster.
  "Start like this every time" keeps them for when Prism opens (also in Settings). Podcasts keep their own speed; live
  streams and casting play as they are. Sing's key change comes on top.
- **How songs cross in a crossfade**: Smooth (equal power, as before), Straight, Gentle (an S-curve) or DJ cut (both at
  full, then a quick swap halfway), each drawn on its card.
- **Crossfade mark and blending covers** (Reddit): a mark on the progress bar where the next song will come in, and the
  player's cover dissolving into the next one's over the crossfade instead of switching at its start.
- **Normalize volume's level**: Loud (-8 LUFS), Louder (-11), Standard (-14, as before), Quieter (-16, like Apple Music)
  or Broadcast (-23). **Albums as they are**: an album playing through gets one volume change for all its songs, so a
  quiet interlude stays quiet next to the single.
- **Your own equalizer presets**: save the equalizer under a name, send one to a friend as a code and paste theirs in,
  and pick a preset for whatever plays the music now (the car, your earbuds): Prism switches to it whenever that output
  plays.
- **Fades**: the sleep timer can fade the music out over up to a minute, and pausing and playing can fade instead of cut.
- **Skip silence for podcasts on its own**: songs and episodes each have their switch (episodes start where the one
  switch was).

### Sing, simpler

- **One button** (after Apple Music): with the lyrics open, a small glass button with the microphone at their lower right.
  Tap it for Sing on or off. Hold it (or slide straight away) and it grows into a tall slider for how much of the voice
  stays in, with a tick at none, half and full. A ring of light runs round it while the song is being got ready, and a
  word under the progress bar says what happened ("Sing", "Sing off", "Voice 62%").
- The glass bar under the lyrics is gone: where they come from is a line at their end (tap it to pick another source),
  and the key is in the player's ⋯ → Sing, with the voice level and the switch.

### Classic Prism

For everyone who liked 1.6's look (#155). Nothing is taken away; each part is a choice of its own.

- **The Classic Look** (Customize Prism → Looks): Prism 1.6's icons, bold type, plain controls and color, no glass,
  docked tab bar, filled play button, on today's screens with everything 1.7 added.
- **Classic icons** (Icons & shapes → Pack): 1.6's hand-drawn icons with their heavier line; Weight and Rounded work on
  them as they did then. Icons that came after 1.6 keep today's drawing at Classic's line.
- **Classic type** (Text → Type): 1.6's bold, tight headings instead of today's light titles.
- **Classic controls** (Icons & shapes → Buttons): Android's own switches, sliders and buttons in your accent.
- **Player buttons apart** (Player → Buttons): the row under the controls as separate buttons, as in 1.6, instead of
  one glass capsule.

### Your requests

Every one of these is a choice; nothing changes until you pick it.

- **Lyrics and the queue, your way** (#193, #197): Customize Prism → Player → Lyrics and the queue: the player's controls
  hide after a moment (as before), always stay, or start hidden so the lyrics and the queue get the whole screen. The
  queue's headings ("Played", "Now Playing", "Up next") can go, for one continuous list.
- **A queue made for editing** (Reddit, #159): hold a song to pick several, then move them up next, to the end, add them
  to a playlist or take them out together. Each song has a ⋯ with the same (and "Clear everything after this"). The
  lock at the top keeps the order still while you scroll (no drag handles, no swiping away), and the player's controls
  step aside while you drag a song.
- **No duplicates in your YouTube playlists** (#159): adding a song that's already there says so instead of adding it
  twice.
- **Swipe up for the queue** (#200, #197): Settings → Controls & gestures: swipe up on the player's controls for the queue
  (from their right half) or the lyrics (left half); swipe down there to close them. Made for one hand.
- **Hide the controls over videos** (#166): while a music video plays, the controls go after a few seconds; a tap on the
  video brings them back.
- **Video in immersive mode** (#170): a playing music video shows behind the lyrics instead of the cover.
- **Full-bleed artwork, whole** (#175): Fill the screen (as before) or Whole cover, the square cover uncropped on a
  blurred copy of itself.
- **The cover flies into place** (#199): opening the player, the mini player's cover grows into the player's while it
  fades in, instead of the whole player sliding up (Player → Show on the player).
- **Picture-in-picture only with its button** (#167), **Lock to portrait** (#190), **Pause when muted** (#157: the phone's
  media volume all the way down pauses, up again plays on).
- **Replay for any dates** (#149): Today, This week and This month beside the usual ones, and "Other dates…" for any
  month you listened in or any days on a calendar, each with its own story.
- **Choose what goes in a backup** (#149, Reddit): Settings & Looks, Library, Listening history & Replay, Accounts and
  Downloaded songs, each one ticked or not, so you can share your settings without your listening or your sign-in.
  Restoring replaces only what the file holds and says what that is first. (Backups from earlier versions restore as
  before; Prism before 1.7.8 asks to be updated before restoring a new one.)
- **Last.fm scrobbling** (#133, #160): Settings → Devices & connections → Last.fm, beside Discord (the Car & devices page, renamed). Connect your account on
  Last.fm's own page (Prism never sees your password); songs count once you've heard half of them or four minutes,
  "Now playing" shows as each starts, and scrobbles made offline are sent later. Options: send only a song's first
  artist (#160), and love songs on Last.fm when you like them here.
- **ALAC (Apple Lossless)** (#135): with the lossless add-on, your ALAC files (.m4a) play in place of songs as FLAC
  files do. A phone without an ALAC decoder says so on a file of your own rather than skipping it, and plays the
  song's stream instead of a matched ALAC file.
- **Lyrics into M4A files** (Reddit): "Save lyrics into your files" now also writes AAC and ALAC files (.m4a), beside
  FLAC and MP3, keeping everything else in them; the audio is checked to be exactly the same before the file is
  changed.
- **Recently played as albums and playlists** (#196): Customize Prism → Home & Library → Recently played shows.
- **Library → Songs** has search, Shuffle and Play, as the Liked songs page does (#184).
- **Downloads take a music video's song** (#186), with "Songs, not videos" on: the audio, not the video's sound (the
  video's sound when it has no song).
- **Hide the cast mark** after your phone's name under the player (#149).
- **Downloads notification** (#149): says how many songs are coming down, which one now and how far, and has a Cancel
  button (finished downloads stay).
- **Download again in Premium quality** (#149, #148): with Premium quality on, Downloads' ⋯ offers to fetch again the
  songs that came down below 256 kbps.

### Fixes

- **A song that stopped playing with "Element 134 must be in a TrackEntry"** (Reddit): part of it was saved from an
  earlier play in another format (another quality, Premium, or another of YouTube's answers), and today's stream was
  joined onto it. Prism now remembers each saved song's format and drops what was saved when it changes; a song that
  still can't be read has its saved part cleared and plays afresh.
- **Closing Prism from its notification** (Reddit): swiping the paused song's notification away (or Hide on the media
  card) now closes Prism properly. The notification doesn't come back until something plays, and Prism no longer stays
  in Android's list of active apps.
- **A "Prism" Quick Settings tile** (Reddit): play or pause from the Quick Settings panel, with the song's name under
  it; with nothing lined up it opens Prism, and holding it opens Prism too.
- **Premium quality on phones where it still failed** (#179): on some phones the small sandbox Prism unlocks YouTube's
  links in ran out of memory reading YouTube's newest player, so every Premium song (and its download) failed and fell
  back to its music video's sound. It now gets the memory it needs, and if a Premium link still can't be unlocked, the
  song plays at once in normal quality instead.
- **Other apps can pause and skip again** (#178, #187): KDE Connect, "dynamic island" apps and launcher widgets
  showed the song but their play, pause and skip did nothing. The media library Prism uses (Media3 1.11) only lets apps
  it doesn't recognize read what's playing; Prism now lets every app that can reach its media session control it, as
  music players do. Playback diagnostics also list each command another app sends and where it went.
- **Lyric translations stopping after a while** (#198): Google stops answering a network that asks for many songs in a
  short time. Translations are now kept on the phone (a song is translated once per language); when Google's main
  service refuses, Prism asks its second one; and when both are resting, the lyrics say "Translation unavailable right
  now" and Prism tries again later instead of showing nothing.
- **Wrong covers on local songs without an album tag** (#143): Android names such a song's album after its folder
  ("Quick Share", "Download"), so Prism took the folder for an album and gave songs with no cover of their own another
  song's. A song whose album is only its folder's name is now checked against the file once: with no album tag, it's
  shown without an album and only ever with its own cover.
- **The player's ⋯ menu jumping when flung up** (#147): a fast fling past the end of the menu pushed the whole sheet up
  under the status bar and it bounced back. The menu now keeps that fling to itself.
- **Centered lyrics** (#158, #164): the instrumental "…" on the lyrics page follows the lyrics' alignment (it stayed on
  the left), and the lyric line on the player is centered on its words alone (the arrow after it pushed it left).
  New: Customize Prism → Lyrics → Instrumental breaks can show a music note instead of the dots.
- **Local files' title at the top again** (#156): as a tab, the page's title sits beside Shuffle and Play, as in 1.6.9.
- **FLAC files missing from Local files** (Reddit): files Android had no length for, or hadn't marked as music, are
  listed now (their length is read from the file once).
- **Switching to a Look in another font no longer freezes the screen for a second**: its font is made beforehand, off
  the screen's thread.
- **App icon picker** (#194): the icons fill the page in an even grid (with larger text there was an empty column at
  the side) and names like "Spectrum" are no longer cut short.

## 1.7.7.1

Fixes only.

- **Songs that stopped about a minute in** (#188, #192, #153): songs Prism asks for as your account (age-restricted,
  some explicit songs, your uploads) came through YouTube Music's web player, whose links YouTube cuts off after the
  first megabyte. YouTube's TV app, which Prism asks first and which has no such limit, had stopped giving links;
  Prism now asks as its older TV app, which still does (Premium's 256 kbps included). If a link is refused anyway,
  the song is asked for the other ways first, and a song can no longer get stuck asking as the account. The
  playback diagnostics now say why each of the account's ways gave nothing.
- **Premium quality failing to play** (#179): YouTube's newest player code asks for number formatting that Prism's
  stream solver had no data for, so no Premium (or other account-only) link could be read. It works without it now.
- **Day and night Looks** (#174): when the schedule switches Looks while you'd changed settings yourself, Prism now says
  where your settings went ("Before Midnight" in Your Looks) and can bring them back with one tap.
- **Every artist a favorite** (#182): bringing your YouTube Music library over starred the artist of every song in your
  library, not only the artists you follow. It stars only those now, and the extra stars come off once (stars you
  gave yourself stay).
- **Library → Albums matches YouTube Music** (#191): signed in, it lists your saved albums, not also the albums of every
  liked and downloaded song. Downloaded albums are still on the Downloads page.
- **Downloads that wouldn't play** (#183, #185): a download that stopped part-way and resumed in another format made a
  file nothing could read. It now starts over when that happens, and a broken download is fetched again (streaming
  meanwhile). Downloads YouTube refuses no longer try again by themselves all day long: every 6 hours, or when you
  tap Try again.
- **Switching to the video jumped ahead** (#180): a video's offset that doesn't fit the video's and song's lengths (a
  match on a repeated chorus) isn't used any more.
- **A shared Look, link or Blend showed again** every time Prism restarted.
- **New releases** (#171): when YouTube won't open a Home shelf's own page, its songs and albums from Home show instead
  of an error.
- **The screen stays on during a music video** (#169).

## 1.7.7

### Shapes, icons and type
- **Covers' shapes**: Customize Prism → Icons & shapes → Covers' shapes. Songs, albums and playlists, artists and the
  player's cover each in their own shape: rounded, square, circle, squircle, flower, cookie, arch or slanted, each shown
  as a card. Drawn by Prism itself, no extra library.
- **Corners as a slider**, with the old Sharp, Subtle, Round and Extra as its marks (Looks that name a step still work).
- **Buttons**: pill, rounded or square, and the Play on album and playlist pages filled (with its glow) or outlined.
- **Icons**: outline, duotone (a light tone inside) or filled, and the tab you're on in ink, your accent or the light.
- **Two more icon packs**: Facet (straight lines and sharp corners, every curve cut into facets) and Dots (drawn in
  dots, like a dot-matrix display). Every one of Prism's icons comes in both, and Looks carry the pack and fill style.
- **Six more app icons**: Dusk, Paper, Neon, Pastel, Line and Gold. Android Auto shows the one you pick.
- **Text**: Light, Regular or Bold for all of Prism's text, tight, normal or wide letter spacing, and titles in a font of
  their own (big titles in Editorial and the rest in Prism, for example). Each choice is a card with its example.
- **Two fonts for reading**: Legible (Atkinson Hyperlegible, made to tell letters apart at a glance) and
  Dyslexia-friendly (OpenDyslexic).

### Widgets
- **Each widget its own way**: when you add a Prism widget (or hold it → Settings on Android 12+, or from Customize
  Prism → Icons & shapes → Home-screen widgets): its background as designed, in a color of yours, in the cover's color
  or see-through, how solid, white or black text, and whether it shows the cover, like, previous and next, and the
  progress. The preview is the widget itself. Two widgets of the same kind can look different.
- **Widgets follow your light** (Android 12+): with a light other than Prism's own (a preset, your own or From the
  cover), Prism's widgets take its colors for the progress and the play ring.

### You asked for it
- **The light from the cover** (#123): Customize Prism → Color → Spectrum → "From the cover". The progress bar, Play ring,
  the playing song in lists, switches and the rest of the light take the playing cover's colors, new with each song
  (or each album, with "New albums only"). A grey cover keeps Prism's own light. Made from the palette Prism already
  reads from each cover, so it costs nothing extra.
- **Rearrange your playlists' songs** (#140): open one of your playlists → ⋯ → Rearrange songs, then hold a song (or
  drag its grip) and move it. Each move goes straight to YouTube Music, so the order is the same everywhere, and is put
  back if YouTube refuses. Prism playlists rearrange the same way.
- **Newest first** (#141): the sort menu of your own playlists has "Newest first" (YouTube adds new songs at the end),
  Liked songs has "Oldest first", and anyone else's playlist can be reversed.
- **Swipe a song out of the queue** (#149): swipe it to the left; an Undo shows for a few seconds. The × still works,
  and now has the Undo too.
- **Swipe the mini player up** to open the player (#149), or pick what it does in Settings → Controls & gestures.
- **Pull the queue down to close it** (#149): by its title, or by the song at the top.
- **Move songs in a shuffled queue**: with shuffle on, drag a song by its handle to change what plays next, as with
  shuffle off. It moves in the shuffled order (what the queue shows), so Previous still goes back through what you heard.
- **History as a Library tile** (#136): Edit tiles → History. (Replay's tile swaps its clock for its chart then.)
- **Clear the Looks history** (#152): Customize Prism → Looks → History → Clear history. Your look stays as it is.
- **Ribbons**, a new progress bar (#151, after One UI's): the bar and a knob, with thin strands flowing along the played
  part while a song plays. Customize Prism → Player → Progress bar, where every style is now a card showing how it
  looks. The strands hold still with Reduce motion or Performance mode.
- **Title weight on the player** (Reddit): Customize Prism → Player → Title and times → Title weight: Light (Prism's
  own), Regular or Bold. Bold makes the artist's name heavier too.
- **Add a whole album or playlist to a playlist** (Reddit): hold it (or its ⋯) → Add to playlist.
- **Share as YouTube links** (Reddit): Settings → Car & devices → Sharing and links → Links you share → YouTube. Songs go
  out as youtu.be links, playlists and artists as youtube.com ones (albums have no page on YouTube, so they stay
  YouTube Music's).
- **Open YouTube Music links in Prism** (Reddit): the same group has a button to Android's "Open by default" page for
  Prism, and says whether music.youtube.com links open here.

### Fixes
- **Album songs play as songs again** (Reddit): signed out, YouTube now lists an album's tracks as their music videos
  where they have one, so a song could play its video's sound: a longer intro, or another song mixed in ("Death &
  Romance" ran 8 minutes with "Fear, Sex" in it), and the lyrics out of time. Prism now reads the album's own playlist,
  which still lists the songs, and plays those (one request per album). The video stays the song's Video button. Also,
  on long album pages "Play" could start another album's songs.
- **A song that won't play no longer becomes a different recording** (Reddit): when a song isn't available where you
  are, Prism plays its music video's sound only when the video is the song's length; it now also does that when YouTube
  keeps refusing the song's stream (it skipped instead), never for age-restricted or Premium songs.
- **All your YouTube Music playlists in the Library** (Reddit): only the first 25 or so showed; Android Auto's Library
  too.
- **Crossfade with the equalizer on** (Reddit): the song fading out now plays on its own audio session with its own copy
  of the equalizer, instead of sharing the main player's, where some phones' effects broke the blend.
- **Widgets and other apps can skip again** (Reddit): since 1.6.1 Prism's media session didn't offer the standard
  previous/next, so "Dynamic Island" apps and other widgets couldn't skip. They're back; only with a Pixel Watch or
  Wear OS companion app on the phone does Prism keep the watch's own layout. Prism's widgets also hold on until a tap
  has reached the player when Prism wasn't running.
- **Sing's key holds through a crossfade** (Reddit): a beat-matched blend set the music back to its original key while
  Sing still showed yours, and the song fading out played in the original key.
- **Premium quality stays on** (Reddit): one song without Premium audio (a music video or an upload, which never have
  it) turned Premium quality off for the next hour, so the songs after it played at normal quality. It now only pauses
  after three songs in a row come without it.
- **Lyric translations don't vanish** (Reddit): when Google's translation came back with lines merged or split, the
  whole translation was dropped and nothing showed. Those lyrics are now translated a line at a time instead.
- **Songs, not videos works again** (#138): YouTube stopped telling apps which song a music video belongs to (October
  2026) unless you're signed in, so a Discovery Mix and other mixes came through as videos, and some videos didn't find
  their song. Each video coming up next is now matched to its song by Prism itself, an artist's own video may run up to
  a minute longer than its song (an intro, a closing scene), and signed out Prism no longer asks YouTube for a pairing it
  won't give (one request less for each).
- **Shared song links show the song straight away**: YouTube now sends a song's queue separately, so "Play in Prism"
  showed "Loading…" until the song started.
- **The artist page's name follows your font** (#139): the plain Standard style was drawn in Google Sans on a Pixel and
  Android's own font elsewhere, whatever font you picked. The other signature styles keep their own typefaces.

## 1.7.6

Home, Library and Search your way, and a lighter, faster Prism. The fifth customization update, and a performance one:
Performance mode for older phones, about 200 MB less memory at start, and quicker lists and Search.

### You asked for it
- **Home opens straight away** (#126): the last Home is kept on the phone and shows at once when Prism starts, while the
  new one loads behind it, instead of a loading spinner. It goes when you sign out or switch accounts.
- **Full-bleed video** (#120, Settings → Customize Prism → Player → Cover, or the new button on the video): a music video fills the top
  of the player edge to edge and fades into the background, as on YouTube Music.
- **The lyric line on the player, centered and on two lines** (#121, Settings → Customize Prism → Lyrics → More options → Where the lines go): the line
  over the progress bar can sit in the middle, and a long line can wrap onto a second line instead of being cut off.
- **Featured and Community playlists in Search** (#130): the Playlists filter is now two, YouTube Music's own playlists
  and people's.
- **Quick picks and Speed dial in any language** (#131): YouTube's Quick picks under another language's title stop at
  each column like Prism's, YouTube's own Speed dial is found in any language, and Customize Home → "Speed dial: YouTube
  Music's only" leaves out the tiles Prism adds from your plays.

### Performance mode
For phones that found 1.7 heavier than 1.6 (#103, #132, #134): **Settings → Performance mode**, one switch that draws
Prism about as lightly as 1.6 did. It pauses blur and frosted glass, the frost behind pages, moving and illustrated
backgrounds (pages are plain), animated covers, the visualizer, a drifting player background, focus blur and glow in the
lyrics, the picture at the top of Home, and animations. Every feature keeps working, nothing you picked is changed, and
it all comes back as it was when you turn it off. Phones on Android 9 or older, or with 4 GB of memory or less, are
offered it once on Home. On the Android 9 emulator, scrolling Home with music playing took half the processor time and
frames drew 4–5× faster with it on.

### Faster everywhere
- Pages read YouTube's answers into shelves and lists off the screen's thread (Home, albums, playlists, artists,
  podcasts, moods, and every search): on a slow phone a long playlist held the screen up for a few hundred milliseconds.
- Covers kept on the phone are looked up from memory, without working out each picture's file name again on every
  frame of a scrolling list.
- The player's volume and output are asked of Android in the background, and the moods ranking is worked out there too.
- **Library and Settings open faster after an update**: the list of Prism's code that Android compiles ahead at install
  now covers them too (since 1.7.4 it only had Home, Search and the player).
- Playback diagnostics now note Prism's memory every half minute, by kind, to find what grows on phones that close Prism.
- **Animated covers use far less memory**: their player kept up to 50 seconds of video loaded ahead (looping the same
  few seconds over and over), about 50 MB on a budget phone; it now keeps 10 seconds at most (8 MB). And a cover is no
  bigger than the screen: a 720 px phone plays the 720 px clip, not the 1080 px one. Prism's memory on a budget phone
  with an animated cover playing went from 77 to 53 MB.

### Settings, easier to find
Settings are sorted by what they do, and every page is shorter. Nothing was removed; Settings search finds everything
at its new place.
- **Customize Prism** holds everything about how Prism looks, in one place: Looks, Color, Text, Icons & shapes, Glass &
  backgrounds, then each part of Prism (Tabs & mini player, Home & Library, the Player, the Lyrics). The live preview
  is at its top once, instead of pinned over every look page.
- **Playback & sound**, **Lyrics & Sing** (sources, language, Sing), **Controls & gestures** (the player's swipes and
  taps, holding previous / next, haptics, the notification's and car's buttons, Search's keyboard),
  **Recommendations & Replay**, **Downloads, storage & data** and **Car & devices** (Android Auto, speakers and TVs,
  Discord), then **Backup & about**.
- **Every "on mobile data" choice is on one page** (Downloads, storage & data → Mobile data); they were on six.
- Less-used groups (the player's title and visualizer, where lyric lines sit…) are folded under **More options**.
- Color's preview, still pinned while you scroll, no longer lets the page show through behind it.

### Search, redesigned (#124)
Prism's own design, not YouTube's page:
- **Search by lyrics, in the search box itself**: type a line from a song ("i was too busy tryna find you with someone else") and
  the song it's from shows under **From the lyrics**, with the line as the song has it, first when the line is nearly
  word for word. Lyrics already on your phone are looked through first (offline too), then Genius's lyrics. They show
  only when Search didn't find the song by its name, and only for the words together as typed.
- **Before you type**: what you opened lately by its picture, your recent searches, your artists as a row of photos,
  and the moods and genres as Prism-drawn tiles (their color, a pattern, the split light). **Arrange this page**
  shows, hides and orders the sections.
- **While typing, only suggestions**: "Search for …", the completions, and the artists, songs and albums YouTube Music
  suggests with their pictures (a tap opens or plays one at once). The chips and results wait until you search, or put
  the keyboard away, so they no longer pile up under the suggestions.
- **One row of chips**: "On this phone" is now the last chip, beside Songs, Videos, Albums and the rest, instead of a
  row of its own above them.
- **In your library** and **On this phone**: results that are already yours (liked songs, your playlists, albums and
  artists) and matching music files and downloads come right after the top result.

### Home your way
- **Pin to Home**: hold any album, playlist, artist or mix and pick "Pin to Home": it becomes a section of its own at
  the top of Home, its songs ready to play, its name opening its page.
- **Sections from your own listening** (Customize Home → Add a section → Your own): Liked this month, Downloads you
  haven't played, and Most played in each year you've listened.
- **Rename any section** from its ⋮ in Customize Home.
- **Leave off music video or podcast sections**: YouTube Music's own sections of those kinds stay off Home.
- **The top of Home** (Customize Home → Top of Home): the greeting as now, just the buttons, the greeting over a
  picture (one of the background designs or your own photo), or over the playing cover, frosted; Short, Medium or Tall.

### Covers you make
- **Make a cover** (a playlist's picture → Make a cover): its name in large type over two colors of your choosing, with a
  pattern from the backgrounds and the split light around it if you like, seen as you make it.
- **Your own photo of an artist** (an artist page's ⋯ → Use your own photo): on their page and in Library.
- Both stay on this phone (and in backups); YouTube Music keeps its own pictures.

### Library your way
- **Playlist folders**: hold a playlist → "Move to a folder" (or make a new one there). Folders sit at the top of
  Library → Playlists; a folder's page renames it or takes it apart. Kept on the phone, and in backups.
- **Pin to the top**: hold a playlist, album or artist in Library to keep it first in its tab.
- **Each tab its own view**: Playlists, Albums and Artists can each be a list or a grid (the button at the top of
  Library switches the tab you're on).
- **Covers across** (Auto, 2, 3 or 4) for the grid, and **Details under each one** to hide song counts, owners and years.

### Tabs your way (Settings → Customize Prism → Tabs & mini player)
- **More tabs**: Downloads, Liked songs and Podcasts can be tabs too, up to six in all.
- **Rename any tab**: tap its name in Tabs.
- **Words only**: the tabs' names without icons (they shrink to fit five or six).
- **Height** (Compact, Standard, Tall) and **Background** (as the glass, see-through or solid) for the tab bar.
- **Swipe between tabs** (off by default): a sideways swipe on a tab's page goes to the tab beside it; rows that
  scroll sideways keep their swipes.
- **Mini player joined to the tab bar**: one floating piece of glass, the mini player on top of the tabs.

### Playlists without an account
- **Keep a YouTube playlist in your library without signing in**: its page's "Add to library" (or a long press) keeps
  it on the phone, in Library → Playlists, opened fresh from YouTube each time. Mixes, which change every time, aren't kept.
- **Save a copy as a Prism playlist** (any YouTube playlist's ⋯ or long press, signed in or not): every song of it, in a
  playlist of your own on the phone that stays as it is.
- **The queue's Save works without an account too**, as a Prism playlist.
- **Library → Playlists → Open a link**: paste a playlist's YouTube or YouTube Music link to open it, no share menu needed.

### Podcasts
- **Episode details**: an episode's ⋯ (or a tap on its description) shows all of it, with working links, its date and
  length, and the show it's from. A show YouTube gives no description says so instead of showing nothing.

### Fixes
- **About 200 MB less memory in the first minute after Prism opens** (likely #103). With a big library, working out your
  artists' genres from what was already kept on the phone ran a text pattern over every song of every artist, and each
  of those checks held a little memory until Android tidied up: on the test phones Prism climbed from ~190 to ~340 MB
  of native memory for most of a minute after opening, enough for Android to close it on a phone with little memory.
  Now each genre name is worked out once; it stays near 110 MB. Reading saved lyrics back (for every download, at each
  start) no longer builds its patterns again each time either.
- **A search no longer shows "The coroutine scope left the composition"** as its error when it was interrupted by the
  next letter typed, or by leaving the page.
- **The Card mini player is glass like the tab bar** under it, with the tab bar's own background (see-through or
  solid), and its progress line runs along its bottom edge. With Frosted Glass off it used to turn into a solid bar
  with the progress line on top.
- **Podcast episodes no longer turn LIVE** a few seconds in (#128). Episodes recorded from a live broadcast were taken for
  streams still on air, so the length disappeared and the progress bar stopped seeking. They play as normal episodes now.
- **Each local song shows its own cover** (#129). Android files untagged songs from one folder (and other mixes) as one
  album, so one file's cover went on all of them. Those songs now show the cover inside each file.
- **Player, Layout & navigation and Lyrics are listed once** in Settings (#127), under Customize Prism, no longer twice.
- US spelling in the last few places: Favorites, Centered.

## 1.7.5

The fourth customization update: lyrics, your way.

### Lyrics, your way
Settings → Lyrics (with a live preview at the top), or the player's ⋯ → Player settings. Every choice shows an example of
itself, drawn the way the lyrics are, and nothing changes until you pick something: the lyrics look as they did.
- **Six styles** for the line being sung: **Prism** (each word grows bolder as it's sung, as before), **Classic karaoke**
  (color fills the words), **Bold card** (big bold lines on a color from the cover, changing with each song), **Glow**
  (the sung words glow, in the shape of their letters), **Typewriter** (words appear as they're sung) and **Calm** (no animation, the line just
  brightens). Lyrics timed by the line rather than the word are filled in at a steady pace in Karaoke and Typewriter.
- **Colors** for the sung line and for the others: white, your light, the accent, the cover's color, or your own from the
  color picker.
- **The look:** weight (Thin to Bold), line spacing, how dim the other lines are, focus blur as a strength (from off to
  strong; the old switch carries over), the size of background vocals, a shadow for photo backgrounds, and capitals.
- **Where the lines go:** the sung line near the top (as before), a third down or in the middle, and how many of the lines
  to come show (all, three, one or none).
- **Translations and romanized lines:** over or under each line, their size, color and italics.
- **A background for the lyrics:** as the player's (as before), plain dark, the cover blurred, the animated cover, the
  cover's color, or your own color or gradient. **Immersive mode** picks its own from the same list (the cover, as before).
- **Same look everywhere:** the current-lyric line above the progress bar and immersive mode follow these settings (on by
  default; with every setting as it comes they look as before). Off, they keep Prism's own look.
- All of it is part of your Looks, Reset look and backups.

### Also new (from GitHub)
- **History** in Library (#118): what you played, newest first under day headings, with the time and repeats in a row
  folded into one ("3×"). "On this phone" is Prism's own (kept on the phone); signed in, "Your account" shows YouTube
  Music's history, plays from the YouTube Music app and website included. A Library start tab too.
- **A song and its video as one** (#111, Settings → Library & Replay, on): liked both a song and its music video? The
  lists show it once, as the song. The video stays liked on YouTube; nothing is changed there. Live takes and other
  recordings stay apart.
- **Prism's own volume** (#116, Settings → Player → "The volume slider changes"): the player's volume slider can change
  Prism's volume instead of the phone's, so other apps stay as loud as they are. It works with crossfades, on Bluetooth
  and offline; casting still uses the speaker's own volume.
- **Hide the status bar** (#115, Settings → Layout & navigation → Screen): more room for Prism; swipe down from the top
  to see it for a moment. Part of your Looks.
- **Tidier settings pages** (#119): the explanations on every settings page now sit behind an ⓘ beside the setting,
  choice or group they explain, as the longer details already did, so each page shows just its settings until you ask.
  This includes the Color page's notes, its "Changes" lists and the three parts' descriptions. What's always shown is
  only what's happening now: space used, an error, why something is still or greyed out.
- **Library's tiles** are picked and ordered from a new edit button at the top right of Library (beside sync), instead of
  an "Edit tiles" tile at the end of them.

### Lighter on the battery
Nothing works differently; Prism just does less that nobody sees.
- With the full player open, the screen under it (an animated background, the playing bars) holds still instead of
  being drawn every frame behind the player: about half the work while the player is up, in our tests.
- The player's clocks (the progress bar, volume, where the sound is going, the sleep timer) stop ticking while Prism is
  out of sight, and catch up the moment it's back.
- Animated backgrounds, the playing bars and loading bars redraw only themselves as they move, not the page around them.
- Paused lyrics no longer redraw on every frame of the screen (and no longer keep a 120 Hz screen at 120 Hz).
- Song rows only change when their own like or download marks do, not at every step of every download.
- With the screen off, widgets wait for it to come back on to move their progress bars.
- Lossless sync's look every few hours only runs while the lossless add-on is on.
- The stall watcher (Diagnostics) sleeps for good once it's turned off.

## 1.7.4

The third customization update: the player, your way. And a faster Prism.

### Faster
- **Prism no longer runs slow after an update.** After every install or update, Android ran much of Prism's code in
  its slow interpreter until it got round to compiling the app, often overnight while charging, and only the parts it
  had seen used. Prism now carries a startup profile (the code starting up, Home, Search, the player and the lyrics
  use), and **updates from Prism's own update banner are compiled while they install** (Android 9 and later), the way
  the Play Store does it for its apps. That starts with the update after this one: this one is installed by the
  Prism you have now. Installed some other way, Prism is compiled from the profile at the phone's next maintenance.
  On an Android 13 test device, starting Prism compiled took 2.4 s instead of 3.9 s.
- **Smoother while music plays.** The player and the mini player were rebuilt four times a second just to move the
  progress bar; now only the bar redraws. The full player is noticeably lighter to open, close and swipe.
- **Search on this phone** (your music files and downloads) is matched off the screen's thread, so typing doesn't stall
  with a big library, and lists of covers do less work as they scroll.
- **Home scrolls more smoothly** with a lot of listening history: the Replay card no longer reads the whole year from
  the database (and jumps the list) every time it scrolls back into view, and Made for you no longer unpacks every mix
  on the screen's thread.
- **Diagnostics see stutters too:** with Settings → About → Diagnostics on, anything that holds up the screen for more
  than a couple of frames is noted with where it happened, so a log shared from a slow phone says what to fix (#100).

### The player, your way
Settings → Appearance → Player, or the player's ⋯ → Player settings. Every choice shows what it looks like.
- **Layouts:** Standard, **Cover** (a big cover; tap it for the controls), **Lyrics** (opens on the lyrics, a small
  cover beside the title), **Minimal** (the title and play; gestures do the rest), **Vinyl** (the cover as a record's
  label, turning as it plays and slowing to a stop on pause, with a tonearm if you like) and **Cassette** (the cover on a
  tape whose reels turn as it plays).
- **A visualizer**, off unless you pick one: **Bars** along the bottom, a **Wave** behind the title, a **Ring** around
  the cover, the progress bar's **Pulse**, or a **Glow** that breathes with the music; in your light, your accent, white
  or the cover's color. It listens to Prism's own sound (no microphone, no permission) and only runs while the player is
  open and playing, so turned off it costs nothing; Reduce motion keeps it still.
- **Your buttons:** pick three to six for the row under the controls, in your order: lyrics, video, up next, play on,
  sleep timer, like, not for me, add to playlist, share, Sing, DJ mode, equalizer, download, start radio, and **speed**
  (songs at 0.75× to 1.5× for this session; crossfades blend at the songs' own speed, then it carries on). With Like in
  the row, the heart beside the title steps aside.
- **Notification and car buttons:** choose and order the buttons beside play. Your phone's notification and lock screen
  show the first two, Android Auto shows them all; Back 10 s and Ahead 30 s are new choices.
- **Your order:** the current lyric, progress bar, controls, volume and buttons, top to bottom as you like.
- **A waveform progress bar**, drawn from each song's own loudness (from Prism's song scan; nothing extra downloaded),
  and the progress bar in your light, your accent, white or the cover's color, thinner or thicker, with a dot, a line,
  a ring or no knob at the playhead.
- **The title** left or centred, small, medium or large, scrolling or not; and which times show under the bar.
- **The background:** the color mesh can drift slowly (it stays still unless you ask), its colors softer or richer, the
  blur softer or clearer; an animated cover can fill the whole player; and "Your background" can be any of Prism's
  backgrounds or a photo of your own just for the player, not only Home's.
- **Gestures:** what swiping left, right and up, a double tap and a long press do on the cover, and swipes on the mini
  player: next or previous song, skip back or ahead, play / pause, like, up next, lyrics, sleep timer, video, Sing, the
  song's menu, stop, or nothing.
- **Vinyl**, a new Look built around the record player.
- **Drag to reorder, everywhere you put things in order:** the player's buttons, the notification and car buttons, the
  order under the title, the tabs, lyric sources, Library's tiles, Local files' sections, Customize Home and your
  playlists. Hold a row (or drag its grip) and it lifts onto a card edged in your light; the others slide out of its
  way, and holding it near the top or bottom of the screen scrolls the page, so the last one can go first in one move.
  The up and down arrows are still there.

### Fixes
- **Prism closed by itself on some Android 12+ phones** (#100's log) when a download started or restarted while it was
  in the background (Wi-Fi coming back, a stalled download restarted). Downloads now wait for Prism to be opened in
  that case; ones already running carry on.
- **Shuffle and repeat come back after Prism restarts** (#101): the queue did, but shuffle was off again. Now shuffle
  returns in the same order, and repeat as it was.
- **A live version no longer plays as the studio song** (#113): with "Songs, not videos", Prism only swaps a video for a
  song when it's the same recording: anything in brackets that isn't just "Official Video", "Lyric Video" and the like
  (a live album, a session, another edition) tells them apart, and the lengths must match.
- **The font picker lines up** (#117): an even grid across the page, every card in a row the same height, also with
  larger text.
- **The browser remote's queue lines up** (#112): the now-playing card's style was spilling onto the queue's song.
- **"Last crash" says when the phone closed Prism** (#103): on Android 11 and later, Prism asks Android why it ended
  last time (not responding, out of memory while playing, a crash in a library) and saves that as a crash too, with
  Android's own trace, so the diagnostics no longer show an older crash instead.
- **Diagnostics say more about refused streams** (#114): which way the song was asked for, and for a refused part of a
  song, how far in it was, which of YouTube's apps the link came from and whether it carried a PO token.
- The speed you picked no longer stays at 1× after a crossfade (it came back only when the blend happened to change the
  song's speed).
- **Android 8 with Bluetooth headphones:** opening the player could close Prism (it asked for the device's address,
  which Android only has from 9 on).
- **Speed dial** has even margins on both sides (#67): the next page no longer peeks in at the right; the dots say
  there's more.
- The Smart Shuffle button on the player follows the queue when it changes while the player is open.

## 1.7.3

The second customization update: Looks.

### Looks
- **Save your look.** Settings → Appearance → Looks keeps your colors, type, icons, backgrounds, and the player's and
  lyrics' look together as a Look. Switch between them with one tap; everything changes at once.
- **Prism's own Looks**: Prism (how it comes), Paper, Midnight, Neon, Soft and Studio. Copy one to make it your own.
- **Share a Look** as a QR code, a code in a message, or a `.prismlook` file that can carry its background photos and
  (if you tick it) your font. A Look someone sends shows first, with Use it and Save; it can only change how Prism looks.
- **Community Looks.** Looks → From the community lists Looks people added to Prism's GitHub repository (`themes/`, by
  pull request; its README says how). The list is downloaded only when you open it, and each Look shows as a preview
  first. Four to start: Aurora Glass, Graphite, Sunset Drive and Forest Walk.
- **Day and night.** One Look by day and another by night, switched at sunrise and sunset (worked out from your time
  zone, no location needed) or at times you set.
- **Undo.** After every change to the look, an Undo bar says what changed ("Corners → Sharp"). Looks → History goes
  back further (the last 10 changes).
- **Nothing gets lost.** Switching to a Look while your own mix isn't saved keeps it as a "Before <Look>" card, so you
  can always go back to it. Each Look's menu (or a long press on its card) says what it changes, and so does a Look
  someone sends you, before you use it.
- **Update your Looks.** Change something after putting on one of your own Looks and the Looks page says what you've
  changed since you saved it, with **Update** (or Save as new, or go back to it as saved). Any of your Looks can also be
  updated to your settings from its menu. Undo takes an update back.

### Changing the look
- **A live preview** at the top of every look page: Home with the mini player and tab bar, the player and the lyrics,
  as your choices make them. It stays pinned under the bar as you scroll.
- **Customize by tapping.** Turn it on in Appearance (or hold the Settings tab), then tap the mini player, the tab bar,
  the player or any Home section to change just that, right there, with an Undo in the sheet. The player's ⋯ →
  Player settings opens the same sheet over the player.
- **Reset this page** on each look page, beside Appearance's Reset look.

### Fixes and smaller changes
- **Prism closed on its own on some phones (#103).** Reading a long Liked songs list could fail on a phone short of
  memory, and that closed the app. Now the list is simply read again.
- **Liked songs follow YouTube Music's order (#99).** "Original order" now matches YouTube Music after every sync; a song
  you liked again elsewhere used to keep its old place.
- **Songs not available in your country (#93)** now play their music video's sound instead of stopping, when the video
  is allowed where you are.
- **Your own uploads (#101)** play as themselves. YouTube Music marks them apart from its own songs, and Prism took them
  for videos, so "Songs, not videos" swapped an upload for YouTube's own recording of the same name (a 6-minute full
  version played as the short one). They're songs now, are asked for with your account from the start, and never get
  a music video looked up.
- **Lyrics timing (#109)** goes up to two minutes either way, with ±5 s steps. It's kept on your phone, for that song.
- **Section headings (#104, #107)** are a little bolder, the same on every page, and the whole heading row opens the
  section, with its arrow at the far end where your thumb reaches it.
- **The time-of-day mix is named after what's in it.** "Your Friday morning indie" could be mostly rock: it was named
  from its first songs' genres before Prism had settled them. Now a genre is in the name only when more than half the
  mix is that genre (else "… mix"), and the name is checked again as genres become known.
- **Android Auto pictures.** Downloads showed a warning sign (it had no picture: now it has its own, as does Liked songs
  without one of yours), YouTube Music's mixes and "Liked Music" on Home were blank tiles (their covers come as PNGs;
  the car now always gets JPEGs, from the covers already saved on your phone first), and a cover that can't be fetched
  shows a playlist tile instead of nothing.

## 1.7.2

The first of the 1.7.x customization updates: color.

### Color
- **Pick any color.** A new color picker is used everywhere Prism asks for a color. You can:
  - pick on a color wheel, or set exact RGB and HSL values;
  - type or paste a color code (#3A86FF);
  - take a color from what's playing, your wallpaper or any spot on a photo;
  - star colors to keep them; your recent colors are kept too.

  Before you use a color, it shows how readable it is on dark and light pages.
- **Your light.** The light that runs through Prism (progress, the Play ring, switches, the song that's playing) comes in 12 presets: Prism, Sunset, Ocean, Aurora, Forest, Ember, Candy, Neon, Ice, Gold, Silver and a color-blind-safe one. There's also **White** (**Black** in light mode), and you can build your own from 2 to 7 colors, smooth or as hard bands like the logo's bars. White light is now this White preset, with its light really white (or black).
- **Your accent.** The accent comes from the song's cover, your wallpaper or a color you pick. It can be used **exactly as picked** or softened to suit dark and light pages, and light mode can have an accent of its own. With the song's cover, you choose which of its colors to use, whether grey covers still give a color, and how the color moves between songs.
- **Your accent on every page.** Artist, album and playlist pages (and the menus opened from them) now use your accent, like the rest of Prism. Before, they always took a color from their picture, so the accent you picked hardly showed. Their frosted backgrounds still take the picture's colors. To have them take their picture's color instead, set Color → Artist & album pages → **Picture's color** (black-and-white pictures keep your accent). An artist's page can also have a color of its own (⋯ → Page color).
- **Page background.** Automatic, plain grey, tinted with your accent, or a color of your own, with a strength slider. It colors the frosted background behind Home, Search, Library, Replay and Settings, and sheets and dialogs.
- **Text contrast.** Standard, Medium or High, for firmer secondary text, lines and accent.
- **A Color page you can see.** A preview of your Prism (a small album page on your real background, in dark or light mode) stays pinned at the top while you scroll, with the three parts numbered: ① the light, ② the accent and ③ the background. Every choice is a card that shows what it looks like, each group lists everything it changes in the app, the color picker shows Prism with the color as you drag, and "Reset colors" at the bottom puts everything back.
- **Appearance is tidier.** It now holds the theme and four pages: Color, Text, Icons & shapes, and Glass & backgrounds.
- **"Color" is spelled the US way** throughout Prism.

### New
- **App icon shortcuts.** Press and hold Prism's icon for Search, Recognize a song, Liked songs and Downloads.
- **Song credits.** A song's menu (and the player's ⋯) shows who performed, wrote and produced it.
- **Library your way.**
  - Pick and order the tiles at the top of Library ("Edit tiles", or Settings → Library & Replay → Library tiles).
  - Put your playlists above the tiles (Edit tiles → "Where your playlists go" → Playlists first).
  - Albums can be sorted with downloaded ones first.
- **Pin Prism playlists to Home.** Customize Home → Pinned playlists lists your Prism playlists as well as YouTube Music's.
- **Square video covers** (#92). A video played as a song no longer shows YouTube's black bars in its cover: the bars are trimmed and the middle of the picture is used, on the player, in lists, in the notification and in Android Auto. Turn it off under Settings → Player → Cover.
- **Lyrics on mobile data** (#95). Turn it off under Settings → Lyrics → Sources, and on mobile data Prism looks lyrics up only once you open them. Lyrics already saved on the phone still show.
- **Which downloads failed, and why** (#98). Under "N downloads failed", **See which** lists each song with the reason (YouTube refusing the link, the phone going offline or running out of space…), and you can try each one again or remove it.
- **Downloads.** The songs' search, genre filter and sort also apply to the downloaded albums and playlists above them.
- **New releases.** Filter by time (this week, this month, 3 months) and type (albums, or singles and EPs), hide what's already in your library, and sort by newest, artist or title.
- **Release Radar** also watches the artists you follow on YouTube Music.
- **Save from a long press.** Holding an album or playlist has a button to add it to (or remove it from) your library; holding an artist has a star to favourite them.
- **Likes as stars.** Settings → Appearance → Icons & shapes → "Likes as stars".
- **Immersive mode** can keep previous, play and next on screen ("Keep controls on screen").
- **Casting is easier to find.** The line under the player shows the cast mark, and the player's ⋯ has "Play on a speaker or TV".
- **Premium quality.** Signed in with YouTube Music Premium, Playback & sound → "Premium quality" plays Premium's 256 kbps streams. Without Premium, Prism notices and plays as usual.
- **The Ring Replay card** says "Your top songs, artists and genres" like the other cards, and shows how far through the year it is to a tenth of a percent.
- **Pure black covers every background.** In dark mode with Pure black on, every screen background (the color ones, Simple patterns, Designs, Now playing) sits on true black, its colors kept but quieter.
- **Android Auto.**
  - Four tabs along the top: Home, Made for you, Recents and Library, with Liked songs, Downloads and your Prism playlists inside Library.
  - With "Covers in a grid" (on by default; Settings → Lyrics → Android Auto), Home shows its sections as rows of covers, and Library and Made for you show as covers.
  - YouTube Music's own mixes in your library (My Supermix, Discover mix…) show their covers.
- **Crash details stay on your phone.** If Prism closes unexpectedly, Settings → About → Diagnostics shows "Last crash", and you can share it.

### Fixed
- **Crossfade not happening at all on some phones.** When the second player couldn't line up after two tries, the blend was dropped. A plain blend now goes ahead anyway, crossed over slowly, and every reason a song isn't blended is written to Playback diagnostics.
- **A hiccup a few seconds into songs.** When a song's measured loudness came in, its volume jumped several dB at once. Changes now ease in over two seconds, and small ones mid-song are skipped.
- **DJ mode was laggy.** It reads one song's key at a time while music plays, and its new order goes into the queue at once (at most every second and a half) instead of one move per song.
- **Stutters on slower phones.** Background song scans (loudness, tempo, keys, music-video timing) step aside when the sound runs short.
- **Freezes when Android's audio service is slow** (after a restart, during a Bluetooth switch, with some phone makers' sound engines): the equalizer and sound effects are now set up on a thread of their own.
- **Starting Prism** does less before the first screen appears.
- **Typing in Search could lag** with a big library (#91).
- **Your own uploaded songs** that YouTube refuses twice are asked for with your account (#101).
- **Pure black stayed grey** behind the frosted background in dark mode.
- **A white accent drew a grey progress bar** on the player and mini player.
- **Album and playlist pages looked black below the picture.** They keep more of the picture's color again, and text still reads.
- **Page background did almost nothing** behind the Ambient background, and **Accent tint with Wallpaper** used your old picked color instead of the wallpaper's.
- **Secondary text turned pink** with a strong page tint, so it looked like a faded accent.
- **"Ready offline"** now says it's a mix of songs already on your phone.
- With Reduce motion on, the playing bars no longer animate behind the scenes.

## 1.7.1

### Fixed
- **Crossfade could go silent, or not happen at all.** Prism plays a song's ending on a second player while the next song fades in, and on a slower connection that second player could stop to load. Just before a blend, that put it out of step, and the crossfade was dropped. Mid-blend, the fade stood still with both songs nearly silent until the ending came back, then rushed through. The ending is now loaded whole before it's needed. A blend that isn't in step is put off by two seconds and lined up again instead of dropped. If the ending still stops to load, the next song simply comes up. Each blend is now logged step by step in Playback diagnostics.

## 1.7.0

### New
- **Search on the Podcasts page**: type a show, host or topic at the top of Podcasts and shows and episodes appear as you type, without leaving the page. (Search's own Podcasts and Episodes filters still work too.)
- **Genres you can trust**: a song's genre now comes from Apple's catalogue (iTunes) where it can: each song's own, so a rapper's country single is Country, a musical's songs are Soundtracks & musicals, and metal, blues and Christian music are named as such (Deezer, used before, files them under Rock and Pop). One request covers an artist, about 20 a minute as iTunes allows, worked through in the background and kept; Deezer answers at once until then. Songs iTunes doesn't list for their artist are checked against their own release on Deezer. On a test set of 68 well-known songs across every genre, 66 now get the right one (54 before).
- **Prism pictures for your account**: twelve profile pictures drawn in Prism's split light (a prism, a spectrum ring, the mark, an aurora, a dawn, a night sky and more), beside your own photos and your initial, on your Prism Account's page.
- **A Prism Account**: who you are in Prism, a name and a picture, kept on this phone and in your backups, with no email, password or server. Prism greets you by it on Home and uses it wherever it names you (Blend, Listen Together, handoff, the player's "Sam's Phone"). It owns everything Prism keeps on the phone: likes, Prism playlists, saved albums, starred artists and Replay. Making one changes nothing you already have. Signed in to YouTube Music too? Your account's page can copy its playlists, saved albums and artists into Prism, so they stay on the phone after you sign out (copy again to bring them up to date), and signing out offers to keep a copy first. New installs are offered one first on the welcome screen; everyone else finds it at the top of Settings.
- **Prism Music**: the music Prism finds for you is called Prism Music in Prism now: in Search, Settings, Customize Home, autoplay, lyrics sources and messages. Where it's your Google account that's meant (signing in, playlists saved to your account, Premium), it still says YouTube Music.
- **Blend covers**: each Blend gets a cover of its own, your top song and your friend's in two rings of split light that cross where your tastes meet, with the match in the corner. Or pick any photo for it (the Blend's ⋯ → Pick a cover, or tap the cover), only for this phone. Blends are no longer in Home's Made for you, which is Prism's own mixes; they're in Library → Blend.
- **Best part is in color again**: the Best part tag is lit in the split light, and its mark on the scrubber is a fainter, shorter hairline.
- **The Replay card heads Home**: it's the first thing on Home now, on new installs and (once) on Home layouts customized before 1.7; move it in Customize Home if you'd rather have it elsewhere.
- **Pick how Replay looks on Home**: Settings → Home screen → **Replay card** has nine looks, from the quietest to the most colorful, with a preview of your own year: **Quiet** (glass and a hairline of split light), **Ledger** (time, top artist and top song side by side), **Wash** (a faint wash of color and your months as slim bars; the default), **Spectrum frame** (the card ringed in split light, the time in color), **Ring** (how far through the year you are), **Cover** (your top song's cover), **Prism stripe**, **Spectrum bars** (your months, red to violet) and **Aurora** (full color). The **Prism** profile picture is redrawn too: white light falling into a glass prism and opening out as a fan of color.
- **Prism's own radio**: when your queue ends, autoplay can now pick from your own music instead of only asking YouTube: songs you've played together before, the same artists and genres, keys that blend when DJ mode has read them, and what you play most, with no artist twice in a row and nothing you've just heard. Settings → Playback & sound → **Autoplay from**: **Both** (the default, your music mixed with YouTube's radio), **Your music**, or **YouTube Music**. Offline, or after a music file from your phone (which used to stop autoplay), it's always your music. Made on the phone from Replay's history; nothing is sent anywhere.
- **More of Home made on your phone**: **On repeat** (your most played this month), more mixes in **Made for you** (a mix for each of your top genres and artists, from songs you know, and **Ready offline**: what plays with no connection, after the Daily Mixes), plus **Repeat rewind** (songs you had on repeat months ago) and **Forgotten favourites** (likes and old favourites you haven't played in a while), which you can add from Customize Home. Another upload of the same song doesn't count twice.
- **Home works offline**: with no connection, Home says so and shows only what plays: Quick picks from your downloads, songs kept whole and your files, and Made for you, your mixes, Recently played and Liked songs cut down to what's on the phone. YouTube's shelves come back by themselves when the connection does.
- **Blend on both phones at once**: on the same Wi-Fi, when a friend scans your Blend code, their Prism sends theirs straight back to yours, so you both have the same Blend without swapping codes. No server: the phones talk to each other directly, only while your code is on screen, and only a phone that read the one-time key in your code gets in. On different networks (or with a code sent as a message) it works as before: they send theirs back.
- **A tour of Prism**: after setting up (and the first time anyone opens 1.7), a short tour walks through Home, the player, lyrics, Sing, DJ mode, Made for you, Search and Recognize, your library, Replay and Rewind, Prism Connect and how to make Prism yours, a page at a time. Skip it whenever you like, and take it again from Settings → About → **Take the tour**. From now on, an update with new features shows only what's new.
- **Playlists and saved albums without an account**: make playlists in Prism (Library → New playlist, or any song's menu → Add to playlist) and save albums with the bookmark on their page, all kept on this phone and in your backups, no YouTube Music account needed. Prism playlists can hold music files from your phone too. Signed in, they sit beside your YouTube Music playlists.
- **Podcast episodes have a podcast player**: back 15 seconds and ahead 30 instead of previous and next, and the speed (1×, 1.25×…) a tap away where Lyrics and Video were.
- **The sleep timer shows on the player**: a small moon with the time left beside the device line; tap it to change or cancel the timer.
- **Music files without artwork get a cover**: the song's initial on a color of its own, edged in the split light, instead of a bare note.
- **Notifications are asked for when they're needed**: Prism no longer asks before its welcome screen, with no reason given. The tour's last page explains what they're for, or the first download asks.
- **A new look, "Aura × Spectrum"**: Prism is redrawn from the ground up. Pages take their color from what's playing, under frosted glass, with soft corners, and the tab bar and mini player float on glass. The split light of Prism's name runs through it: the song that's playing, progress lines, the tab and chip you're on, the ring around Play, a "Continue …" line on Home for the album you were in, Liked songs' cover, switches when they're on, and Settings' sections from red to violet. The new font is Outfit, light at large sizes. Every icon is new, drawn for Prism, as are the app icon (all six launcher icons) and the notification icon. In synced lyrics the line being sung thickens word by word as it's sung, with a thin line under it showing how far through it you are. Prism's own home-screen widgets (Prism, Cover, Disc, Strip, Recognize) match it. There's a new **Spectrum** screen background, and the color-from-the-cover background is now called **Ambient**. Prefer a quieter, neutral Prism? Settings → Appearance → Color → Style → **White light** (the light before the prism splits it): neutral greys and ink, frosted backgrounds in grey, and your accent color where the split light would be (progress, the Play ring, what's playing, switches), as in Prism's earlier look. Pick the white accent for no color at all; color then stays only in the album covers and photos. Every look setting you had (corners, glass, backgrounds, tab bar and mini player styles, the Cupertino icon pack) still works.
- **New fonts** (Settings → Appearance → Text → Font): besides Prism's own (Outfit), **Clean** (Manrope), **Geometric** (Plus Jakarta Sans), **Airy** (Urbanist), **Rounded** (Nunito), **Editorial** (Fraunces, a modern serif) and **Mono** (Geist Mono) now come with Prism, so they look the same on every phone (Google Sans and Rounded used to need a Pixel's own fonts). Each works for the lyrics too, including the line that thickens word by word as it's sung. Google Sans and Condensed are gone; if you had one picked, you're moved to Clean. System and your own font file are still there.
- **Your own best part**: hold the **Best part** button (or ⋯ → **Your best part**) to pick where a song's best part starts: on a bar, nudged by 5 seconds, from where the song is now, heard before you save it. It's what Best part, "Play from the best part", Rewind and Blend use for that song from then on, and it can go back to the part people replay most. The button itself is quieter now.
- **Android Auto: Play as a DJ set**: every song list in the car has **Play as a DJ set** under Shuffle play: the list in the order that blends best, mixed and beat-matched (DJ mode).
- **Hide Home's Continue line**: Settings → Home screen → **Continue line** turns off the "Continue …" line under the greeting.
- **Settings, tidied**: every setting reads in one short line, with the full explanation one tap away (ⓘ). The Player page is shorter: what shows on the player is one set of chips to tick, and seeking and haptics are one Gestures group. Lyrics settings are split into Look and Language. The player's ⋯ menu has **Player settings**.
- **Sing, as on Apple Music**: with the lyrics open, their foot is a glass bar with where they come from (tap to change) and a **Sing** pill. Tap it and Sing goes on: its controls (how much of the voice stays in, the key, how far the song is ready) come up just above, with the lyrics playing on behind them; tap it again for off, hold it to hide or show the controls. From the player's ⋯ menu, Sing opens the lyrics the same way. **Get songs ready while Sing is off** (Settings → Lyrics → Sing) separates the playing and next songs ahead, so Sing starts at once when you turn it on.
- **Immersive mode, redrawn**: the lyrics are set light, each word thickening as it's sung, with a thin line of split light under the line showing how far through it you are. A tap brings up the song's name and a glass capsule with previous, play / pause and next as well as its settings (still held to leave), and a hairline of split light along the bottom shows how far through the song you are.
- **Play on**: the player's device list is the frosted sheet the player's other menus are: this phone, speakers and TVs, and Prism devices in glass groups, with what's playing on a disc ringed in split light. While casting, the line under the controls is a glass pill with the cast mark.
- **Browser remote, redrawn**: Prism's own type (Outfit, served by the phone), glass panels over the cover's color, the spectrum ring round Play and in the progress and volume bars, and lyrics set light that thicken word by word as they're sung, with a line under the one being sung.
- **Tablets, foldables and landscape**: on a wide screen the tabs move to a rail down the side, and the player shows the cover and controls beside the lyrics, the queue or the stream stats. On its side a phone gets the same player with a smaller cover. A foldable half-open like a laptop puts the cover above the fold and the controls below it. Settings lists its pages beside the one that's open, and albums and playlists keep their cover beside the songs on a tablet in landscape.
- **Jump to the best part**: in a song's first seconds a **Best part** button under the progress bar skips to the stretch listeners replay most (YouTube's own "Most replayed" data). Without that, the best part is where the chorus first comes round in the lyrics. The start lands on a lyric line or a bar, not mid-word. A song's menu has **Play from the best part** too. The button can be turned off in Settings → Player.
- **Headphone correction** (Settings → Playback & sound → Equalizer): pick your headphones from almost 9,000 measured by the AutoEq project, and Prism corrects them to sound neutral (the Harman target). The correction's curve is shown, with a strength slider. Each pair of headphones keeps its own correction and switches on when they connect; the phone's speaker and the car never get one. You can also import a ParametricEQ / EqualizerAPO file. It runs inside Prism, so it works on any phone and with Crossfade, and stats for nerds shows it.
- **Romanized lyrics**: songs in Korean, Japanese, Chinese, Arabic, Hindi, Russian, Greek and other scripts get each line in Latin letters under it (or in its place), so you can sing along. Karaoke lyrics light up word by word in Latin letters too. Korean (Revised Romanization, with its sound changes), Japanese, Russian and Greek are spelled out on the phone. Japanese kanji need a one-time 13 MB download of reading data ("kimi no na wa", not "kiminonaha"). Chinese (pinyin), Hindi, Arabic and the rest come from Google Translate, which is sent only the lyrics' text. Results are kept, so they work offline after the first time. Lyrics in your phone's own language are left as they are unless you ask (Settings → Lyrics → Romanize lyrics), and the player's ⋯ menu turns it on or off for one song.
- **Sing**: karaoke with the song's own music. The microphone button by the lyrics (or the player's ⋯ menu) takes the singer's voice out, worked out on your phone from the very copy that's playing (Deezer's Spleeter, a one-time ~50 MB download). A song is ready to sing a few seconds after you start, and a whole one takes about 30–40 s on a recent phone. The next song is prepared while this one plays. Leave a little of the lead voice in to sing along with, or none, and move the key up or down by up to six semitones (the tempo stays). The voice is only taken out where Prism has checked it lines up with the music to the sample, so nothing ever sounds doubled. Crossfade pauses while Sing is on. Prepared songs are kept (Settings → Lyrics → Sing: on mobile data or not, how much space to use, clear them). Separation is very good, but not perfect: echoes and some backing vocals can stay faintly.
- **DJ mode**: turn on **DJ** in the queue (or pick **Play as a DJ set** on an album or playlist), and Prism plays the songs ahead like a DJ would. Each song's key is found on your phone. Prism then orders the next 40 songs so each one goes into the next in a key that blends (the Camelot wheel), at a tempo close enough to beat-match, with no artist twice running. They're crossfaded and beat-matched: long blends where the keys sit together, short ones where they don't. Pick the energy: **Steady**, **Build up** or **Wind down**. The queue shows each song's key and tempo ("8A · 124"). Each song gets a quick listen (three 25-second stretches of it, a few songs at once, the next ones first), and the order improves as each one comes in; the playing song and the next one never move. Once a day, on Wi-Fi while your phone charges, the songs you play most and like are looked at ahead of time, so most sets start in order. On mobile data the queue says the songs are waiting for Wi-Fi, with a button to use mobile data instead. On 28 well-known songs the key was exact for 18 and within one step on the wheel for 26. Unclear keys aren't guessed. Shuffle turns DJ mode off, and a new queue starts without it.
- **Made for you**, worked out on your phone from what you play (nothing about your listening leaves it):
  - **Daily Mixes** (up to six) group your artists by what goes together, by genre and by which ones you play back to back. Each is 50 songs: ones you know, with new ones like them in between (two in every five). They're made once a day and stay the same all day, offline too.
  - **A time-of-day mix** ("Your Sunday evening rock") is made from what you play at this time of day, plus more like it.
  - **Release Radar** keeps an eye on the artists you've starred, saved and play most (looking every few days). It finds their new albums, singles and EPs with their exact release dates (from Deezer). They show on a **New releases** page and on Home, and in a weekly **Release Radar** playlist (refreshed on Fridays). There's a notification when a starred artist puts something out; turn it off in Settings → Home screen. The first look at an artist only learns what's already out, so their back catalogue is never announced as new.
  - The mixes are on Home ("Made for you", added once to existing layouts) and in Android Auto.
- **Smart Shuffle**: press shuffle again on a playlist or album, and a recommendation like the music around it slips in after every three or four songs (marked ✦ in the queue). While one plays, the ✦ button by the like button adds it to the playlist (your own YouTube playlists) or turns it down. Turning Smart Shuffle off takes the ones still to come back out.
- **Exclusions**: "Don't play this artist" (artist page ⋯) keeps an artist out of radios, autoplay, Smart Shuffle, your mixes and Smart Downloads. "Leave out of my taste" (a playlist's ⋯, a song's menu) still plays them, but they no longer shape your recommendations, search, or Smart Downloads; Replay counts them as usual. Everything left out is listed in Settings → Library & Replay → Exclusions.
- **Blend** (Library → Blend): a playlist of two tastes. Show a friend your QR code (they scan it in Prism), or send it as a message they share to Prism, or paste it. Prism shows your taste match, the artists and genres you share, and a 50-song playlist: the songs you both play first, then each of yours in turns. Both phones make exactly the same Blend once you've each taken the other's code. There's also "Discover together" (new to you both, like what you share), Play / Shuffle, Save to YouTube Music, and a picture to share. No server is involved: the code carries your Blend name, top artists and songs, and genres, and you can see exactly what's in it ("What you share"). Artists you don't play and things left out of your taste aren't in it.
- **Song recognition**: tap the new button in Search's box (or add the **Recognize song** tile to Quick Settings) and Prism listens for up to 12 seconds to tell you what's playing around you. It then finds the song on YouTube Music, ready to play, like, or open its album or lyrics. The sound stays on your phone: Prism makes Shazam's fingerprint of it on the phone (ported from SongRec) and only that goes to Shazam, with no location. If Prism is playing, it pauses while listening (or keep it playing to recognize Prism's own song). Offline, the fingerprint is kept and looked up when you're back online, with a notification when it's found. Everything found is in Library → Recognized. The microphone is asked for the first time you use it, and used only while that screen is open.
- **Prism Connect** (Settings → Prism Connect):
  - **Speakers & TVs**: the player's device list (the line under the controls) now finds Google Cast devices (Chromecast, Google TV, speakers and TVs with Cast built in) and speakers, TVs and AV receivers that play music sent to them (DLNA / UPnP) on your Wi-Fi. Pick one and the music plays there; the queue, lyrics, notification and Android Auto carry on as usual, the phone's volume keys and the player's volume bar set its volume, and pausing or skipping on the TV's own remote comes back to Prism. Songs go to it decoded on your phone into plain uncompressed audio, which every renderer plays (a renderer that refuses one kind gets the other), and run into each other without a gap where it allows. The equalizer, Crossfade, Sing and videos are the phone's own, so they're off while casting, and live streams play on the phone. Google Cast devices play in Google's own media player on the TV, with the song's title, artist and cover; Prism talks to them directly over the open Cast protocol, with no Google SDK, so nothing else is installed. Leaving Prism's player on the TV (its Back button) or opening another app there ends casting; the music pauses on the phone.
  - **Control from a browser**: switch it on and open the address shown on any computer or tablet on the same Wi-Fi. After typing a six-digit code from the phone (once per browser), it shows what's playing, its controls and volume, the queue, search, and lyrics that light up word by word. Only browsers you pair get in, guessing the code is locked out after a few tries, and nothing goes through the internet. It works while Prism is open or playing.
  - **Handoff**: move what's playing to another phone or tablet with Prism (the same Wi-Fi, "Available for handoff" on there), or bring theirs over: the whole queue, the song and place, shuffle and repeat. The first time, the other device shows a code to type in; after that they know each other. Songs that are files on one device stay behind. Prism has to be open (or already playing) on the device the music moves to, as Android doesn't let an app start playing from the background; if it isn't, you're told so and the music keeps playing where it was.
- **Rewind**, every year: Replay now has a chip for each year you've listened in (not only this year and last), and each year's story is its **Rewind**. This year's opens on December 1 (with a notification, which you can turn off in Settings → Library & Replay, and a Rewind card on Home through December); earlier years any time. Besides your minutes, genres, top artists and songs, habits, discoveries and personality, it has your year season by season, your top album, your biggest month, the first song you played that year, your year in Prism (songs sung, DJ sets, Smart Shuffle finds, songs recognized, Blends, new releases found) and how the year compares with the one before. Your #1 song shows a line from its best part, and every song in the story plays from its best part. A quiet year gets a short story rather than empty slides. Any slide can be shared as a picture, and "Save Top 100" makes a YouTube Music playlist of the year. Once a year is over, its Rewind is kept as it was (in your backups too), so it opens at once.
- **Playback diagnostics** (Settings → About): if music stutters, switch this on and Prism keeps a log on your phone of what the audio does: the output being set up, moments it ran dry, Bluetooth and other devices coming and going, how long Prism's own sound processing takes, and the background work running beside the music. Tap **Mark a stutter** in the player's ⋯ menu when you hear one, then **Share diagnostics** to send the log (with your phone's model and Prism's audio settings) to whoever is helping. Nothing is sent unless you share it.
- **Recognize widget** (Prism · Recognize in your launcher's widget list, or Library → Recognized → Add the widget): a round button on your home screen that opens a small listening card over whatever's on screen, without opening Prism. Made wider it also shows the last song recognized with a Play button (one row at 4×1, a card at 4×2), or that one is saved to look up once you're online. The **Recognize song** Quick Settings tile now opens the same card instead of the whole app. The microphone is only used while the card is on screen; leaving it stops listening.
- **Pick artists you like**: after the welcome screen (and any time from Settings → Home screen → Your artists), choose from YouTube Music's genres or search. They're starred on your phone (nothing is followed on YouTube), so your mixes and Release Radar have something to go on from the first day.
- **Quick picks made around your artists**: once you've a little history, Quick picks are favourites by the artists you play that you haven't heard for a few days, new songs from radios of your top three artists, and YouTube Music's own picks when they're by artists you play; no artist twice in a row. They're made once a session (pull down on Home for new ones).
- **Mini player**: a **Previous** button to go with Next (Settings → Layout & navigation → Mini player), and the **Fill** progress now fills in the split light (or White light's color).
- **Lyrics**: Settings → Lyrics → **Progress line** turns off the thin line of split light under the line being sung (on the lyrics page and in immersive mode).
- **Discord**: your status shows the song's cover (Discord takes it in through Prism's own Discord application).
- **Android Auto** shows the app icon you picked (Settings → Appearance → App icon), from the next time playback starts.
- **Songs start sooner**: Prism asks for a song's stream in one request where it can (falling back to the old way), resolves the song after next as well as the next one, and keeps background lookups out of the way while a song is loading. On Wi-Fi a song started in about 0.7 s instead of 2.5 s, and skips took about 0.2 s. The whole song is now buffered, not the next 30-60 seconds, so a dropped signal doesn't stop it.
- **Double-tap the cover's sides to seek** back or ahead (3, 5, 10 or 15 seconds; Settings → Player → Gestures) (#79).
- **Tap the time left** under the progress bar to show the song's length instead (#82).
- **Pull the mini player down** to stop playing and clear the queue (#59).
- **The queue shows what played**: the last 100 songs sit above Now playing, and the queue opens at the song playing (#68).
- **Search in Settings**: the search box on Settings' first page finds any setting and opens its page at it (#73).
- **Songs, not videos → Everywhere** (Settings → Playback & sound): a video you tap, or one coming up in the queue, plays as its song, with the video a tap away (#80).
- **Search can open the keyboard** as soon as it opens (Settings → Layout & navigation → Search) (#71).
- **Search lyrics with another title**: from the Lyrics source sheet, look a song's lyrics up under a title of your own; it's remembered for that song (#63).
- **Background vocals** show smaller under the line, lit word by word, and two karaoke lines sung at once are both lit (#65, #66).
- **Artists a song names without a link** (featured artists, often) now open, from the player and from the song's menu (#78).
- **Downloads** shows albums and playlists you've downloaded above the songs, and its "Recently added" is newest first (with "Oldest first" too) (#77).
- **Sort orders are kept**: Liked songs, Downloads, each playlist and Library's tabs remember how you last sorted them.

### Fixed
- **From GitHub issues:** removing a song from a playlist could fail with "Precondition check failed"; it now tries again with fresh details (#87). The player's cover was blurry (it was decoded at a small size), and downloads now save full-size covers (#86). An artist's "From your library" listed only the first songs, under an empty heading (#69). Speed dial's last page showed a sliver of the previous one (#67). Songs with non-Latin titles could get another song's animated cover (#58). The Discord sign-in page was blank (#72).
- **From Reddit:** Local files could spin forever when music access was given outside the page; it now checks again when you come back, and lists songs before their artwork is ready. Lyrics are faster: if a source is slow, the next one is asked too after a moment (its answer still only used in your order).
- **Tidier settings and looks**: Appearance only offers an accent color with White light (Spectrum's color is the split light), and only shows the color swatches for your own color (Now playing and Wallpaper pick theirs). The Best part tag's flame is plain white. The song title in the player is a touch smaller. Customize Home calls YouTube Music's shelves YouTube Music again. "Japanese reading data" is now "Japanese kanji readings", which says what it's for: only Japanese needs a download to spell its lyrics in Latin letters. The Quiet, Prism stripe and Aurora Replay cards read "Your top songs, artists and genres", and the Ring card follows the year by the minute (it could sit on a stale percentage while Prism stayed open). The Prism app icon's mark sits a little higher, so it looks centred in a circle.
- **Music videos are the artist's own**: some songs opened a fan's lyric video, a translated re-upload or a different song ("What You Need" by Bring Me The Horizon, "Memories" by Panic! At The Disco, "Bohemian Rhapsody"). A video now has to come from the artist's channel, their VEVO or their record label, and its title has to be the song's title, not just contain it; otherwise the song simply has no video. If fan uploads crowd out the first page, Prism asks once more for the official video.
- **Replay counts a song once**: two uploads of the same song (an album's and a single's) were listed separately in Replay, Rewind and the Replay card; their plays and time are added together now.
- **Shortcut tiles**: an album of music files on your phone showed as one tile per song; it's one tile now, and it plays the album.
- **Prism's radio skips clips**: tracks under a minute (intros, skits, sound files) no longer turn up in autoplay.
- **Best part**: its words are plain white again; the tag keeps its outline and flame in the split light.
- **Recognized**: an empty list now says what it's for, with a button to recognize a song.
- **Tapping the device under the player closed Prism**: the "Play on" sheet scrolled inside a sheet that scrolls, which Android refuses. It opens now.
- **Prism could stay "offline" with the connection back**: it read the phone's network a moment after Android announced it, and on some starts got nothing, then acted offline (no searches, offline Home) until the network changed again. It now takes the network Android hands over.
- **Home's section styles now work for every section**: Shortcut tiles, the Replay teaser, Your top genres, Moods & genres, Made for you and New from your artists ignored the style picked in Customize Home, Speed dial's List looked like its Grid, and YouTube's shelves fell back to a carousel in List unless they held only songs. Each now shows as Carousel, Grid, List or Spotlight, and Grid and List fit sections with only a few things in them (they left empty rows). Replay no longer says "1 minutes".
- **DJ mode read fast rock songs at half their tempo** (Mr. Brightside showed 74 BPM; it's about 148): only tempos above 152 are now taken for a song's eighth notes and halved.
- **The DJ queue's "Use mobile data"** quietly switched on "Measure songs on mobile data" for good; it now uses mobile data for that set only.
- **Translate lyrics did nothing, without saying why**, for a song already in your language: the player's ⋯ now says which language it translates into, and turning it on for such a song says so.
- **In light mode a light grey bar sat under the always-dark player** (three-button navigation): it's dark while the player is open.
- **Settings' Labels option showed for the Minimal tab bar**, which has no labels; it's hidden then. Smart Downloads says how much it uses in MB or GB (it read "using 0.0 of it"), Library no longer says "1 albums", and a podcast episode no longer says "Lyrics not available" under its title.
- **Pale covers washed the player out**: a white sleeve (Queen's *A Night at the Opera*) turned the player's background light grey, under white type. The colors from the cover now always sit dark enough for the player's text, in the cover, lyrics, queue and video views and Immersive mode.
- **A bass-boosted upload played as Bohemian Rhapsody's music video.** It named Queen and ran exactly as long as the song, which is what Prism looks for when YouTube links no video. Bass-boosted, re-tuned ("432 Hz"), reverb, looped, mashed-up and AI copies, and channels that post only those, are now never taken as a song's video.
- **Thriller's lyrics ran 45 seconds early.** KuGou had the single edit's lyrics listed under the album's length. Prism now reads the edition the lyrics name ("Single Version", "Live", "Radio Edit"…) and skips them for another edition, and lyrics already saved that way are fetched again.
- **Back skipped a page**: from an artist to a similar artist, from an album to another album, or from About to Open-source licenses, Back went past the page you came from. It now goes back to it.
- **Settings said Sing still needed downloading** after it had downloaded, if the download sheet was closed while it ran. Downloads now carry on when the sheet closes and always end up listed as on the phone. The sheet's download button is a proper button.
- **Downloads' "Use mobile data"** turned off Download on Wi-Fi only for good without saying so; it's now labelled **Turn off Wi-Fi only**.
- **Text was hard to read over some covers** on the Ambient background (and below the picture on album, artist and playlist pages): a white or yellow cover lit a dark page up behind white text, a black one darkened a light page behind dark text. The frost keeps the cover's colors but now always sits deep (dark mode) or pale (light mode), so text reads at 4.5:1 or better whatever the cover.
- **The Play button had two rings**: the spectrum ring is now its only edge.
- **YouTube Music's own mixes all showed the same cover** (My Supermix's). Prism keeps covers on the phone so they show offline, and it filed every mix's cover under one name, since their addresses differ only after the "?". Each mix now keeps its own, and the shared one is cleared on the first start.
- **Replay, Rewind, Blend and the mixes counted a song's whole credit as one artist** ("The Kid LAROI, Justin Bieber" as an artist of its own). A song now counts for its main artist. Songs are also stored with their exact artists, so a name with a comma in it ("Tyler, The Creator") stays whole.
- **Music files without an artist tag counted as an artist called "Unknown artist"**, with a photo of whoever YouTube has by that name, and their genre was guessed from the title alone (so a few test tones became "Dance & electronic"). They still play and show in Replay's songs, but no longer count as an artist or a genre. Wrong guesses already made are cleared on the first start.
- **Some songs played as AAC where YouTube Music plays Opus** ("Espresso", "Levitating", "Mr. Brightside" and others). YouTube's stated bitrate for the AAC stream is sometimes a hair higher than the Opus one, and Prism took the bigger number. Opus is the better codec at the same bitrate, so Prism now picks it first at every quality setting, as YouTube Music does (256 kbps AAC for Music Premium still beats 128 kbps Opus).
- **Some songs had no music video though they have one**, like "Linger (SiriusXM Session)" by Royel Otis, whose video is on SiriusXM's channel. Prism only took a video from the artist's own channel and never a "live" or "cover" one. A video from another channel now counts when it's the very same recording: the same length to within a few seconds, naming the artist, and from the same source the song names (SiriusXM here). Lyric videos, karaoke, fan edits and audio-only uploads still never count. A song found without a video is looked up again after an hour, not only after a restart.
- **A music video that YouTube refused to serve once fell straight back to the song.** It now gets one more try with fresh links (the other way, through NewPipe) first.
- **TalkBack:** with the full player open, TalkBack could still move to the screen, tabs and mini player hidden under it; they're now left out until the player closes. Immersive mode said nothing at all: it now reads the song and its lyrics, a double tap opens its settings, and its actions menu has Next song, Previous song, Leave immersive mode and Close the player (the swipes and the hold aren't possible with TalkBack). Its settings button is a proper button, with Leave immersive mode as an action.
- **An album or playlist page's ⋯** was a short dropdown without Play next, Add to queue or Play as a DJ set. It's now the same menu as a long press on the album, with the page's own actions (radio, library, downloads, picture, edit, delete) in a group under it.
- **Settings search** could open a setting that's hidden until another is on (Crossfade's Length needs Crossfade on). Those results now say what to turn on first, and open at that switch.
- **Library → Downloads** counted downloads waiting for Wi-Fi as "in progress"; only ones actually downloading count now.
- **A WAV file's quality** read "AUDIO/RAW 705 kbps" on the player; lossless files now show their format and resolution ("WAV · 16-bit · 44.1 kHz", "FLAC · 96 kHz").
- **Sing's download size** said 51 MB in one place and 76 MB in another; both now say how big the download is and how much room it takes on the phone.
- **Settings tidied:** Discord has its own page instead of the top of Settings; Local files has its title under Back like the other pages; the welcome screen keeps a phone's width on tablets; the album caption above the cover reads "From the album …" (it's the song's album, not necessarily what's playing); and Back leaves immersive mode for the normal player instead of closing it.
- **Search:** with little listening yet, "For you" and "Moods & moments" showed the same tiles (the second list now leaves out what the first shows); the Top result card has the same long-press menu as the results under it; and a hardware keyboard's Enter searches like the on-screen key.
- **"See all" grids of many artists' releases** (like YouTube's own New releases) said only "Album" or "Single" on each card; they now name the artist.
- **An album's "Other versions"** could show three identical "After Hours (Deluxe)" cards; editions with the same name now show their song count, year and whether they're explicit.
- **Two "New releases" on Home:** Prism's own section (from Release Radar) is now called **New from your artists**, so it isn't confused with YouTube's shelf.
- **Sing's engine (ONNX Runtime) has its own usage reporting, now switched off outright.** It could never start on Android (it has no way to send), so nothing was ever sent, but Prism doesn't leave that to chance.
- **"Pick artists you like" (first start):** the Done button sat under the tab bar, which now stays hidden until setup is finished; the genre chips all showed the same hip-hop artists (YouTube's genres share one page address, and Prism told them apart by that alone), and genres whose pages list only albums and playlists (Rock, Pop) came up empty. Each genre now shows its own artists, with their photos (some big names, like Drake, showed blank).
- **Bluetooth earbuds (AirPods especially) could make the music skip or judder.** Earbuds register and drop several audio devices as they work (their microphone, call channels, ear detection, the case), and each one made Prism re-point its audio output, even when nothing had changed; on Bluetooth each re-route is a short gap. Prism now only lets go of an output you picked, and only for a real new output. Headphone correction waits for the devices to settle before switching. Sing's processing no longer runs on every sample of music when Sing is off, and the song scans behind Normalize, Crossfade and DJ mode wait while the playing song is still loading.
- **DJ mode took minutes to get a set in order**, reading every song whole, one at a time. It now hears three stretches of each song, three songs at once (the key matched the whole song's for 26 of 28 test songs, and the tempo for 27). On mobile data it used to say "in key order" while it couldn't read a single key; it now says what it's doing ("reading keys… 4 of 24", "reading keys… 0 of 1, 41 wait for Wi-Fi" with a **Use mobile data** button, "10 of 24 songs in key order").
- **Daily Mixes could skip a number** ("Daily Mix 1", "Daily Mix 3") when a group of artists didn't have enough songs for a mix.

## 1.6.9.2

### Fixed
- **Discord sign-in stayed on Discord's page after signing in.** Prism now picks up the session from Discord's own sign-in answer (password, two-factor or QR code) and its requests, checks on every page rather than only once the address changes, and finds Discord's session wherever its current code keeps it.

## 1.6.9.1

### Fixed
- **Discord sign-in showed a blank page** when Discord's page couldn't load (no connection, or a network that blocks Discord, as many work and school ones do). It now says it couldn't reach Discord, with Try again.
- **Discord sign-in** looks like the phone's browser to Discord (Android's in-app browser marked itself), and reads the sign-in a second way if the first doesn't find it.

## 1.6.9

### New
- **More than one account**: Settings → "Switch account" lists every YouTube channel you can use, including several channels (brand accounts) under one email, and "Add a Google account" signs another one in beside it. Switching brings that channel's library, likes and recommendations; Replay and this phone's history stay.
- **Discord** (Settings → Discord): "Listening to Prism" on your Discord profile, with the song, artist and how far in, cleared when you pause. Off until you sign in to Discord on its own page; the sign-in stays on this phone and never goes in a backup. Discord has no official way for Android apps to do this, so it works the way other Android music players do, which Discord's terms don't allow for third-party apps: there's a small risk to your Discord account.
- **Cupertino icons** (Settings → Appearance → Icons → Pack): the most-seen icons redrawn in the style of Apple's apps (filled tabs, a chevron for Back, two-triangle skips, circled menus and downloads).
- **Remove from library** in an album's long-press menu.

### Fixed
- **Lyrics stuck on the last line** from the start of a song ("4 Steps Back, 2 Steps Forward" by Motion Sick): one source sent plain lyrics marked as synced, every line at 0:00. Those now count as plain, so a source with real timings wins.
- **Quick picks popping in and out**, and Home showing the same songs twice: Quick picks and Speed dial no longer reload each time they scroll off screen, a refresh without Quick picks keeps the last ones, and a section already shown isn't repeated.
- **Slow starts and buffering since 1.6.8**: saving playlists, albums and covers for offline, and retrying failed downloads, now wait while music is loading, and do less at a time.
- **A crash** when Prism started in the background with failed downloads waiting (Android 12 and newer).
- **Albums that wouldn't leave the library** when YouTube opened them as another edition (a remaster or deluxe).

## 1.6.8

### New
- **Speed dial** on Home (Customize Home → Add a section), as on YouTube Music's app: your go-to songs, albums, playlists and artists as big tiles, nine to a page. YouTube's own Speed dial (or Listen again) leads, filled out with what you play most. It goes in at the top (#53).
- **Quick picks start a radio**: tapping a pick plays it and then songs like it, as on YouTube Music. Customize Home → Options turns it back to playing Quick picks in order (#38).
- **Search your own music**: Search has YouTube Music and **On this phone**, which finds your music files and downloads by title, artist or album. Offline, Search starts there (#46).
- **Offline library**: your playlists, albums and artists are kept as last synced, and playlists and albums are saved with their songs when opened (and on Wi-Fi after a sync), so they open with no connection. Offline, a playlist plays its downloaded songs and shows the rest greyed out (#55).
- **Covers offline**: downloaded songs' covers (a lossless file's own embedded cover first) and your playlists' and albums' are kept on the phone, for the player and its blurred background, the notification, widgets and lists. Local files whose album has no artwork in Android show the cover inside the file (#54, #55).
- **Data saver** (Settings → Playback & sound → Mobile data): on mobile data, pictures come at the size they're shown, artist photos and animated covers wait for Wi-Fi, songs stream at your mobile data quality (at most Normal) and music videos at 480p at most (#40).
- **Songs, not videos** (Settings → Playback, on by default): autoplay and radios play music videos as their songs and leave out videos with no song version (#39).
- **Stop when swiped away** (Settings → Playback): swiping Prism out of your recent apps stops the music (#45).
- **Choose your lyrics sources**: each source in Settings → Lyrics → Sources has a switch and a line on what it's good for; Prism only asks the ones switched on (#51).
- **Save lyrics into your music files** (Settings → Lyrics, or a song's ⋯ menu): synced lyrics go into your FLAC and MP3 files' tags as LRC, so other players show them too (#16).

  > **About #16:** Prism has always left your own music files alone, so this only happens when you ask for it: nothing is written until you tap it in Settings → Lyrics or a song's menu, and on Android 11 and newer Android asks you to allow the change first. Only the lyrics tag is written; the audio and every other tag stay as they were. Each file is copied first and checked before it goes back (if anything goes wrong, even Prism closing mid-way, the original is put back). Prism still never deletes or moves your files.
- **New widgets**: Cover (2×2), Disc (2×2, with a progress ring) and Strip (4×1) (#34).
- **Local files sorting**: a sort sheet with the direction in plain words and more orders (date added, year, length, file size), plus orders for Artists and Albums (#35).
- **Every artist of a song**: a song's menu has "Go to …" for each of its artists, and tapping the artist on the player asks which one (#42).

### Fixed
- **Downloads stuck at a percentage** (#55): failed downloads now try again by themselves at launch and when you're back online, with fresh links; one that stalls restarts from where it got to; and Downloads says when they're waiting for Wi-Fi or failed, with "Use mobile data" and "Try again". Offline, songs that aren't fully on the phone are passed over instead of cutting off mid-song.
- **Music videos with the Song / Video switch off** (#48): a music video picked from a list plays as its song, and nothing switches to video.
- **Headphone volume lag** (#52): the player's volume slider only sends real changes, so Bluetooth headphones no longer fall seconds behind it.
- **Grid** for Quick picks and other song sections shows covers in two rows; it looked the same as List (#44).
- **Artist pages** (#47): the photo's colors carry on down the page and settle into its own tint instead of fading to black (album and playlist pages too). The artist's name moves with the photo as you scroll (#37).
- **The player's title** is a size larger than the artist under it; they were both 20 sp (#41).

## 1.6.7

### New
- **Your own fonts** (Settings → Appearance → Text): pick a .ttf or .otf file from your phone (Inter, SF Pro…) for the whole app, and choose a separate **lyrics font**: the app's, any built-in font, or a file of its own. Variable fonts get all their weights (#12).
- **Icon style** (Settings → Appearance → Icons): Thin, Regular or Bold icons, rounded or square. Applies after a quick restart, from the button that appears (#19).
- **App icons** (Settings → Appearance → App icon): Classic, Spectrum, Solid, Light, Wave and Midnight (#20).
- **Local files, reworked** (#29): Play, Shuffle and **Recently played** at the top, the song and album count under the title, songs sorted by title by default (the order you pick is kept), and new sections: **Favourites** (liked songs from your phone), **Recently added**, **Added albums** and **Downloads**. Tap **Edit** at the end of the bar to choose and order the sections.
- **Hide the Podcasts button** on Home (Settings → Home screen → Podcasts button); Podcasts then opens from Library (#28).

### Fixed
- **Lossless FLAC at double speed with harsh noise** on Samsung phones (#25): Samsung's FLAC decoder mishandled 16-bit (CD-quality) files in lossless mode. They're now decoded at 16-bit, which loses nothing; 24-bit files stay hi-res.
- **Searches showing up in your YouTube search history** (#27): Prism's searches, every keystroke and the lookups it makes for songs, were saved to your YouTube account. Searches no longer go out with your account.
- **Podcasts "Requested entity was not found"** (#18): in countries where YouTube Music has no Podcasts page, Prism shows your followed shows and saved episodes, and points to Search, instead of an error.
- **Phones in other languages** (Korean, Spanish and more): YouTube answers in your phone's language, and Prism misread its words for "Song", "Single", "Album", years ("2026년") and listener counts, which mixed up artists, album types and search ranking. They're understood in 20 languages now. The date on Home follows your language's order. (Prism's own screens are still in English.)

## 1.6.6

### New
- **Hold previous / next to seek** (Settings → Player → Hold to seek, on by default): holding jumps back or forward, again and again while you hold. Pick 1–20 seconds per step, or turn it off. A tap still changes songs (#21).
- **Local files as a tab** (Settings → Layout & navigation → Tabs): put Local files in the bottom bar, and open Prism on it if you like (#17).
- **Song / Video switch** (Settings → Player → On the player): turn it off to keep the player audio only (#22).
- **"Hey Google, play … on Prism"**: Google Assistant and Gemini can now pick Prism as the app to play from. "Play music on Prism" carries on with your last queue (#23).

### Fixed
- **Right-to-left lyrics on the player:** the arrow after an Arabic line now sits on its left, at the end of the line, and points left (#14).
- **Podcasts** showed "Requested entity was not found" for some signed-in accounts. Prism now loads the page as a guest when YouTube refuses it for the account (#18).
- **Closing the player:** swiping it down and pressing Back now slide it away the same way (#26).

## 1.6.5

### New
- **Immersive mode** (the player's ⋯ menu, or Settings → Player): the cover fills the whole screen, its animated version first when it has one, and the lyrics play over its lower part a line at a time, like a lyric video. Everything else hides; tap the screen for a settings button at the bottom (back to the normal player, lyrics timing and source, the song's options), or **hold it** until its ring fills to go straight back to the normal player. Swipe left or right for the next or previous song, and drag down to close the player. The cover can fill the screen, be **zoomed out** (the default) or show **whole**, over a blurred copy of itself (the settings button, or Settings → Player). The lyrics can sit **high**, in the **middle** or **low** (the default) on the screen, or run **over the whole screen**: every line, the one being sung large and bright, the list gliding along by itself.
- **Crossfade** (Settings → Playback & sound, formerly Mix) now works like Spotify's: the ending song fades out while the next one fades in, both at their own speed. **Automatic length** (on by default) works out each crossfade from how the songs end and begin: over a song's own fade-out, over the next song's quiet intro after an abrupt ending, and only briefly between two hard edges. Or set 1–12 seconds yourself. Matching tempo and beats is now an option (DJ-style), off unless you turn it on. Songs already measured are measured once more for their fade-outs and intros.
- **Local files** has its own page (Library → Playlists → Local files), with **Songs**, **Artists** and **Albums**. Artists open to their songs album by album, and albums play in track order.
- **Shuffle and repeat on the player** (Settings → Player → Shuffle and repeat buttons): either side of play, always. Repeat goes all, one, off.
- **Load ahead** (Settings → Playback & sound): besides the next song's first seconds (as before), the next song or the next three can be loaded whole while one plays, so they play without buffering even if the signal drops. On Wi-Fi, or on mobile data too if you allow it (for the next song's start as well).
- **Swipe a song** in an album or playlist (Liked songs, Downloads and Local files too): right to **play it next**, left to **add it to the end of the queue**.
- **Android Auto lyrics: Keep the song title** (Settings → Lyrics → Android Auto, with the Karaoke or Line by line style): the lyrics show only on the cover, so the car's small media card reads as usual and its full player shows the lyric card big.
- **Settings tidied:** the Player page has separate Artwork, Immersive mode and Animated covers groups, and "On the player" lists things top to bottom as they sit on the player ("Hide volume bar" is now "Volume bar"). Lyrics sources and how they're picked share one group. The same bar is called the progress bar everywhere.

### Fixed
- **The player sits higher again,** as before 1.6.4: the cover and the play, back and forward buttons had moved down. The lyrics, Song/Video and queue buttons along the bottom stay where they are, and the title still sits close to the progress bar.
- **Quick picks** showed your recent plays again: YouTube's "Listen again" / "Speed dial" shelf was taken for them. When YouTube has no picks, songs like your latest play stand in, never the songs you just played.
- **Animated covers:** after saving one song's animated cover, the album's other songs showed their still cover. Looking up covers for saved albums and downloads no longer holds up the cover of the song that's playing.
- **Songs start faster with Crossfade or Normalize volume on.** Measuring a song (which can mean fetching and decoding all of it) ran just as a song started, slowing its loading. Now the next three songs in the queue are measured (and their loudness looked up) while the current one plays, once it's under way, at low priority.
- **Normalize volume no longer jumps a few seconds into a song.** A song's volume is set as it starts; a loudness figure that only arrives later is kept for next time instead of changing the volume mid-song. Queued songs have theirs ready before they play.
- **Animated covers keep playing** while the ⋯ menu or another sheet is open over the player, instead of switching to the still cover.
- **Shuffle:** Previous went to a song that hadn't played yet. It now goes back to the song played last, including after songs are added to the queue or a song is picked from it, and Play next plays next while shuffled too.

## 1.6.4

### New
- **Cover size** (Settings → Player → Artwork): Standard, **Large** (narrow side margins, like YouTube Music) or **Full width** (the square cover runs edge to edge). With Large or Full width, a hidden volume bar or output device gives its room to the cover (#10).
- **Stream quality** (Settings → Player, where "Stats for nerds" was) shows or hides the codec and bitrate (or "Downloaded") under the progress bar. The stats line over the cover is gone; tap the label, or use the ⋯ menu, for the full stats (#8).
- **Animated covers are saved with downloads**, so downloaded songs keep their moving cover offline. "Save with downloads on mobile data" decides whether that waits for Wi-Fi. An album's songs share one saved copy when they use the album's cover.
- **Quick picks snap** a page of rows at a time when swiped, like YouTube Music (#11).

### Fixed
- **Arabic and other right-to-left lyrics** sit on the right and read right to left, and word-by-word lyrics light up from the right (#7). The line on the player is right-aligned too.
- **Time-synced lyrics from YouTube Music** (LyricFind) are used when a song has them, so songs that only had plain lyrics there now scroll in time, Arabic songs among them (#6).
- **With "Current lyric" off,** the player no longer keeps a blank line for it, and the title sits just above the controls on tall screens (#9).
- **Quick picks** showed your recent plays instead of YouTube's picks when YouTube was in another language (#11).
- **Android Auto's queue** (and the watch's and notification's) follows the shuffled order you hear, and tapping a song in it plays that song.

## 1.6.3

### New
- **Your music files.** Library → **On this phone** lists the music on your phone (MP3, M4A, FLAC, OGG, WAV…) with its own tags and album art, sorted by date added, title, artist or album, with Play and Shuffle. Songs play from the file, with normalization, EQ, Mix, lyrics and Replay. Prism asks once for access and only reads the files.
- **Smart Downloads is one storage space.** It now covers both the songs you stream (the old song cache) and the songs it downloads ahead, under one size (your old two sizes added together). When it's full, the songs you're least likely to play again make room, and liked songs are kept longest. **Smart Downloads on mobile data** lets it download without Wi-Fi, and **Clear Smart Downloads** empties it (songs you downloaded yourself stay).
- **Prism's own icons** everywhere: minimal, rounded line icons in place of Google's.
- **Search:** better top results. Abbreviations work ("adtr" finds A Day to Remember, also "rhcp", "mcr"), YouTube's own top pick is used unless it's a cover or remix you didn't ask for, a song wins over its music video, and suggestions start with your artists and recent searches.
- **Replay** lists your top 100 songs.
- Settings → About → tapping the version opens Prism's GitHub page.

### Fixed
- **Normalize volume** really evens songs out now: each song's loudness is measured on your phone (LUFS, as Spotify and YouTube use) and brought to the same level. YouTube's own figure, used before, hardly differed between loud and quiet songs. The next song's level is ready before it starts. Stats for nerds shows the measured loudness.
- **Mix** blended into silence, or not at all, on songs whose music ends before the file does (a fade-out or a silent tail): blends are now timed from where the music ends, the next song comes straight in if the ending one goes quiet, silence at the start of the next song is skipped, and a slow-loading ending gets more time. "Measure songs on mobile data" (Settings → Playback & sound → Sound) covers Mix and normalization; songs already on your phone are measured without data.
- **Scrubbing** after changing songs could jump too far (it used the first song's length).
- **The player's ⋯ menu** stuck or stuttered when pulled down on songs with animated covers.
- **"Alright" (Kendrick Lamar)** and other long explicit songs froze the player for a moment as they opened, while starred-out words were filled in; songs with long music videos also opened sluggishly.
- **Music videos with a long intro** (like "Alright") have their lyrics lined up again.
- **After a podcast,** music plays as the song again, not its video.
- **Live streams** load again when signed in.
- **Home's frosted background** follows the song that's playing (#3).
- **Search:** the search box has its background back, and a top result's play button turns into pause (#5).
- **Replay:** genre names are readable with a white accent in light mode.

## 1.6.2

Everything since 1.6.0 (1.6.1 was tagged but never released).

### New
- **Podcasts.** The round podcasts button at the top of Home, beside Customize, opens them.
  - **Customize button at the top** (Settings → Home screen) can be turned off; Podcasts then takes its place.
  - **Continue listening:** episodes you've started pick up where you left off.
  - **Your shows** and **Episodes for later** from your YouTube Music account, when signed in.
  - **Popular shows and episodes**, by category.
  - **Show pages:** newest episodes first, more as you go, save to library and share.
  - **Search:** new Podcasts and Episodes filters.
  - **Speed:** 0.75× to 2× from the player's ⋯ menu, for episodes only.
  - Episodes don't look up lyrics, don't blend with Mix, aren't followed by music radio and don't count in Replay.
- **Home screen widgets**, in four looks: Prism (frosted cover), Spotify style, Apple Music style and YouTube Music style. Each shows the cover, title, progress, previous/play/next and like; tapping the cover opens the player.
- **Content country** (Settings → Home screen → Region). Where YouTube Music isn't offered, pick a country where it is to get Home, charts and search back. Songs a label blocks in your country still won't play.
- **Mix on mobile data.** "Measure tempo on mobile data" lets beat matching work away from Wi-Fi (about 1–4 MB per song).

### Fixed
- **Pixel Watch:** previous and next sit beside play again, instead of like and shuffle.
- **Lyric timing:**
  - lyric files that shift their own timing (an `[offset]` line) were read without the shift, so every line was early or late;
  - some sources used an extended or radio edit's lyrics, which ran early or late; versions more than 8 seconds off are now turned down, and lyrics saved from those sources are checked again once;
  - while paused, lyrics no longer creep ahead and jump back.
- **Karaoke lyrics first.** Lyrics that light up word by word are now called **Karaoke** (Lyrics source shows "Karaoke", "Time-synced lines" or "Plain text"). They win over time-synced lines even when a source earlier in your order only has lines, and songs whose time-synced lyrics were saved before look for karaoke again.
- **Uncensored lyrics:** words starred out whole (`****`, as Apple Music's lyrics do) are filled in now, as in Childish Gambino's "IV. Sweatpants". Songs with a guest in the title find their full words again, and YouTube Music's or LRCLIB's lyrics fill in when Genius doesn't have the song.
- **Menus:** swiping a menu down (like the player's ⋯) could stutter, or pop back up after being flung away.
- **Player:** an interrupted swipe down could leave the player stuck part of the way down.

## 1.6.0

### New
- **Mix.** Songs blend into each other instead of stopping and starting (Settings → Playback & sound → Mix, off by default).
  - **Blend length:** 2 to 12 seconds.
  - **Match tempo and beats:** Prism measures each song's tempo on the phone, on Wi-Fi. The next song comes in on the beat and plays at the outgoing song's tempo while they overlap (pitch kept), then eases back to its own.
  - **No blend for:** an album's own tracks, live streams, music videos and Listen Together. These still play as before.
- **Translated lyrics.** A translation can show under each lyric line when a song is in another language.
  - **Where:** Settings → Lyrics, or the player's ⋯ menu.
  - **Language:** your phone's language by default, or one of 20 others.
  - **Privacy:** this is a machine translation, and the lyrics' text is sent to Google Translate.
- **"Next in queue".** Songs you add with Play next or Add to queue get their own heading in the queue. Add to queue now puts songs before autoplay's suggestions instead of after them.

### Faster
- **Songs start sooner.** Prism gets the likeliest next song ready ahead of time and, on Wi-Fi, saves its first few seconds. This covers:
  - the top search result;
  - the first song of an open album or playlist;
  - the next song in the queue, including after the queue changes or shuffle is turned on;
  - the song in the mini player when the app opens, and the one after it.
- **Results:** a song that was got ready starts in about half a second instead of over two seconds.

### Fixed
- **Back gesture animation:** the phone's back button or gesture now fades between pages like the back arrow does, instead of shrinking the page like a card.
- **Plain error messages:**
  - "You're offline", instead of technical text;
  - searching with no connection says so, instead of "Search failed";
  - a clear message when YouTube is limiting playback on the network.
- **Refused requests after signing in:** YouTube refused some requests ("Request contains an invalid argument") for some accounts and regions, such as brand accounts and es-419. Prism now retries them in a plainer form.
- **Blocked networks:** when YouTube blocks anonymous playback on a network, signed-in playback goes straight to the account's own clients for a while, instead of failing on each song first.
- **Bitrate:** the player shows each song's real average bitrate. It used to show YouTube's nominal figure (Opus always said 160 kbps).
- **Album tracks:** signed out, some albums' tracks opened as music videos. They now play as songs.
- **Switching between song and video** no longer counts as a new play in history and Replay.
- **Lyrics and queue scrolling:** the player controls no longer flicker in and out while you scroll lyrics or the queue (seen on 120 Hz phones).
- **Home:** a stale "Quick picks" row no longer flashes up while Home loads.
- **"See all" pages:** mixes and playlists now show as a two-column grid of covers, not one sideways row.
- **Fonts:** Google Sans and Rounded are greyed out, with a note, on phones that don't have Google Sans Flex. Picking them there used to do nothing.
- **Counts:** they read "1 song" and "1 artist", not "1 songs".
