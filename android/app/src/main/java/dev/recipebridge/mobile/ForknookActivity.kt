package dev.recipebridge.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val pine = Color(0xFF1E3F2D)
private val paper = Color(0xFFFFFCF5)
private val terracotta = Color(0xFFDC543B)

/** A share from Instagram, another app, or a local media picker. */
data class SharedRecipe(
    val text: String? = null,
    val file: Uri? = null,
    val mime: String = "",
    val sender: String = ""
)

private fun sharedDescription(share: SharedRecipe): String = when {
    share.file != null && share.mime.startsWith("video/") -> "Shared video"
    share.file != null && share.mime.startsWith("image/") -> "Shared image"
    firstSharedUrl(share.text.orEmpty()) != null -> "Shared recipe or social-media link"
    else -> "Shared recipe text"
}

class RecipeCoveActivity : ComponentActivity() {
    var incoming by mutableStateOf<SharedRecipe?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 enforces edge-to-edge. Use dark system bar icons on
        // RecipeCove cream and explicitly consume window insets in Compose.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.rgb(255, 252, 245),
                android.graphics.Color.rgb(255, 252, 245)
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.rgb(255, 252, 245),
                android.graphics.Color.rgb(255, 252, 245)
            )
        )
        window.decorView.setBackgroundColor(android.graphics.Color.rgb(255, 252, 245))
        incoming = readShare(intent)
        setContent { RecipeCoveApp(this) }
    }

    @Suppress("DEPRECATION")
    private fun readShare(intent: Intent?): SharedRecipe? {
        if (intent == null || intent.action !in listOf(
                Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW
            )) return null

        // Instagram commonly shares the Reel URL in EXTRA_TEXT, even when
        // the intent MIME type suggests media. Other apps use ClipData or
        // EXTRA_STREAM instead. Preserve whichever usable payload exists.
        val clip = intent.clipData
        val sharedText = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_HTML_TEXT),
            if (intent.action == Intent.ACTION_VIEW) intent.dataString else null,
            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString(),
            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                ?.takeIf { it.scheme in listOf("http", "https") }?.toString()
        ).firstOrNull { it.isNotBlank() }?.trim()

        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
            ?: clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                ?.takeIf { it.scheme == "content" || it.scheme == "file" }

        val mime = if (stream != null) {
            contentResolver.getType(stream).orEmpty()
                .ifBlank { intent.type.orEmpty() }
        } else intent.type.orEmpty()
        val mediaFile = stream?.takeIf {
            mime.startsWith("video/") || mime.startsWith("image/")
        }
        if (mediaFile == null && sharedText.isNullOrBlank()) return null
        return SharedRecipe(
            text = sharedText, file = mediaFile, mime = mime,
            sender = intent.`package`.orEmpty()
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = readShare(intent)
    }

    fun clearShare() { incoming = null }
}

private fun checkedServer(input: String): String {
    val uri = URI(input.trim())
    require((uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
        && uri.userInfo == null && uri.query == null && uri.fragment == null
        && (uri.path.isNullOrBlank() || uri.path == "/")) {
        "Enter your RecipeCove base URL, such as https://recipes.example.com"
    }
    if (uri.scheme == "http") {
        val host = uri.host.lowercase()
        require(host == "localhost" || host.endsWith(".local") || host.startsWith("10.")
            || host.startsWith("192.168.") || Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)) {
            "Use HTTPS outside your private network"
        }
    }
    return uri.scheme + "://" + uri.authority
}

/** Calls only the user's selected RecipeCove server. Never calls KitchenOwl directly. */
private class RecipeCoveApi(val origin: String, val token: String) {
    private fun connection(path: String, method: String, mime: String): HttpURLConnection {
        val url = URL(origin + "/api/v2" + path)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 90000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer " + token)
            if (mime.isNotBlank()) setRequestProperty("Content-Type", mime)
        }
    }

    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): Any =
        withContext(Dispatchers.IO) {
            val c = connection(path, method, if (body == null) "" else "application/json")
            try {
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
                }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val description = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                        .orEmpty().ifBlank { c.responseMessage }
                    throw IllegalStateException("HTTP " + code + ": " + description.take(300))
                }
                if (text.isBlank()) JSONObject() else JSONTokener(text).nextValue()
            } finally { c.disconnect() }
        }

    suspend fun photo(path: String): Bitmap? = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext null
        val url = if (path.startsWith("http://") || path.startsWith("https://")) path
                  else origin + (if (path.startsWith("/")) path else "/" + path)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 15000
        c.instanceFollowRedirects = false
        if (url.startsWith(origin + "/")) c.setRequestProperty("Authorization", "Bearer " + token)
        try {
            if (c.responseCode !in 200..299) return@withContext null
            c.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
        finally { c.disconnect() }
    }

    suspend fun upload(context: Context, uri: Uri, mime: String, endpoint: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val type = if (mime.startsWith("video/")) "preview-video" else "preview-image"
            val boundary = "RecipeCove" + System.nanoTime()
            val c = connection(endpoint ?: "/imports/" + type, "POST", "multipart/form-data; boundary=" + boundary)
            c.doOutput = true
            c.setChunkedStreamingMode(16384)
            // Video OCR and speech recognition can legitimately take minutes.
            c.readTimeout = if (mime.startsWith("video/")) 960_000 else 90_000
            try {
                c.outputStream.use { out ->
                    out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"shared\"\r\nContent-Type: " +
                        mime + "\r\n\r\n").toByteArray())
                    context.contentResolver.openInputStream(uri)?.use { it.copyTo(out) }
                        ?: throw IllegalArgumentException("Cannot read the shared file")
                    out.write(("\r\n--" + boundary + "--\r\n").toByteArray())
                }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException(
                    "Import HTTP " + code + ": " + runCatching { JSONObject(text).optString("detail") }.getOrDefault(text.take(200))
                )
                JSONObject(text)
            } finally { c.disconnect() }
        }
}

