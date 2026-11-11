package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.PrismIcons
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.testTag
import kotlin.math.cos
import kotlin.math.sin

/**
 * Signs in through Google's own web sign-in page. Prism never sees your
 * password — it only keeps the resulting YouTube Music session cookies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit, onBack: () -> Unit, addAccount: Boolean = false) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(0f) }
    var finishing by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf(false) }
    // Adding an account goes through Google's "add another account", which keeps the ones already signed in on this
    // web session. Signed in with a pasted cookie, there's no such session here: it's then an ordinary sign-in.
    val adding = remember {
        addAccount && CookieManager.getInstance().getCookie("https://music.youtube.com")?.contains("SAPISID=") == true
    }
    /** While adding: Google's pages have been shown, so landing back on YouTube Music means the account is in. */
    var sawGoogle by remember { mutableStateOf(false) }

    /** The session now holds one more Google account: kept, and the account list opens from Settings. */
    fun completeAdd(cookie: String) {
        if (finishing) return
        finishing = true
        scope.launch {
            CookieManager.getInstance().flush()
            val s = c.settings.current
            c.settings.saveIdentity(s.authUser, s.dataSyncId, cookie)
            android.widget.Toast.makeText(
                c.app, tr(R.string.account_added_pick_its_channel_under), android.widget.Toast.LENGTH_LONG,
            ).show()
            onBack()
        }
    }

    fun complete(cookie: String) {
        if (finishing) return
        // The web page goes away as soon as the session exists, so nobody is left on YouTube's site.
        finishing = true
        scope.launch {
            CookieManager.getInstance().flush()
            val (visitor, dataSync) = withTimeoutOrNull(10_000) { c.innerTube.sessionInfo(cookie) } ?: (null to null)
            c.settings.saveAccount(cookie, visitor.orEmpty(), dataSync.orEmpty())
            c.settings.setOnboarded(true)
            // Wait until the saved cookie is what requests will actually use,
            // otherwise the first account/library calls go out signed-out.
            c.settings.flow.first { it.cookie == cookie }
            // Name, picture and library fill in once Prism is open; no need to hold sign-in up for them.
            c.scope.launch {
                val info = withTimeoutOrNull(15_000) { runCatching { c.ytm.accountInfo() }.getOrNull() }
                if (info != null) c.settings.saveAccountInfo(info.name, info.email, info.avatar)
                c.library.syncInBackground()
            }
            onDone()
        }
    }

    /** Signed in once the YouTube session cookies exist, whatever page the WebView is on. */
    fun checkCookies() {
        // Adding: the cookies are there from the start; [completeAdd] waits for the way back to YouTube Music.
        if (finishing || adding) return
        val cm = CookieManager.getInstance()
        val cookie = cm.getCookie("https://music.youtube.com") ?: cm.getCookie("https://www.youtube.com") ?: return
        if (cookie.contains("SAPISID=") || cookie.contains("__Secure-3PAPISID=")) complete(cookie)
    }

    LaunchedEffect(Unit) {
        while (isActive && !finishing) {
            checkCookies()
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(tr(R.string.sign_in_to_youtube_music)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(PrismIcons.ArrowBack, tr(R.string.back)) } },
            actions = { PrismTextButton(onClick = { pasteDialog = true }) { Text(tr(R.string.use_cookie)) } },
        )
        if (finishing) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                com.prism.music.ui.components.PrismSpinner(Modifier.size(30.dp))
                Spacer(Modifier.height(20.dp))
                Text(tr(R.string.signing_you_in_to_prism), style = MaterialTheme.typography.titleMedium)
            }
            return@Column
        }
        if (progress < 1f) com.prism.music.ui.components.PrismProgressBar({ progress }, Modifier.fillMaxWidth(), height = 2.dp)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.stopLoading(); it.destroy() },
            factory = { ctx ->
                WebView(ctx).apply {
                    // Fills the box (see DiscordLoginScreen: a WRAP_CONTENT WebView measures viewport units as 0).
                    layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Google blocks sign-in from user agents flagged as embedded web views.
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                            checkCookies()
                            if (finishing) return true
                            // Keep everything inside this WebView; app-link schemes
                            // (intent://, vnd.youtube://) would otherwise dead-end.
                            val scheme = request.url.scheme ?: return false
                            if (scheme != "http" && scheme != "https") {
                                if (view.url?.contains("music.youtube.com") != true) view.loadUrl("https://music.youtube.com/")
                                return true
                            }
                            return false
                        }

                        // Catch the session the moment a redirect sets it, before YouTube's page draws.
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { checkCookies() }
                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { checkCookies() }

                        override fun onPageFinished(view: WebView, url: String) {
                            checkCookies()
                            if (finishing) return
                            val host = android.net.Uri.parse(url).host.orEmpty()
                            if (adding) {
                                if (host == "accounts.google.com") sawGoogle = true
                                else if (sawGoogle && host == "music.youtube.com") {
                                    CookieManager.getInstance().getCookie("https://music.youtube.com")?.let(::completeAdd)
                                }
                                return
                            }
                            val cm = CookieManager.getInstance()
                            // Signed in, but left on a YouTube or Google page that isn't Music
                            // (youtube.com's home, Google's account page): hop to Music so its session cookies get set.
                            val strayYouTube = host.endsWith("youtube.com") && host != "music.youtube.com" &&
                                cm.getCookie("https://www.youtube.com")?.contains("SID=") == true
                            val strayGoogle = (host == "myaccount.google.com" || host == "www.google.com") &&
                                cm.getCookie("https://accounts.google.com")?.contains("SID=") == true
                            if (strayYouTube || strayGoogle) view.loadUrl("https://music.youtube.com/")
                        }
                    }
                    loadUrl(
                        if (adding) "https://accounts.google.com/AddSession?continue=https%3A%2F%2Fmusic.youtube.com%2F&service=youtube"
                        else "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F&service=youtube&passive=true",
                    )
                }
            },
        )
    }

    if (pasteDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasteDialog = false },
            title = { Text(tr(R.string.sign_in_with_a_cookie)) },
            text = {
                Column {
                    Text(
                        tr(R.string.if_google_blocks_the_embedded_sign),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    com.prism.music.ui.components.PrismTextField(text, { text = it }, Modifier.fillMaxWidth().height(140.dp), placeholder = { Text(tr(R.string.sapisid_secure_3papisid)) })
                }
            },
            confirmButton = {
                PrismTextButton(enabled = text.contains("SAPISID"), onClick = { pasteDialog = false; complete(text.trim()) }) { Text(tr(R.string.sign_in)) }
            },
            dismissButton = { PrismTextButton(onClick = { pasteDialog = false }) { Text(tr(R.string.cancel)) } },
        )
    }
}

