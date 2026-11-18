package com.resonance.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext

internal data class GenreTileData(val label: String, val count: Int, val covers: List<String>, val score: Float?)

internal fun discoveryTiles(library: LibraryState, scores: List<GenreScore>, availableAlbums: Set<String>): List<GenreTileData> {
    val tracksByLabel = linkedMapOf<String, MutableList<QqCatalogueTrack>>()
    library.favorites.distinctBy { it.mid }.forEach { track ->
        library.genres[track.mid]?.labels?.forEach { label -> tracksByLabel.getOrPut(label, ::mutableListOf).add(track) }
    }
    library.discoveries.forEach { track ->
        library.genres[track.mid]?.labels?.forEach { label -> tracksByLabel.getOrPut(label, ::mutableListOf) }
    }
    val rated = scores.filter { it.observations > 0 || it.manual != 0.0 || it.combined != 0.0 }.associateBy { it.label }
    return tracksByLabel.map { (label, tracks) ->
        val coverTracks = tracks.filter { CatalogueStore.artwork(it) != null }
            .sortedByDescending { it.albumMid in availableAlbums }.take(4)
        val covers = List(minOf(tracks.size, 4)) { index -> coverTracks.getOrNull(index)?.albumMid.orEmpty() }
        GenreTileData(label, tracks.size, covers, rated[label]?.combined?.toFloat())
    }.sortedWith(compareByDescending<GenreTileData> { it.count }.thenBy { genreName(it.label).lowercase(Locale.ROOT) }.thenBy { it.label })
}

/** Filled by the existing cover download. Grid reads stay local. */
internal object CoverThumbnails {
    private const val PixelLimit = 144
    private const val DiskLimit = 320
    private const val MemoryKiB = 3 * 1024

    private val images = object : LruCache<File, ImageBitmap>(MemoryKiB) {
        override fun sizeOf(key: File, value: ImageBitmap) = (value.width * value.height * 4 + 1023) / 1024
    }
    private val albumPattern = Regex("[A-Za-z0-9]{1,80}")
    private val written = MutableSharedFlow<File?>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun key(cacheDir: File, album: String): File? = if (albumPattern.matches(album))
        File(File(cacheDir, "cover-thumbnails"), "$album.jpg") else null

    fun memory(key: File): ImageBitmap? = images.get(key)

    fun availableAlbums(cacheDir: File): Set<String> = File(cacheDir, "cover-thumbnails").listFiles()
        .orEmpty().filter { it.extension == "jpg" }.mapTo(hashSetOf()) { it.nameWithoutExtension }

    suspend fun observe(key: File, update: (ImageBitmap?) -> Unit) {
        written.onSubscription { emit(null) }.collect { changed ->
            if (changed == null || changed == key) {
                val image = withContext(Dispatchers.IO) {
                    images.get(key) ?: BitmapFactory.decodeFile(key.path)?.asImageBitmap()?.also { images.put(key, it) }
                }
                update(image)
            }
        }
    }

    @Synchronized
    fun save(cacheDir: File, album: String, bytes: ByteArray) {
        val file = key(cacheDir, album) ?: return
        if (file.isFile) return
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
        val longest = maxOf(dimensions.outWidth, dimensions.outHeight)
        if (longest <= 0) return
        val options = BitmapFactory.Options().apply {
            while (longest / inSampleSize > PixelLimit * 2) inSampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return
        val scale = (PixelLimit.toFloat() / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
        val image = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1), true)
        val directory = file.parentFile ?: return
        if (!directory.isDirectory && !directory.mkdirs()) return
        val atomicFile = AtomicFile(file)
        val sink = try { atomicFile.startWrite() } catch (_: IOException) { return }
        try {
            if (image.compress(Bitmap.CompressFormat.JPEG, 82, sink)) atomicFile.finishWrite(sink)
            else atomicFile.failWrite(sink)
        } catch (_: IOException) {
            atomicFile.failWrite(sink)
        }
        images.put(file, image.asImageBitmap())
        written.tryEmit(file)
        directory.listFiles { entry -> entry.extension == "jpg" }
            ?.takeIf { it.size > DiskLimit }?.sortedByDescending { it.lastModified() }?.drop(DiskLimit)
            ?.forEach { it.delete() }
    }
}