private fun Any?.asObject() = this as? JSONObject ?: JSONObject()
private fun Any?.asArray() = this as? JSONArray ?: JSONArray()
private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.s(key: String) = optString(key).takeUnless { it == "null" }.orEmpty()
private fun params(s: String) = URLEncoder.encode(s, "UTF-8")

@Composable
private fun RecipePhoto(api: RecipeCoveApi, image: String, modifier: Modifier = Modifier) {
    val remote = (image.startsWith("https://", ignoreCase = true) ||
                  image.startsWith("http://", ignoreCase = true)) &&
                 !image.startsWith(api.origin + "/")
    if (remote) {
        // Publisher images often redirect to a separate CDN. Coil handles those
        // redirects and caches images; no RecipeCove token is sent to publishers.
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(image).crossfade(true).build(),
            contentDescription = "Recipe photograph",
            modifier = modifier,
            contentScale = ContentScale.Crop,
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Photograph unavailable", style = MaterialTheme.typography.bodySmall)
                }
            }
        )
    } else {
        // Local photos are private and require the signed-in user's bearer token.
        val bitmap by produceState<Bitmap?>(null, api.origin, image, api.token) {
            value = api.photo(image)
        }
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = "Recipe photograph",
                modifier = modifier, contentScale = ContentScale.Crop)
        } else {
            Box(modifier, contentAlignment = Alignment.Center) {
                Text(if (image.isBlank()) "No photograph available" else "Photograph unavailable",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Input(value: String, onChange: (String) -> Unit, title: String,
                  multiline: Boolean = false, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, label = { Text(title) }, modifier = modifier.fillMaxWidth(),
        minLines = if (multiline) 3 else 1, maxLines = if (multiline) 12 else 1, singleLine = !multiline)
}

@Composable
private fun RecipeCoveApp(activity: RecipeCoveActivity) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("forknook_native", Context.MODE_PRIVATE) }
    val legacyServer = remember { context.getSharedPreferences("forknook", Context.MODE_PRIVATE).getString("server", "").orEmpty() }
    var origin by remember { mutableStateOf(prefs.getString("server", legacyServer).orEmpty()) }
    var token by remember { mutableStateOf(if (origin.startsWith("http://") && prefs.getString("http_consent_origin", "") != origin) "" else SessionVault.load(context)) }
    var username by remember { mutableStateOf(prefs.getString("username", "").orEmpty()) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var serverInput by remember { mutableStateOf(origin) }
    var pendingHttpConsent by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf<JSONObject?>(null) }
    var page by remember { mutableStateOf("Recipes") }
    var discoveredRecipe by remember { mutableStateOf<JSONObject?>(null) }
    var discoverResults by remember { mutableStateOf(JSONArray()) }
    // The state lives above the conditional page branch, so navigating into a
    // recipe and back restores the exact list item and pixel scroll offset.
    val discoverListState = rememberLazyListState()
    var discoverProvider by remember { mutableStateOf("all") }
    var discoverMode by remember { mutableStateOf("name") }
    var discoverQuery by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var listDetail by remember { mutableStateOf<JSONObject?>(null) }
    var payload by remember { mutableStateOf<Any?>(null) }
    var recipes by remember { mutableStateOf(JSONArray()) }
    var categories by remember { mutableStateOf(JSONArray()) }
    var nonce by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(setOf<Int>()) }
    var titleInput by remember { mutableStateOf("") }
    var textInput by remember { mutableStateOf("") }
    var dateInput by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var mealInput by remember { mutableStateOf("anytime") }
    var chosenRecipe by remember { mutableIntStateOf(0) }
    var chosenList by remember { mutableIntStateOf(0) }
    var roleInput by remember { mutableStateOf("contributor") }
    var catalogProviders by remember { mutableStateOf(JSONArray()) }
    var shareRetry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val api = remember(origin, token) { RecipeCoveApi(origin, token) }
    val canEdit = user?.s("role") in listOf("admin","approver","contributor")
    val isAdmin = user?.s("role") == "admin"
    val canApprove = user?.s("role") in listOf("admin","approver")

    fun request(path: String, method: String = "GET", body: JSONObject? = null, after: (Any) -> Unit = {}) {
        scope.launch {
            busy = true
            try {
                val result = api.call(path, method, body)
                message = ""
                after(result)
            } catch (e: Exception) { message = e.message ?: "Request failed" }
            finally { busy = false }
        }
    }

    fun refresh() { nonce++ }
    fun enterRecipe(recipe: JSONObject) { detail = recipe; editing = null; page = "Recipe" }
    fun openEditor(recipe: JSONObject = JSONObject()) { editing = recipe; detail = null; page = "Editor" }
    fun openList(id: Int) { chosenList = id; page = "List" }
    fun signOut() {
        token = ""; user = null; SessionVault.clear(context);page = "Recipes"
    }

    LaunchedEffect(origin, token) {
        if (origin.isNotEmpty() && token.isNotEmpty()) {
            try {
                user = api.call("/auth/me").asObject()
                recipes = api.call("/recipes").asArray()
                categories = api.call("/categories").asArray()
            } catch (e: Exception) {
                message = e.message.orEmpty()
                if (message.contains("401") || message.contains("403")) signOut()
            }
        }
    }
    LaunchedEffect(token, nonce) {
        if (token.isNotEmpty() && origin.isNotEmpty()) {
            try {
                recipes = api.call("/recipes").asArray()
                categories = api.call("/categories").asArray()
            } catch (e: Exception) { message = e.message.orEmpty() }
        }
    }
    LaunchedEffect(page, nonce, token, chosenList) {
        if (token.isNotEmpty()) {
            val path = when(page) {
                "Plan" -> "/meal-plan"
                "Discover" -> "/discovery/providers"
                "Shopping" -> "/shopping-lists"
                "List" -> "/shopping-lists/" + chosenList
                "Review" -> "/submissions?status=pending"
                "Categories" -> "/categories"
                "Users" -> "/admin/users"
                "Family" -> "/family/settings"
                else -> ""
            }
            if (path.isNotEmpty()) try {
                val result = api.call(path)
                if (page=="Discover") { catalogProviders=result.asArray() }
                else payload=result
            }
            catch (e: Exception) { message = e.message.orEmpty() }
        }
    }

    val shared = activity.incoming
    LaunchedEffect(shared, origin, token, shareRetry) {
        if (shared != null && token.isNotBlank() && origin.isNotBlank()) {
            page = "Share"
            busy = true
            message = ""
            try {
                val parsed = if (shared.file != null) {
                    api.upload(context, shared.file, shared.mime)
                } else {
                    val input = shared.text.orEmpty()
                    val link = firstSharedUrl(input)
                    if (link != null) {
                        api.call("/imports/preview-url", "POST",
                            JSONObject().put("url", link)).asObject()
                    } else {
                        api.call("/imports/preview-text", "POST",
                            JSONObject().put("text", input)).asObject()
                    }
                }
                // Import is PREVIEW-ONLY. Saving in Editor still uses the
                // family's ordinary role and approval workflow.
                activity.clearShare()
                openEditor(parsed)
            } catch (e: Exception) {
                // Keep the received content so the user can retry instead of
                // being forced to re-share from Instagram.
                message = "Could not extract the shared recipe: " +
                    e.message.orEmpty() +
                    ". Retry or dismiss the share."
            } finally {
                busy = false
            }
        }
    }

    val scheme = lightColorScheme(
        primary = terracotta, secondary = pine,
        background = paper, surface = Color.White,
        onPrimary = Color.White, onSurface = Color(0xFF20382B)
    )

    MaterialTheme(colorScheme = scheme) {
      // The entire app receives safe drawing padding exactly once.
      Surface(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        color = paper
      ) {
        if (token.isBlank() || origin.isBlank()) {
            Column(Modifier.fillMaxSize().padding(26.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center) {
                // Official RecipeCove cookbook emblem.
                Surface(color = Color.Transparent, shape = RoundedCornerShape(16.dp)) {
                    Image(
                        painter = painterResource(R.drawable.recipecove_wordmark_alpha),
                        contentDescription = "RecipeCove",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(124.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Your recipes. One place.", color = pine)
                if (activity.incoming != null) {
                    Spacer(Modifier.height(12.dp))
                    InfoCard("Shared recipe received") {
                        Text(sharedDescription(activity.incoming!!))
                        Text("Sign in to extract it; RecipeCove will keep it until you review the recipe.")
                    }
                }
                Spacer(Modifier.height(28.dp))
                Input(serverInput, { serverInput = it }, "RecipeCove server URL")
                Spacer(Modifier.height(10.dp))
                Input(username, {username = it}, "Username")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value=password,
                    onValueChange={password=it},
                    label={Text("Password")},
                    singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),
                    visualTransformation=if(showPassword) VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon={TextButton(onClick={showPassword=!showPassword}){Text(if(showPassword) "Hide" else "Show")}},
                    modifier=Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
                fun completeLogin() {
                    scope.launch {
                        busy=true
                        try {
                            val server=checkedServer(serverInput)
                            val auth=RecipeCoveApi(server,"").call("/auth/login","POST",
                                JSONObject().put("username",username).put("password",password)).asObject()
                            val session=auth.s("token")
                            require(session.isNotBlank()){"Server did not return a session"}
                            SessionVault.save(context, session)
                            prefs.edit().putString("server",server).putString("username",username).apply()
                            origin=server;token=session;user=auth.optJSONObject("user")
                            password="";message=""
                        } catch(e: Exception){message=e.message.orEmpty()}
                        finally {busy=false}
                    }
                }
                Button(onClick={
                    try {
                        val server=checkedServer(serverInput)
                        val approved=prefs.getString("http_consent_origin", "").orEmpty()
                        if(server.startsWith("http://") && approved!=server) {
                            pendingHttpConsent=true
                        } else completeLogin()
                    } catch(e: Exception){message=e.message.orEmpty()}
                },enabled=!busy,modifier=Modifier.fillMaxWidth().height(52.dp)){Text("Sign in to RecipeCove")}
                if(pendingHttpConsent) AlertDialog(
                    onDismissRequest={pendingHttpConsent=false},
                    title={Text("Unencrypted connection warning")},
                    text={Text("This RecipeCove server uses HTTP, not HTTPS. Your username, password, session token, recipes, and other data may be read or changed by anyone who can intercept your network traffic. Do not use this connection on public or untrusted networks. HTTPS is strongly recommended. By selecting Accept and connect, you agree to these risks for this server address.")},
                    confirmButton={TextButton(onClick={
                        val approved=checkedServer(serverInput)
                        prefs.edit().putString("http_consent_origin",approved).apply()
                        pendingHttpConsent=false
                        completeLogin()
                    }){Text("Accept and connect")}},
                    dismissButton={TextButton(onClick={pendingHttpConsent=false}){Text("Cancel")}}
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, color=MaterialTheme.colorScheme.error,
                    modifier=Modifier.padding(top=12.dp))
                Text("This app connects to RecipeCove directly, not KitchenOwl.",
                    modifier=Modifier.padding(top=16.dp), style=MaterialTheme.typography.bodySmall)
            }
        } else {
            BackHandler(page != "Recipes") {
                page = when(page) {"Discover recipe" -> "Discover"; "Recipe" -> "Recipes"; "Editor" -> "Recipes"; "List" -> "Shopping";else -> "Recipes"}
                detail=null;editing=null
            }
            val tabs = listOf("Recipes","Plan","Discover","Shopping","More")
            Scaffold(
              // Outer Surface already consumed the system bar insets.
              contentWindowInsets = WindowInsets(0, 0, 0, 0),
              topBar = {
                Surface(color = paper, tonalElevation = 1.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(70.dp)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Image(
                            painter = painterResource(R.drawable.recipecove_wordmark_alpha),
                            contentDescription = "RecipeCove — open recipes",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.widthIn(max=226.dp).fillMaxWidth(0.68f).height(64.dp)
                                .clickable { page = "Recipes"; detail = null; editing = null }
                        )

                    }
                }
              },
              bottomBar = {
                NavigationBar(
                    containerColor = Color.White,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = page == tab || (tab == "Discover" && page == "Discover recipe"),
                            onClick = {
                                page = tab; detail = null; editing = null; discoveredRecipe = null
                                listDetail = null; message = ""
                            },
                            icon = { Text(when(tab) {
                                "Recipes" -> "📖"; "Plan" -> "🗓"
                                "Discover" -> "🔎"; "Shopping" -> "🛒"; else -> "☰"
                            }) },
                            label = { Text(tab) }
                        )
                    }
                }
              }
            ) { inner ->
                Column(Modifier.fillMaxSize().padding(inner).padding(horizontal=14.dp, vertical=10.dp),
                    verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(message.isNotBlank()) Surface(shape=RoundedCornerShape(12.dp),
                        color=Color(0xFFFFE9E2),modifier=Modifier.fillMaxWidth().clickable{message=""}) {
                        Text(message,modifier=Modifier.padding(12.dp),color=Color(0xFF883C28))
                    }
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    when(page) {
                        "Share" -> {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text("Import shared recipe",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold)
                                if (shared != null) {
                                    InfoCard(sharedDescription(shared)) {
                                        val link = firstSharedUrl(shared.text.orEmpty())
                                        if (link != null) Text(link, style = MaterialTheme.typography.bodySmall)
                                        else Text("RecipeCove received a photo, video, or recipe text.")
                                        Text("Recipes are extracted for review. Nothing is saved until you choose Save.")
                                    }
                                }
                                if (busy) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text("Extracting ingredients and directions. Videos may take several minutes.")
                                } else if (shared != null) {
                                    Button(
                                        onClick = { shareRetry++ },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Retry recipe extraction") }
                                    OutlinedButton(
                                        onClick = {
                                            activity.clearShare()
                                            message = ""
                                            page = "Recipes"
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Dismiss share") }
                                }
                            }
                        }
                        "Recipes" -> {
                            OutlinedTextField(search,{search=it},label={Text("Search family recipes")},
                                modifier=Modifier.fillMaxWidth(),singleLine=true)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                if(canEdit) Button(onClick={openEditor()}){Text("New recipe")}
                                if(canEdit) OutlinedButton(onClick={page="Import"}){Text("Import")}
                                if(selection.isNotEmpty()) OutlinedButton(onClick={
                                    request("/shopping-lists/build","POST",
                                        JSONObject().put("recipe_ids",JSONArray(selection.toList()))
                                            .put("name","Recipes shopping list")) {
                                        selection=emptySet();page="Shopping";refresh()
                                    }
                                }){Text("Shop ("+selection.size+")")}
                            }
                            val filtered=recipes.objects().filter { (it.s("title")+" "+it.s("description"))
                                .contains(search,ignoreCase=true) }
                            LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),
                                contentPadding=PaddingValues(bottom=12.dp)) {
                                items(filtered,key={it.optInt("id")}) { recipe ->
                                    ElevatedCard(onClick={enterRecipe(recipe)},shape=RoundedCornerShape(16.dp)) {
                                        Row(Modifier.fillMaxWidth().heightIn(min=102.dp),
                                            verticalAlignment=Alignment.CenterVertically) {
                                            RecipePhoto(api,recipe.s("image_url"),
                                                Modifier.size(104.dp))
                                            Column(Modifier.weight(1f).padding(12.dp),
                                                verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                                Text(recipe.s("title"),fontWeight=FontWeight.Bold,maxLines=2)
                                                Text(recipe.s("category").ifBlank{"Family recipe"},
                                                    style=MaterialTheme.typography.labelSmall,color=pine)
                                                Text(recipe.s("description"),maxLines=2,
                                                    style=MaterialTheme.typography.bodySmall,overflow=TextOverflow.Ellipsis)
                                            }
                                            Checkbox(checked=recipe.optInt("id") in selection,onCheckedChange={checked->
                                                selection=if(checked) selection+recipe.optInt("id") else selection-recipe.optInt("id")
                                            })
                                        }
                                    }
                                }
                            }
                        }
                        "Recipe" -> {
                            val r=detail
                            if(r!=null) Column(Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                RecipePhoto(api,r.s("image_url"),
                                    Modifier.fillMaxWidth().height(250.dp))
                                Text(r.s("title"),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                                if(r.s("description").isNotBlank()) Text(r.s("description"))
                                Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                                    if(canEdit) OutlinedButton(onClick={openEditor(r)}){Text("Edit")}
                                    OutlinedButton(onClick={ request("/recipes/"+r.optInt("id")+"/state","PUT",
                                        JSONObject().put("state","want")){refresh()} }){Text("Want to make")}
                                    OutlinedButton(onClick={ request("/recipes/"+r.optInt("id")+"/state","PUT",
                                        JSONObject().put("state","made")){refresh()} }){Text("Made")}
                                }
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Button(onClick={
                                        request("/shopping-lists/build","POST",
                                            JSONObject().put("recipe_ids",JSONArray(listOf(r.optInt("id"))))
                                                .put("name",r.s("title")+" ingredients")) {
                                            page="Shopping";refresh()
                                        }
                                    }){Text("Create shopping list")}
                                    OutlinedButton(onClick={
                                        chosenRecipe=r.optInt("id");page="Add meal"
                                    }){Text("Plan meal")}
                                }
                                InfoCard("Ingredients") {
                                    val ing=r.optJSONArray("ingredients") ?: JSONArray()
                                    for(i in 0 until ing.length()) {
                                        val x=ing.opt(i)
                                        val label=if(x is JSONObject) x.s("raw").ifBlank{x.s("name")} else x.toString()
                                        Text("• "+label,modifier=Modifier.padding(vertical=3.dp))
                                    }
                                }
                                InfoCard("Directions") {
                                    val steps=r.optJSONArray("instructions") ?: JSONArray()
                                    for(i in 0 until steps.length()) {
                                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                            Text((i+1).toString()+".",color=pine,fontWeight=FontWeight.Bold)
                                            Text(steps.optString(i),modifier=Modifier.weight(1f))
                                        }
                                    }
                                }
                                if(r.s("notes").isNotBlank())InfoCard("Notes"){Text(r.s("notes"))}
                            }
                        }
                        "Editor" -> {
                            val draft=editing ?: JSONObject()
                            var editTitle by remember(editing){mutableStateOf(draft.s("title"))}
                            var editDesc by remember(editing){mutableStateOf(draft.s("description"))}
                            var editPhoto by remember(editing){mutableStateOf(draft.s("image_url"))}
                            var editIng by remember(editing){mutableStateOf((draft.optJSONArray("ingredients")?:JSONArray()).objects()
                                .joinToString("\n"){it.s("raw").ifBlank{it.s("name")}})}
                            var editSteps by remember(editing){mutableStateOf((draft.optJSONArray("instructions")?:JSONArray())
                                .let{a->(0 until a.length()).joinToString("\n"){a.optString(it)}})}
                            var editNotes by remember(editing){mutableStateOf(draft.s("notes"))}
                            Column(Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Text("Review your recipe before saving",
                                    style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                Input(editTitle,{editTitle=it},"Recipe name")
                                Input(editDesc,{editDesc=it},"Description",true)
                                Input(editIng,{editIng=it},"Ingredients, one per line",true)
                                Input(editSteps,{editSteps=it},"Instructions, one step per line",true)
                                Input(editPhoto,{editPhoto=it},"Photo URL")
                                val choosePhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri ->
                                    if (uri != null) scope.launch {
                                        busy = true
                                        try {
                                            val mediaType = context.contentResolver.getType(uri) ?: "image/jpeg"
                                            val result = api.upload(context, uri, mediaType, "/images/upload")
                                            editPhoto = result.s("image_url")
                                            message = "Recipe photograph uploaded"
                                        } catch(e: Exception) { message = e.message.orEmpty() }
                                        finally { busy = false }
                                    }
                                }
                                OutlinedButton(onClick={choosePhoto.launch("image/*")}){Text("Upload recipe photograph")}
                                Input(editNotes,{editNotes=it},"Notes",true)
                                Button(onClick={
                                    val obj=JSONObject(draft.toString())
                                    obj.put("title",editTitle.trim())
                                    obj.put("description",editDesc)
                                    obj.put("image_url",editPhoto)
                                    obj.put("notes",editNotes)
                                    obj.put("ingredients",JSONArray().apply{editIng.lines().filter{it.isNotBlank()}
                                        .forEach{put(JSONObject().put("raw",it.trim()).put("name",it.trim()))}})
                                    obj.put("instructions",JSONArray().apply{editSteps.lines().filter{it.isNotBlank()}
                                        .forEach{put(it.trim())}})
                                    val id=draft.optInt("id")
                                    request(if(id>0) "/recipes/"+id else "/recipes",
                                        if(id>0)"PUT" else "POST",obj){result->
                                        val resp=result.asObject()
                                        message=if(resp.optBoolean("pending"))"Submitted for family approval." else "Recipe saved."
                                        page="Recipes";editing=null;refresh()
                                    }
                                }, enabled=!busy && editTitle.isNotBlank(),
                                    modifier=Modifier.fillMaxWidth().height(52.dp)){Text("Save recipe")}
                                OutlinedButton(onClick={page="Recipes"},modifier=Modifier.fillMaxWidth()){Text("Cancel")}
                            }
                        }
                        "Import" -> {
                            Column(Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                InfoCard("Import a recipe") {
                                    Input(titleInput,{titleInput=it},"Recipe or video URL")
                                    Button(onClick={
                                        request("/imports/preview-url","POST",JSONObject().put("url",titleInput)){openEditor(it.asObject())}
                                    },enabled=titleInput.isNotBlank()){Text("Preview URL")}
                                    Input(textInput,{textInput=it},"Paste recipe text",true)
                                    Button(onClick={
                                        request("/imports/preview-text","POST",JSONObject().put("text",textInput)){openEditor(it.asObject())}
                                    },enabled=textInput.isNotBlank()){Text("Preview text")}
                                    val pick=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->
                                        if(uri!=null)scope.launch {
                                            busy=true
                                            try {
                                                val mime=context.contentResolver.getType(uri).orEmpty()
                                                openEditor(api.upload(context,uri,mime))
                                            } catch(e:Exception){message=e.message.orEmpty()}
                                            finally{busy=false}
                                        }
                                    }
                                    OutlinedButton(onClick={pick.launch("*/*")}){Text("Import photo or video")}
                                }
                                Text("You review every imported recipe before it is saved.",
                                    style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        "Shopping" -> {
                            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Text("Family shopping lists",style=MaterialTheme.typography.titleLarge)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Input(titleInput,{titleInput=it},"List name",modifier=Modifier.weight(1f))
                                }
                                Button(onClick={
                                    request("/shopping-lists","POST",JSONObject().put("name",titleInput.ifBlank{"Shopping list"})){
                                        titleInput="";refresh()
                                    }
                                }){Text("Create list")}
                            }
                            LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                items(payload.asArray().objects()) { list ->
                                    ElevatedCard(onClick={openList(list.optInt("id"))}) {
                                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                                            Text(list.s("name"),fontWeight=FontWeight.Bold)
                                            Text("Tap to view and check off items",
                                                style=MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                        "List" -> {
                            val list=payload.asObject()
                            Text(list.s("name"),style=MaterialTheme.typography.titleLarge)
                            var newItem by remember(chosenList){mutableStateOf("")}
                            var showHaveIt by remember(chosenList){mutableStateOf(false)}
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                OutlinedTextField(newItem,{newItem=it},label={Text("Add item")},modifier=Modifier.weight(1f))
                                Button(onClick={
                                    request("/shopping-lists/"+chosenList+"/items","POST",JSONObject().put("name",newItem)){
                                        newItem="";refresh()
                                    }
                                },enabled=newItem.isNotBlank()){Text("Add")}
                            }
                            val allItems=(list.optJSONArray("items")?:JSONArray()).objects()
                            val haveCount=allItems.count{it.optBoolean("checked")}
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("Need to buy: ${allItems.size-haveCount}",modifier=Modifier.weight(1f))
                                TextButton(onClick={showHaveIt=!showHaveIt}){
                                    Text(if(showHaveIt)"Hide Have It ($haveCount)" else "Have It ($haveCount)")
                                }
                            }
                            LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                items(allItems.filter{showHaveIt||!it.optBoolean("checked")}) { item->
                                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                        Checkbox(item.optBoolean("checked"),onCheckedChange={checked->
                                            request("/shopping-lists/"+chosenList+"/items/"+item.optInt("id"),
                                                "PATCH",JSONObject().put("name",item.s("name"))
                                                    .put("quantity",item.s("quantity")).put("checked",checked)){refresh()}
                                        })
                                        Text(item.s("name") + item.s("quantity").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(), modifier=Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        "Plan" -> {
                            Text("Plan to Make",style=MaterialTheme.typography.titleLarge)
                            Button(onClick={chosenRecipe=recipes.optJSONObject(0)?.optInt("id")?:0;page="Add meal"},
                                enabled=recipes.length()>0){Text("Plan a recipe")}
                            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                items(payload.asArray().objects()) { meal->
                                    InfoCard(meal.s("day")+" · "+meal.s("meal")) {
                                        Text(meal.s("recipe_title"))
                                        TextButton(onClick={
                                            request("/meal-plan/"+meal.optInt("id"),"DELETE"){refresh()}
                                        }){Text("Remove")}
                                    }
                                }
                            }
                        }
                        "Add meal" -> {
                            Column(Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement=Arrangement.spacedBy(12.dp)){
                                Text("Plan to Make",style=MaterialTheme.typography.titleLarge)
                                Input(dateInput,{dateInput=it},"Date (YYYY-MM-DD)")
                                Text("Meal (optional)",fontWeight=FontWeight.Bold)
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                    listOf("anytime","breakfast","lunch","dinner","snack").forEach{m->
                                        FilterChip(selected=mealInput==m,onClick={mealInput=m},label={Text(m)})
                                    }
                                }
                                Text("Choose a recipe",fontWeight=FontWeight.Bold)
                                recipes.objects().forEach{r->
                                    Row(verticalAlignment=Alignment.CenterVertically,
                                        modifier=Modifier.fillMaxWidth().clickable{chosenRecipe=r.optInt("id")}){
                                        RadioButton(chosenRecipe==r.optInt("id"),onClick={chosenRecipe=r.optInt("id")})
                                        Text(r.s("title"))
                                    }
                                }
                                Button(onClick={
                                    request("/meal-plan","POST",JSONObject().put("day",dateInput)
                                        .put("meal",mealInput).put("recipe_id",chosenRecipe)){page="Plan";refresh()}
                                },enabled=chosenRecipe>0){Text("Add to Plan to Make")}
                            }
                        }
                        "Review" -> {
                            Text(if(canApprove)"Recipes awaiting approval" else "My recipe requests",
                                style=MaterialTheme.typography.titleLarge)
                            LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){
                                items(payload.asArray().objects()){item->
                                    InfoCard(item.s("title")) {
                                        Text("Submitted by "+item.s("requested_by"))
                                        val data=item.optJSONObject("data")
                                        Text(data?.s("description").orEmpty(),maxLines=3)
                                        if(canApprove)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                            Button(onClick={request("/submissions/"+item.optInt("id")+"/approve",
                                                "POST",JSONObject().put("reason","Approved")){refresh()}}){Text("Approve")}
                                            OutlinedButton(onClick={request("/submissions/"+item.optInt("id")+"/reject",
                                                "POST",JSONObject().put("reason","Not selected for our family collection")){refresh()}}){
                                                Text("Decline")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "Discover" -> {
                            Text("Discover recipes", style = MaterialTheme.typography.titleLarge)
                            Input(discoverQuery, { discoverQuery = it }, "Recipe name or ingredient")
                            Row(Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                catalogProviders.objects().forEach { source ->
                                    val id = source.s("id")
                                    FilterChip(selected=discoverProvider==id,
                                        onClick={ discoverProvider=id },
                                        label={ Text(source.s("name")) })
                                }
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                listOf("name","ingredient").forEach { kind ->
                                    FilterChip(selected=discoverMode==kind,
                                        onClick={ discoverMode=kind },
                                        label={Text(if(kind=="name") "By name" else "By ingredient")})
                                }
                            }
                            Button(
                                onClick={
                                    request("/discovery/search?provider="+params(discoverProvider)+
                                        "&mode="+params(discoverMode)+"&q="+params(discoverQuery)) { response ->
                                        discoverResults=response.asObject().optJSONArray("items")?:JSONArray()
                                        scope.launch { discoverListState.scrollToItem(0) }
                                    }
                                },
                                enabled=discoverQuery.trim().length>=2 && !busy,
                                modifier=Modifier.fillMaxWidth()
                            ){ Text("Search recipes") }
                            if(discoverResults.length()==0 && !busy) {
                                Text("Search a recipe name or ingredient to explore recipes from available sources.",
                                    color=pine,style=MaterialTheme.typography.bodyMedium)
                            }
                            LazyColumn(modifier=Modifier.weight(1f),
                                state=discoverListState,
                                verticalArrangement=Arrangement.spacedBy(10.dp),
                                contentPadding=PaddingValues(bottom=16.dp)) {
                                items(discoverResults.objects(),
                                    key={ item -> item.s("provider")+":"+item.s("id") }){ item ->
                                    ElevatedCard(
                                        onClick={
                                            val resultProvider=item.s("provider").ifBlank { discoverProvider }
                                            request("/discovery/preview?provider="+params(resultProvider)+
                                                "&id="+params(item.s("id"))) { response ->
                                                discoveredRecipe=response.asObject()
                                                page="Discover recipe"
                                            }
                                        },
                                        modifier=Modifier.fillMaxWidth(),
                                        shape=RoundedCornerShape(16.dp)
                                    ) {
                                        Row(Modifier.fillMaxWidth().padding(12.dp),
                                            horizontalArrangement=Arrangement.spacedBy(12.dp),
                                            verticalAlignment=Alignment.CenterVertically) {
                                            RecipePhoto(api,item.s("image_url"),
                                                Modifier.size(92.dp))
                                            Column(Modifier.weight(1f),
                                                verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                                Text(item.s("title").ifBlank{item.s("name")},
                                                    fontWeight=FontWeight.Bold,maxLines=3,
                                                    overflow=TextOverflow.Ellipsis)
                                                Text(catalogProviders.objects()
                                                    .firstOrNull{it.s("id")==item.s("provider")}
                                                    ?.s("name")?:item.s("provider"),
                                                    color=pine,style=MaterialTheme.typography.labelSmall)
                                                Text("View full recipe",color=pine,
                                                    style=MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "Discover recipe" -> {
                            val draft=discoveredRecipe
                            if(draft==null) {
                                Text("Recipe preview is no longer available.")
                                OutlinedButton(onClick={page="Discover"}) {Text("Back to search")}
                            } else {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                    verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                    OutlinedButton(onClick={page="Discover"}) {Text("← Back to results")}
                                    RecipePhoto(api,draft.s("image_url"),
                                        Modifier.fillMaxWidth().height(240.dp))
                                    Text(draft.s("title").ifBlank{"Recipe"},
                                        style=MaterialTheme.typography.headlineMedium,
                                        fontWeight=FontWeight.Bold)
                                    if(draft.s("description").isNotBlank())
                                        Text(draft.s("description"))
                                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                        if(draft.s("servings").isNotBlank())
                                            Text("Servings: "+draft.s("servings"))
                                        if(draft.optInt("prep_time",0)>0)
                                            Text("Prep: "+draft.optInt("prep_time")+" min")
                                    }
                                    InfoCard("Ingredients") {
                                        val ing=draft.optJSONArray("ingredients")?:JSONArray()
                                        if(ing.length()==0) Text("No ingredients provided; review before saving.")
                                        for(i in 0 until ing.length()) {
                                            val ingredient=ing.opt(i)
                                            val description=if(ingredient is JSONObject)
                                                ingredient.s("raw").ifBlank{ingredient.s("name")}
                                                else ingredient.toString()
                                            Text("• "+description,
                                                modifier=Modifier.padding(vertical=3.dp))
                                        }
                                    }
                                    InfoCard("Directions") {
                                        val steps=draft.optJSONArray("instructions")?:JSONArray()
                                        if(steps.length()==0) Text("No directions provided; review before saving.")
                                        for(i in 0 until steps.length()){
                                            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                                Text("${i+1}.",color=pine,fontWeight=FontWeight.Bold)
                                                Text(steps.optString(i),Modifier.weight(1f))
                                            }
                                        }
                                    }
                                    Text("Nothing is saved until you review and save this recipe.",
                                        style=MaterialTheme.typography.bodySmall,color=pine)
                                    Button(onClick={
                                        discoveredRecipe=null
                                        openEditor(draft)
                                    },modifier=Modifier.fillMaxWidth()){Text("Import and review recipe")}
                                    OutlinedButton(onClick={page="Discover"},
                                        modifier=Modifier.fillMaxWidth()){Text("Back to results")}
                                }
                            }
                        }
                        "Categories" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            items(categories.objects()){cat->
                                InfoCard(cat.s("name")) {Text("Organize your family's recipes")}
                            }
                        }
                        "Family" -> {
                            val settings=payload.asObject()
                            Text("Family recipe approvals",style=MaterialTheme.typography.titleLarge)
                            if(isAdmin) InfoCard("Require approval for contributions") {
                                Switch(settings.optBoolean("require_approval"),onCheckedChange={v->
                                    request("/admin/family/settings","PUT",JSONObject().put("require_approval",v)){refresh()}
                                })
                            }
                            InfoCard("Request approval for my submissions") {
                                Switch(settings.optBoolean("my_submit_for_review"),onCheckedChange={v->
                                    request("/me/preferences","PUT",JSONObject().put("submit_for_review",v)){refresh()}
                                })
                            }
                        }
                        "Users" -> {
                            Text("Family accounts",style=MaterialTheme.typography.titleLarge)
                            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                items(payload.asArray().objects()){person->
                                    InfoCard(person.s("display_name")){
                                        Text(person.s("username")+" · "+person.s("role"))
                                    }
                                }
                            }
                        }
                        "Add user" -> {
                            var newDisplay by remember{mutableStateOf("")}
                            var newUsername by remember{mutableStateOf("")}
                            var newPassword by remember{mutableStateOf("")}
                            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                                Input(newDisplay,{newDisplay=it},"Display name")
                                Input(newUsername,{newUsername=it},"Username")
                                Input(newPassword,{newPassword=it},"Temporary password (12+ characters)")
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                    listOf("approver","contributor","viewer").forEach{role->
                                        FilterChip(selected=roleInput==role,onClick={roleInput=role},label={Text(role)})
                                    }
                                }
                                Button(onClick={request("/admin/users","POST",
                                    JSONObject().put("display_name",newDisplay).put("username",newUsername)
                                        .put("password",newPassword).put("role",roleInput)){page="Users";refresh()}},
                                    enabled=newUsername.length>=3&&newPassword.length>=12){Text("Create family account")}
                            }
                        }
                        "More" -> {
                            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                                val links=mutableListOf("Import" to "Import recipes, photos and videos",
                                    "Categories" to "Browse categories",
                                    "Review" to "Family recipe requests","Family" to "Approval preferences")
                                if(isAdmin){links.add("Users" to "Manage family accounts");links.add("Add user" to "Create a family account")}
                                links.forEach{(name,desc)->
                                    ElevatedCard(onClick={page=name;payload=null}) {
                                        Column(Modifier.fillMaxWidth().padding(18.dp)){
                                            Text(name,fontWeight=FontWeight.Bold)
                                            Text(desc,style=MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                InfoCard("Share recipes from other apps") {
                                    Text("In Instagram or Facebook, tap Share, then choose RecipeCove from your phone's Share sheet. If it isn't in the first row, tap More.")
                                    Text("RecipeCove can receive post/Reel links and accessible image or video files. You'll review the extracted recipe before saving.")
                                    Text("Private posts, restricted videos or unsupported links may not be extractable; you can retry or paste a link in Import.")
                                }
                                OutlinedButton(onClick={
                                    scope.launch {
                                        runCatching { api.call("/auth/logout","POST") }
                                        signOut()
                                    }
                                },modifier=Modifier.fillMaxWidth()){Text("Sign out")}
                                Text("RecipeCove v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName,
                                    style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