/**
 * Signs in to Discord on Discord's own page, for "Listening to Prism" (Settings → Discord). Prism never sees the
 * password: once Discord's web app is open, it reads the session Discord keeps for that page, then leaves. When the
 * page can't load (no connection, or a network that blocks Discord, as some work and school ones do) it says so,
 * with Try again, rather than showing an empty page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiscordLoginScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var done by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    /** Why Discord's page didn't load, when it didn't. */
    var failed by remember { mutableStateOf<String?>(null) }

    fun finish(token: String) {
        if (done || token.isBlank()) return
        done = true
        c.discord.signIn(token)
        c.settings.setDiscordPresence(true)
        // The page's own copy goes; Prism keeps the session in its own storage (never in backups).
        android.webkit.WebStorage.getInstance().deleteOrigin("https://discord.com")
        onBack()
    }

    LaunchedEffect(Unit) {
        while (isActive && !done) {
            delay(1_000)
            val view = web ?: continue
            if (view.url?.contains("discord.com") != true) continue
            // Asked on every page: Discord signs in without leaving /login (the app draws in place), and with
            // two-factor or a QR code the address can lag behind; no session simply comes back empty.
            view.evaluateJavascript(DISCORD_HOOK_JS, null)
            view.evaluateJavascript(DISCORD_TOKEN_JS) { result ->
                val token = runCatching {
                    com.prism.music.data.innertube.InnerTube.json.parseToJsonElement(result)
                        .let { (it as kotlinx.serialization.json.JsonPrimitive).content }.trim('"')
                }.getOrNull()
                if (!token.isNullOrBlank()) finish(token)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(tr(R.string.sign_in_to_discord)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(PrismIcons.ArrowBack, tr(R.string.back)) } },
        )
        if (progress < 1f && failed == null) com.prism.music.ui.components.PrismProgressBar({ progress }, Modifier.fillMaxWidth(), height = 2.dp)
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.stopLoading(); it.destroy() },
                factory = { ctx ->
                    if (com.prism.music.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                    WebView(ctx).apply {
                        // A WebView left at AndroidView's WRAP_CONTENT takes its page height from its content, so a page
                        // sized in viewport units (100vh) measures 0: Discord's login drew nothing (#72). It fills the box.
                        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // As Chrome on the phone: Android's in-app browser marks itself ("; wv", "Version/4.0"), which
                        // sites can treat as an embedded sign-in and refuse.
                        settings.userAgentString = settings.userAgentString
                            .replace(Regex(";\\s*wv\\)"), ")").replace(Regex("\\s*Version/\\d+(\\.\\d+)*"), "")
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
                        }
                        webViewClient = object : WebViewClient() {
                            // Discord's links to its apps (discord://) would dead-end here.
                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                                request.url.scheme != "http" && request.url.scheme != "https"

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { failed = null }

                            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                                if (request.isForMainFrame) failed = error.description?.toString().orEmpty()
                            }

                            override fun onReceivedHttpError(view: WebView, request: android.webkit.WebResourceRequest, response: android.webkit.WebResourceResponse) {
                                if (request.isForMainFrame && response.statusCode >= 400) failed = tr(R.string.discord_answered_http, response.statusCode)
                            }

                            // A load the network cut off can end without an error, on an empty page: say so too.
                            override fun onPageFinished(view: WebView, url: String) {
                                if (done) return
                                // Listen to Discord's own requests before anyone types: the sign-in answer carries the session.
                                view.evaluateJavascript(DISCORD_HOOK_JS, null)
                                view.evaluateJavascript(tr(R.string.document_documentelement_innerhtml_length)) { len ->
                                    if ((len.toIntOrNull() ?: 0) < 200 && failed == null) failed = tr(R.string.the_page_came_back_empty)
                                }
                            }
                        }
                        loadUrl(DISCORD_LOGIN_URL)
                        web = this
                    }
                },
            )
            failed?.let { why ->
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(tr(R.string.couldn_t_reach_discord), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        tr(R.string.check_your_connection_some_networks_such_all),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (why.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(20.dp))
                    PrismButton(onClick = { failed = null; progress = 0f; web?.loadUrl(DISCORD_LOGIN_URL) }) { Text(tr(R.string.try_again)) }
                }
            }
        }
    }
}

