package vtsen.hashnode.dev.newemptycomposeapp.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.scale
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.io.File
import java.util.concurrent.TimeUnit


// =========================================================================================
//  مدل‌های داده
// =========================================================================================
data class SongItem(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val filePath: String,
    var hasLrc: Boolean,
    var selected: Boolean = false,
)

data class LrcLibResponse(
    val id: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean? = null,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

enum class ProcessStatus { IDLE, SCANNING, FETCHING, DONE }

// =========================================================================================
//  کلاینت LRCLib
// =========================================================================================

private const val LRCLIB_USER_AGENT = "JetpackLyricsFetcher/1.0.0 (https://example.com)"

interface LrcLibApi {
    @GET("api/get")
    suspend fun getLyrics(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
        @Query("album_name") albumName: String? = null,
        @Query("duration") duration: Int? = null,
        @Header("User-Agent") userAgent: String = LRCLIB_USER_AGENT,
    ): Response<LrcLibResponse>

    @GET("api/search")
    suspend fun search(
        @Query("track_name") trackName: String? = null,
        @Query("artist_name") artistName: String? = null,
        @Header("User-Agent") userAgent: String = LRCLIB_USER_AGENT,
    ): Response<List<LrcLibResponse>>
}

object LrcLibClient {
    val api: LrcLibApi by lazy {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LrcLibApi::class.java)
    }
}

// =========================================================================================
//  توابع کمکی: پاک‌سازی نام آهنگ/هنرمند از تگ‌های سایت‌ها
// =========================================================================================

object TextCleaner {
    private val bracketRegex = Regex("""[\[\(\)\]{}<>]""")
    private val allBracketsContentRegex = Regex("""[\[\(<{][^\]\)>}]*[\]\)>}]""")
    private val tildeRegex = Regex("""~""")
    private val underscoreRegex = Regex("""_""")
    private val mentionRegex = Regex("""@\S+""")
    private val domainRegex = Regex("""\S+\.[a-zA-Z]{2,3}(?:/\S*)?(?:\s|$)""")
    private val multiSpaceRegex = Regex("""\s{2,}""")

    fun clean(raw: String): String {
        var s = raw
        // 1- حذف محتوای داخل براکت‌ها
        s = allBracketsContentRegex.replace(s, " ")
        // حذف براکت‌های باقی‌مانده
        s = bracketRegex.replace(s, " ")
        // 2- حذف ~
        s = tildeRegex.replace(s, "")
        // 3- تبدیل _ به Space
        s = underscoreRegex.replace(s, " ")
        // 4- حذف @ و هر چیزی بعدش تا Space
        s = mentionRegex.replace(s, " ")
        // 5- حذف آدرس سایت‌ها
        s = domainRegex.replace(s, " ")
        // 6- پاکسازی فاصله‌های اضافی و trim
        s = multiSpaceRegex.replace(s, " ")
        return s.trim()
    }
}

// =========================================================================================
//  ریپازیتوری: اسکن مدیااستور و مدیریت فایل‌های LRC
// =========================================================================================

object MusicRepository {


    fun copyLrcToClipboard(context: Context, song: SongItem) {
        val audioFile = File(song.filePath)
        val lrcFile = lrcFileFor(audioFile)
        if (lrcFile.exists()) {
            val text = lrcFile.readText()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("LRC", text)
            clipboard.setPrimaryClip(clip)
        }
    }

    fun scanAudioFiles(context: Context): List<SongItem> {
    val songs = mutableListOf<SongItem>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATA,
    )
    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

    context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

