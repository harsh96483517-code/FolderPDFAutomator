package com.example.folderpdfautomator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AutomatorScreen() } }
    }
}

data class FolderJob(val folder: DocumentFile, val images: List<DocumentFile>, var status: String = "Pending")

private val imageExt = setOf("jpg", "jpeg", "png", "webp")

fun naturalCompare(a: String, b: String): Int {
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]; val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val startI = i; val startJ = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val na = a.substring(startI, i).trimStart('0').ifEmpty { "0" }
            val nb = b.substring(startJ, j).trimStart('0').ifEmpty { "0" }
            if (na.length != nb.length) return na.length.compareTo(nb.length)
            val cmp = na.compareTo(nb)
            if (cmp != 0) return cmp
        } else {
            val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (cmp != 0) return cmp
            i++; j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

@Composable
fun AutomatorScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var jobs by remember { mutableStateOf(listOf<FolderJob>()) }
    var log by remember { mutableStateOf("Select parent and output folders to begin.") }
    var running by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(0) }
    var pageProgress by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(0) }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            sourceUri = uri
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            jobs = emptyList()
            log = "Parent folder selected. Tap Scan folders."
        }
    }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            outputUri = uri
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            log = "Output folder selected."
        }
    }

    suspend fun scan() {
        val rootUri = sourceUri ?: return
        jobs = withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext emptyList()
            root.listFiles().filter { it.isDirectory && !it.name.orEmpty().startsWith(".") }
                .mapNotNull { folder ->
                    val images = folder.listFiles().filter { file ->
                        file.isFile && file.name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT) in imageExt
                    }.sortedWith { x, y -> naturalCompare(x.name.orEmpty(), y.name.orEmpty()) }
                    if (images.isNotEmpty()) FolderJob(folder, images) else null
                }
        }
        log = "Found ${jobs.size} folders and ${jobs.sumOf { it.images.size }} images."
    }

    suspend fun convert(job: FolderJob, out: DocumentFile) = withContext(Dispatchers.IO) {
        val name = (job.folder.name ?: "Folder").replace("/", "_") + ".pdf"
        val existing = out.findFile(name)
        if (existing != null) {
            job.status = "Skipped (exists)"
            return@withContext
        }
        val target = out.createFile("application/pdf", name) ?: throw FileNotFoundException("Could not create $name")
        val doc = PdfDocument()
        var successful = 0
        try {
            job.images.forEachIndexed { index, imageFile ->
                while (paused && !cancel) Thread.sleep(250)
                if (cancel) throw InterruptedException("Cancelled")
                pageProgress = index + 1
                totalPages = job.images.size
                val input = context.contentResolver.openInputStream(imageFile.uri)
                val bitmap = input?.use { BitmapFactory.decodeStream(it) }
                if (bitmap == null) {
                    log = "Skipped unreadable image: ${imageFile.name}"
                } else {
                    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, successful + 1).create()
                    val page = doc.startPage(pageInfo)
                    page.canvas.drawColor(Color.WHITE)
                    val scale = minOf(595f / bitmap.width, 842f / bitmap.height)
                    val w = bitmap.width * scale; val h = bitmap.height * scale
                    val dst = RectF((595f-w)/2f, (842f-h)/2f, (595f+w)/2f, (842f+h)/2f)
                    page.canvas.drawBitmap(bitmap, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    doc.finishPage(page)
                    bitmap.recycle()
                    successful++
                }
            }
            if (successful == 0) throw IllegalStateException("No valid images in ${job.folder.name}")
            context.contentResolver.openOutputStream(target.uri, "w")!!.use { doc.writeTo(it) }
            job.status = "Completed ($successful pages)"
        } catch (e: Exception) {
            out.findFile(name)?.delete()
            throw e
        } finally {
            doc.close()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Folder PDF Automator", style = MaterialTheme.typography.headlineMedium)
        Text("Offline • Sequential folder-to-PDF conversion")
        Spacer(Modifier.height(12.dp))
        Button(onClick = { sourcePicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Select Parent Folder") }
        Button(onClick = { outputPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Select Output Folder") }
        Button(onClick = { scope.launch { scan() } }, enabled = sourceUri != null, modifier = Modifier.fillMaxWidth()) { Text("Scan Folders") }
        Text("Folders: ${jobs.size} • Images: ${jobs.sumOf { it.images.size }}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !running && jobs.isNotEmpty() && outputUri != null, onClick = {
                running = true; cancel = false; paused = false
                scope.launch {
                    val out = DocumentFile.fromTreeUri(context, outputUri!!)
                    if (out == null) { log = "Output folder unavailable"; running = false; return@launch }
                    for ((idx, job) in jobs.withIndex()) {
                        if (cancel) break
                        current = idx + 1
                        job.status = "Processing"
                        try { convert(job, out) }
                        catch (e: InterruptedException) { job.status = "Cancelled"; break }
                        catch (e: Exception) { job.status = "Failed: ${e.message}" }
                        jobs = jobs.toList()
                    }
                    running = false
                    log = if (cancel) "Batch cancelled. Completed PDFs were preserved." else "Batch finished."
                }
            }) { Text("Start") }
            Button(enabled = running && !paused, onClick = { paused = true; log = "Paused after current page." }) { Text("Pause") }
            Button(enabled = running && paused, onClick = { paused = false; log = "Resuming…" }) { Text("Resume") }
            Button(enabled = running, onClick = { cancel = true; paused = false }) { Text("Cancel") }
        }
        Text("Folder ${current.coerceAtMost(jobs.size)} / ${jobs.size} • Page $pageProgress / $totalPages")
        LinearProgressIndicator(progress = { if (jobs.isEmpty()) 0f else current.toFloat()/jobs.size }, modifier = Modifier.fillMaxWidth())
        Text(log, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn {
            items(jobs) { job ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(job.folder.name ?: "Unnamed folder", style = MaterialTheme.typography.titleMedium)
                        Text("${job.images.size} images • ${job.status}")
                        Text("Preview: " + job.images.take(5).joinToString(", ") { it.name.orEmpty() })
                    }
                }
            }
        }
    }
}