private const val DISCORD_LOGIN_URL = "https://discord.com/login"

/**
 * The signed-in session from Discord's web app. Discord hides `localStorage` from its own window once it's open, but a
 * fresh frame on the page can still read the token there; failing that, it's asked of Discord's own code (the
 * module with `getToken`), the way other Android players sign in to Discord.
 */
private val DISCORD_TOKEN_JS = """
    (function(){
      var t=window.__prismToken||'';
      if(!t){
      try{var f=document.createElement('iframe');f.style.display='none';document.body.appendChild(f);
        t=JSON.parse(f.contentWindow.localStorage.getItem('token')||'""')||'';f.remove();}catch(e){}}
      if(!t){try{
        var mods=[];
        window.webpackChunkdiscord_app.push([[Symbol()],{},function(r){for(var k in r.c)mods.push(r.c[k]);}]);
        window.webpackChunkdiscord_app.pop();
        for(var i=0;i<mods.length&&!t;i++){
          var x=mods[i]&&mods[i].exports; if(!x)continue;
          var cands=[x];try{for(var key in x)cands.push(x[key]);}catch(e){}
          for(var j=0;j<cands.length;j++){var o=cands[j];
            try{if(o&&typeof o.getToken==='function'){var v=o.getToken();if(typeof v==='string'&&v){t=v;break;}}}catch(e){}}
        }
      }catch(e){}}
      return typeof t==='string'?t:'';
    })()
""".trimIndent()

/**
 * Keeps the session Discord's page itself hands out: the `token` in the answer to signing in (password, two-factor or
 * QR code) and the `Authorization` its later requests carry. Installed once per page; Discord stays on one page.
 */