        while (cursor.moveToNext()) {
            val path = cursor.getString(dataCol) ?: continue
            val file = File(path)
            if (!file.exists()) continue
            val lrcFile = lrcFileFor(file)
            val rawTitle = cursor.getString(titleCol) ?: file.nameWithoutExtension
            val rawArtist = cursor.getString(artistCol) ?: ""
            var finalTitle = rawTitle.trim()
            var finalArtist = rawArtist.trim()
            if (finalArtist.isEmpty()) {
                val dashCount = finalTitle.count { it == '-' }
                if (dashCount == 1) {
                    val parts = finalTitle.split("-")
                    if (parts.size == 2) {
                        val extractedArtist = parts[0].trim()
                        val extractedTitle = parts[1].trim()    
                        if (extractedArtist.isNotEmpty() && extractedTitle.isNotEmpty()) {
                            finalArtist = extractedArtist
                            finalTitle = extractedTitle
                        }
                    }
                }
            }
            
            
            if (finalArtist.isEmpty()) {
                continue
            }
            
            songs.add(
                SongItem(
                    id = cursor.getLong(idCol),
                    title = finalTitle,
                    artist = finalArtist,
                    album = cursor.getString(albumCol)?.trim() ?: "",
                    durationMs = cursor.getLong(durationCol),
                    filePath = path,
                    hasLrc = lrcFile.exists(),
                )
            )
        }
    }
    return songs
}

    private fun lrcFileFor(audioFile: File): File =
        File(audioFile.parentFile, audioFile.nameWithoutExtension + ".lrc")

    /**
     * برای یک آهنگ، متن Lyric را از LRCLib پیدا می‌کند و کنار فایل موسیقی ذخیره می‌کند.
     * ابتدا از /api/get استفاده می‌شود؛ در صورت شکست، از /api/search.
     * اولویت با syncedLyrics است، در غیر این صورت plainLyrics.
     * خروجی true یعنی موفق بود.
     */
    suspend fun fetchAndSaveLrc(song: SongItem): Boolean {
        val cleanTitle = TextCleaner.clean(song.title)
        val cleanArtist = TextCleaner.clean(song.artist)
        val cleanAlbum = song.album.takeIf { it.isNotBlank() }?.let { TextCleaner.clean(it) }
        val durationSec = (song.durationMs / 1000).toInt().takeIf { it in 1..3600 }

        var lyrics: String? = tryGetLyrics(cleanTitle, cleanArtist, cleanAlbum, durationSec)

        if (lyrics == null) {
            lyrics = trySearchLyrics(cleanTitle, cleanArtist)
        }

        if (lyrics.isNullOrBlank()) return false

        val audioFile = File(song.filePath)
        val lrcFile = lrcFileFor(audioFile)
        return try {
            lrcFile.writeText(lyrics)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteLrc(song: SongItem): Boolean {
        val audioFile = File(song.filePath)
        val lrcFile = lrcFileFor(audioFile)
        return if (lrcFile.exists()) lrcFile.delete() else true
    }
   fun copyLrcToClipboard(context: Context, song: SongItem) {
        val lrcFile = File(File(song.filePath).parentFile, File(song.filePath).nameWithoutExtension + ".lrc")
        if (lrcFile.exists()) {
           val text = lrcFile.readText()
           val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
           val clip = android.content.ClipData.newPlainText("LRC", text)
           clipboard.setPrimaryClip(clip)
        }
    }
    /** تابع خالی برای دکمه ترجمه؛ عمداً کاری انجام نمی‌دهد. */
    fun translateLrcToPersian(song: SongItem) {
        // TODO: هنوز پیاده‌سازی نشده است.
    }

    private suspend fun tryGetLyrics(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSec: Int?,
    ): String? {
        if (trackName.isBlank() || artistName.isBlank()) return null
        val response = safeApiCall {
            LrcLibClient.api.getLyrics(
                trackName = trackName,
                artistName = artistName,
                albumName = albumName,
                duration = durationSec,
            )
        } ?: return null

        if (!response.isSuccessful) return null
        val body = response.body() ?: return null
        if (body.instrumental == true) return null
        return body.syncedLyrics?.takeIf { it.isNotBlank() } ?: body.plainLyrics?.takeIf { it.isNotBlank() }
    }

    private suspend fun trySearchLyrics(trackName: String, artistName: String): String? {
        if (trackName.isBlank()) return null
        val response = safeApiCall {
            LrcLibClient.api.search(trackName = trackName, artistName = artistName.ifBlank { null })
        } ?: return null

        if (!response.isSuccessful) return null
        val results = response.body().orEmpty()
        if (results.isEmpty()) return null

        // اولویت با نتیجه‌ای که syncedLyrics دارد
        val withSynced = results.firstOrNull { it.instrumental != true && !it.syncedLyrics.isNullOrBlank() }
        if (withSynced != null) return withSynced.syncedLyrics

        val withPlain = results.firstOrNull { it.instrumental != true && !it.plainLyrics.isNullOrBlank() }
        return withPlain?.plainLyrics
    }

    /**
     * فراخوانی امن API با احترام به Rate Limit (429 + Retry-After) و حداکثر ۳ تلاش.
     */
    private suspend fun <T> safeApiCall(call: suspend () -> Response<T>): Response<T>? {
        var attempts = 0
        while (attempts < 3) {
            try {
                val response = call()
                if (response.code() == 429) {
                    val retryAfterSec = response.headers()["Retry-After"]?.toLongOrNull() ?: 2L
                    delay(retryAfterSec * 1000)
                    attempts++
                    continue
                }
                if (response.code() == 404) return response // یافت نشد، معتبر است
                return response
            } catch (e: Exception) {
                attempts++
                delay(500)
            }
        }
        return null
    }
}

// =========================================================================================
//  ViewModel
// =========================================================================================

class MusicLyricsViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val _songs = MutableStateFlow<List<SongItem>>(emptyList())
    val songs: StateFlow<List<SongItem>> = _songs.asStateFlow()

    private val _status = MutableStateFlow(ProcessStatus.IDLE)
    val status: StateFlow<ProcessStatus> = _status.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentTotal = MutableStateFlow(0)
    val currentTotal: StateFlow<Int> = _currentTotal.asStateFlow()

    @Volatile
    private var stopRequested = false

    fun scanSongs() {
        _status.value = ProcessStatus.SCANNING
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                MusicRepository.scanAudioFiles(getApplication())
            }
            _songs.value = result
            _status.value = ProcessStatus.IDLE
        }
    }

    fun toggleSelected(songId: Long) {
        _songs.update { list ->
            list.map { if (it.id == songId) it.copy(selected = !it.selected) else it }
        }
    }

    fun selectAll(selected: Boolean) {
        _songs.update { list -> list.map { it.copy(selected = selected) } }
    }

    fun startFetchingLyrics() {
        val target = _songs.value.filter { it.selected && !it.hasLrc }
        if (target.isEmpty()) return
        stopRequested = false
        _status.value = ProcessStatus.FETCHING
        _currentTotal.value = target.size
        _currentIndex.value = 0

        viewModelScope.launch {
            for ((index, song) in target.withIndex()) {
                if (stopRequested) break
                _currentIndex.value = index + 1
                val success = withContext(Dispatchers.IO) {
                    MusicRepository.fetchAndSaveLrc(song)
                }
                if (success) {
                    _songs.update { list ->
                        list.map { if (it.id == song.id) it.copy(hasLrc = true) else it }
                    }
                }
                // فاصله‌ی زمانی مناسب بین درخواست‌ها طبق مستندات LRCLib
                delay(350)
            }
            _status.value = ProcessStatus.DONE
        }
    }

    fun stopFetching() {
        stopRequested = true
    }

    fun regenerateLrc(song: SongItem) {
        viewModelScope.launch {
            _status.value = ProcessStatus.FETCHING
            val success = withContext(Dispatchers.IO) { MusicRepository.fetchAndSaveLrc(song) }
            if (success) {
                _songs.update { list ->
                    list.map { if (it.id == song.id) it.copy(hasLrc = true) else it }
                }
            }
            _status.value = ProcessStatus.IDLE
        }
    }

    fun deleteLrc(song: SongItem) {
        val ok = MusicRepository.deleteLrc(song)
        if (ok) {
            _songs.update { list ->
                list.map { if (it.id == song.id) it.copy(hasLrc = false) else it }
            }
        }
    }
}