@Composable
private fun Thumbnail(album: String, modifier: Modifier) {
    val dir = LocalContext.current.cacheDir
    val file = remember(dir, album) { CoverThumbnails.key(dir, album) }
    var image by remember(file) { mutableStateOf(file?.let(CoverThumbnails::memory)) }
    LaunchedEffect(file) { if (file != null) CoverThumbnails.observe(file) { image = it } }
    Box(modifier.background(ResonancePalette.surface)) {
        image?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun CoverMosaic(covers: List<String>, modifier: Modifier) {
    when (covers.size) {
        1 -> Thumbnail(covers[0], modifier)
        2 -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.progressLine)) {
            covers.forEach { Thumbnail(it, Modifier.weight(1f).fillMaxHeight()) }
        }
        3 -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.progressLine)) {
            Thumbnail(covers[0], Modifier.weight(1.4f).fillMaxHeight())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.progressLine)) {
                Thumbnail(covers[1], Modifier.fillMaxWidth().weight(1f))
                Thumbnail(covers[2], Modifier.fillMaxWidth().weight(1f))
            }
        }
        4 -> Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.progressLine)) {
            repeat(2) { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.progressLine)) {
                    Thumbnail(covers[row * 2], Modifier.weight(1f).fillMaxHeight())
                    Thumbnail(covers[row * 2 + 1], Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

private val wordBoundary = Regex("(?<=-)(?=\\S)| +")

/** Only whitespace and a visible hyphen are break opportunities. */
internal fun genreTitleLines(label: String, width: Int, measure: (String) -> Int): String {
    if (measure(label) <= width) return label
    val boundaries = wordBoundary.findAll(label)
        .map { it.range.first to (it.range.last + 1) }
        .filter { (end, start) -> end > 0 && start < label.length }
        .toList()
    fun ellipsize(line: String): String {
        if (measure(line) <= width) return line
        var end = line.length - 1
        while (end > 0 && measure(line.substring(0, end) + "…") > width) end--
        return line.substring(0, end) + "…"
    }
    val boundary = boundaries.lastOrNull { (end, _) -> measure(label.substring(0, end)) <= width }
        ?: boundaries.firstOrNull() ?: return ellipsize(label)
    return ellipsize(label.substring(0, boundary.first)) + "\n" + ellipsize(label.substring(boundary.second))
}

@Composable
internal fun GenreTile(tile: GenreTileData, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = genreName(tile.label)
    val interaction = remember { MutableInteractionSource() }
    val selectedBorder = if (selected) Modifier.border(Space.focus, ResonancePalette.signal, Corners.card) else Modifier
    Box(modifier.aspectRatio(1f).clip(Corners.card).background(ResonancePalette.plate).then(selectedBorder)
        .selectable(selected, interaction, LocalIndication.current, role = Role.Button, onClick = onClick)
        .resonancePress(interaction)) {
        if (tile.count == 0) {
            SandPlate("genre:" + tile.label, Modifier.matchParentSize(), density = 80f / 230f, opacity = .7f)
        } else CoverMosaic(tile.covers, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().drawWithCache {
            val shade = Brush.verticalGradient(
                0f to ResonancePalette.ink.copy(alpha = 0f),
                .38f to ResonancePalette.ink.copy(alpha = .80f),
                1f to ResonancePalette.ink.copy(alpha = .92f),
                startY = size.height * .40f, endY = size.height
            )
            onDrawBehind { drawRect(shade) }
        })
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(Space.x2),
            horizontalArrangement = Arrangement.spacedBy(Space.x1), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                tile.score?.let { score ->
                    ResonanceText(scoreDisplay(score), TypeSize.caption,
                        Modifier.background(ResonancePalette.ink.copy(alpha = .90f), CircleShape)
                            .padding(horizontal = Space.statusDot, vertical = Space.progressLine),
                        color = if (score < 0) ResonancePalette.signal else ResonancePalette.sand,
                        weight = FontWeight.Bold, english = true)
                }
            }
            if (tile.count > 0) ResonanceText(tile.count.toString(), TypeSize.caption,
                Modifier.height(Space.x5).background(ResonancePalette.sand, CircleShape)
                    .wrapContentHeight().padding(horizontal = Space.statusDot),
                color = ResonancePalette.ink, weight = FontWeight.ExtraBold, english = true)
        }
        BoxWithConstraints(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(Space.x2)) {
            val style = resonanceTextStyle(TypeSize.action, FontWeight.Bold, english = true).copy(lineHeight = TypeSize.genreTileLine)
            val measurer = rememberTextMeasurer()
            val width = with(LocalDensity.current) { maxWidth.roundToPx() }
            val title = remember(label, width, style, measurer) {
                genreTitleLines(label, width) { measurer.measure(AnnotatedString(it), style, softWrap = false, maxLines = 1).size.width }
            }
            Text(title, Modifier.semantics { contentDescription = label }, color = ResonancePalette.sand, style = style,
                maxLines = 2, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}