private val DISCORD_HOOK_JS = """
    (function(){
      if(window.__prismHooked)return; window.__prismHooked=true;
      function keep(v){if(typeof v==='string'&&v.length>20&&v.indexOf(' ')<0)window.__prismToken=v;}
      function read(txt){try{var j=JSON.parse(txt);if(j&&j.token)keep(j.token);}catch(e){}}
      var X=XMLHttpRequest.prototype, open=X.open, send=X.send, set=X.setRequestHeader;
      X.setRequestHeader=function(k,v){if(String(k).toLowerCase()==='authorization')keep(v);return set.apply(this,arguments);};
      X.open=function(m,u){this.__u=String(u);return open.apply(this,arguments);};
      X.send=function(){var x=this;if(/\/auth\/|\/login/.test(x.__u||''))x.addEventListener('load',function(){read(x.responseText);});
        return send.apply(this,arguments);};
      var f=window.fetch; if(f)window.fetch=function(i,o){
        try{var h=o&&o.headers;if(h)keep(typeof h.get==='function'?h.get('Authorization'):(h.Authorization||h.authorization));}catch(e){}
        var p=f.apply(this,arguments), u=String(i&&i.url||i);
        if(/\/auth\/|\/login/.test(u))p.then(function(r){r.clone().text().then(read);}).catch(function(){});
        return p;};
    })()
""".trimIndent()

@Composable
fun WelcomeScreen(onSignIn: () -> Unit, onAccount: () -> Unit, onSkip: () -> Unit) {
    val t = rememberInfiniteTransition(label = "welcome")
    val a by t.animateFloat(0f, (Math.PI * 2).toFloat(), infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "a")
    Box(Modifier.fillMaxSize().background(Color(0xFF070707))) {
        Canvas(Modifier.fillMaxSize()) {
            val cols = listOf(Color(0xFF787878), Color(0xFF797979), Color(0xFF828282), Color(0xFFBCBCBC))
            cols.forEachIndexed { i, col ->
                val ph = a + i * 1.6f
                val p = Offset(size.width * (0.5f + 0.35f * cos(ph)), size.height * (0.35f + 0.22f * sin(ph * 1.3f)))
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.55f), Color.Transparent), p, size.width * 0.7f), size.width * 0.7f, p)
            }
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF070707)), startY = size.height * 0.35f))
        }
        // A phone's width in the middle on a tablet, rather than buttons stretched across the screen. It scrolls: with
        // large text or display size the list pushed the buttons off the bottom and new listeners couldn't go on (1.7.9.3,
        // owner's report). When it doesn't fit, it opens scrolled to the buttons; the rest is a swipe up.
        val scroll = androidx.compose.foundation.rememberScrollState()
        LaunchedEffect(scroll.maxValue) { if (scroll.maxValue > 0 && scroll.value == 0) scroll.scrollTo(scroll.maxValue) }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.align(Alignment.TopCenter).widthIn(max = 480.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight).padding(28.dp).testTag("welcome"),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(tr(R.string.app_name), color = Color.White, style = MaterialTheme.typography.displayLarge)
            Text(tr(R.string.your_music_refracted), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(28.dp))
            Feature(PrismIcons.Lyrics, tr(R.string.karaoke_lyrics_word_by_word_from))
            Feature(PrismIcons.Mic, tr(R.string.sing_the_song_with_its_voice))
            Feature(PrismIcons.StarFilled, tr(R.string.made_for_you_daily_mixes_release))
            Feature(PrismIcons.Equalizer, tr(R.string.dj_mode_your_songs_mixed_in))
            Feature(PrismIcons.Recognize, tr(R.string.name_the_song_playing_around_you))
            Feature(PrismIcons.Replay, tr(R.string.rewind_your_year_in_music))
            Spacer(Modifier.height(32.dp))
            // A Prism Account first: it's all on this phone, no sign-in anywhere. Google's for the YouTube Music library.
            PrismButton(onClick = onAccount, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                Text(tr(R.string.make_a_prism_account), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Text(
                tr(R.string.kept_on_this_phone_no_email),
                color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            com.prism.music.ui.components.PrismOutlinedButton(onClick = onSignIn, Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) {
                Text(tr(R.string.sign_in_with_google), color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            PrismTextButton(onClick = onSkip, Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(tr(R.string.skip_for_now), color = Color.White.copy(alpha = 0.8f))
            }
            WelcomeRestoreButton()
        }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}