// =========================================================================================
//  مدیریت دسترسی‌ها (با fallback برای نسخه‌های مختلف اندروید)
// =========================================================================================

object PermissionHelper {

    fun hasFullAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
        }
    }

    /** برای API 30+ تلاش می‌کند صفحه‌ی تنظیمات اختصاصی برنامه را باز کند؛ در صورت شکست، صفحه‌ی عمومی را باز می‌کند. */
    fun buildManageStorageIntent(context: Context): Intent {
        return try {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } catch (e: Exception) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    fun legacyPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

// =========================================================================================
//  MainActivity
// =========================================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(typography = AppTypography){
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot(viewModel: MusicLyricsViewModel = viewModel()) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(PermissionHelper.hasFullAccess(context)) }
    var scanTriggered by remember { mutableStateOf(false) }

    // لانچر برای صفحه‌ی تنظیمات دسترسی کامل فایل (API 30+)
    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasPermission = PermissionHelper.hasFullAccess(context)
    }

    // لانچر برای مجوزهای runtime قدیمی/جایگزین
    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            hasPermission = PermissionHelper.hasFullAccess(context) || true
        }
    }

    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                manageStorageLauncher.launch(PermissionHelper.buildManageStorageIntent(context))
            } catch (e: Exception) {
                // fallback نهایی: تلاش برای مجوزهای رسانه‌ای معمولی
                legacyPermissionLauncher.launch(PermissionHelper.legacyPermissions())
            }
        } else {
            legacyPermissionLauncher.launch(PermissionHelper.legacyPermissions())
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission && !scanTriggered) {
            scanTriggered = true
            viewModel.scanSongs()
        }
    }

    if (!hasPermission) {
        PermissionRequestScreen(onRequestClick = { requestAccess() })
    } else {
        MainScreen(viewModel)
    }
}

@Composable
fun PermissionRequestScreen(onRequestClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "برای اسکن آهنگ‌ها و ذخیره‌ی فایل‌های متنی، دسترسی کامل به حافظه لازم است.",
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestClick) {
            Text("اعطای دسترسی")
        }
    }
}

@Composable
fun MainScreen(viewModel: MusicLyricsViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("آهنگ‌ها", "متن‌ها")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }
        when (selectedTab) {
            0 -> SongsScreen(viewModel)
            1 -> LrcListScreen(viewModel)
        }
    }
}

@Composable
fun SongsScreen(viewModel: MusicLyricsViewModel) {
    val songs by viewModel.songs.collectAsState()
    val status by viewModel.status.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val currentTotal by viewModel.currentTotal.collectAsState()
    
    // محاسبه وضعیت انتخاب همه
    val selectableSongs = songs.filter { !it.hasLrc } // آهنگ‌هایی که قابل انتخاب هستند
    val allSelected = selectableSongs.isNotEmpty() && selectableSongs.all { it.selected }
    val someSelected = selectableSongs.any { it.selected }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("تعداد کل: ${songs.size}", fontWeight = FontWeight.Bold)
                Text(
                    "قابل انتخاب: ${selectableSongs.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Switch برای انتخاب همه
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    if (allSelected) "لغو انتخاب همه" else "انتخاب همه",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Switch(
                    checked = allSelected,
                    onCheckedChange = { checked ->
                        viewModel.selectAll(checked)
                    },
                    enabled = selectableSongs.isNotEmpty(), // غیرفعال اگر آهنگی قابل انتخاب نباشد
                    modifier = Modifier.scale(0.8f) // کوچک‌تر کردن سوییچ (اختیاری)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (status) {
            ProcessStatus.SCANNING -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("در حال بررسی آهنگ‌ها...")
            }
            ProcessStatus.FETCHING -> {
                LinearProgressIndicator(
                    progress = if (currentTotal > 0) currentIndex.toFloat() / currentTotal else 0f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("در حال پردازش $currentIndex از $currentTotal")
                    TextButton(onClick = { viewModel.stopFetching() }) { Text("توقف") }
                }
            }
            else -> {
                Button(
                    onClick = { viewModel.startFetchingLyrics() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = someSelected // فقط وقتی فعال باشد که حداقل یک آهنگ انتخاب شده باشد
                ) {
                    Text(if (someSelected) "شروع دریافت متن (${songs.count { it.selected && !it.hasLrc }})" else "شروع دریافت متن")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(songs, key = { it.id }) { song ->
                SongRow(
                    song = song,
                    onToggle = { viewModel.toggleSelected(song.id) },
                )
                Divider()
            }
        }
    }
}
@Composable
fun SongRow(song: SongItem, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = song.selected, onCheckedChange = { onToggle() }, enabled = !song.hasLrc)
        Column(modifier = Modifier.weight(1f)) {
            Text(TextCleaner.clean(song.title), fontWeight = FontWeight.Medium)
            Text("${TextCleaner.clean(song.artist)}", style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(song.durationMs), style = MaterialTheme.typography.bodySmall)
        }
        if (song.hasLrc) {
            AssistChip(onClick = {}, label = { Text("متن ✓") })
        }
    }
}

@Composable
fun LrcListScreen(viewModel: MusicLyricsViewModel) {
    val songs by viewModel.songs.collectAsState()
    val withLrc = songs.filter { it.hasLrc }

    if (withLrc.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("هنوز هیچ متنی برای آهنگ ساخته نشده.")
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        items(withLrc, key = { it.id }) { song ->
            LrcRow(
        song = song,
        onRegenerate = { viewModel.regenerateLrc(song) },
        onDelete = { viewModel.deleteLrc(song) },
        onTranslate = { MusicRepository.translateLrcToPersian(song) },
        onCopyText = { MusicRepository.copyLrcToClipboard(context, song) },
            )
            Divider()
        }
    }
}

@Composable
fun LrcRow(
    song: SongItem,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onTranslate: () -> Unit,
    onCopyText: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(TextCleaner.clean(song.title), fontWeight = FontWeight.Medium)
        Text(TextCleaner.clean(song.artist), style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(4.dp))
        Row {
            TextButton(onClick = onRegenerate) { Text("به‌روزرسانی") }
            TextButton(onClick = onDelete) { Text("حذف") }
          //  TextButton(onClick = onTranslate) { Text("ترجمه") }
            TextButton(onClick = onCopyText) { Text("کپی") }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
} 