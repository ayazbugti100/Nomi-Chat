package com.example.ui.screens

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.data.model.ChatThread
import com.example.data.model.StoryDrawStroke
import com.example.data.model.StoryFontStyle
import com.example.data.model.StoryItem
import com.example.data.model.StoryOverlayCodec
import com.example.data.model.StoryStickerOverlay
import com.example.data.model.StoryViewRecord
import com.example.ui.components.AvatarCircle
import com.example.ui.components.computeImageCacheKey
import com.example.ui.components.resolveImageModel
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.EmeraldMid
import com.example.ui.theme.MintOnlineGlow
import com.example.ui.theme.toFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class EditorToolMode {
    NONE,
    MUSIC,
    CROP_STICKER,
    TEXT,
    DRAW
}

private val STORY_COLOR_SWATCHES = listOf(
    0xFFFFFFFFL,
    0xFF00E676L,
    0xFFFFD600L,
    0xFFFF4081L,
    0xFF00E5FFL,
    0xFF7C4DFFL,
    0xFFFF6D00L,
    0xFF1B2420L
)

private val STORY_CURATED_MUSIC_TRACKS = listOf(
    "Lo-Fi Chill • Nomi Beats",
    "Acoustic Breeze • Sunset",
    "Midnight Synth • Ambient",
    "Dil Diyan Gallan • Sufi Mix",
    "Upbeat Pop • Sunny Day",
    "Rainy Window • Piano"
)

private val STORY_STICKER_PALETTE = listOf(
    "❤️", "🔥", "😂", "✨", "💚", "🎉", "🌙", "⭐", "📍", "🙌", "☕", "🎧"
)

private val STORY_REACTION_EMOJIS = listOf(
    "❤️", "😂", "😮", "😢", "👏", "🔥"
)

@Composable
fun StoryMediaRenderer(
    localUri: Uri? = null,
    localIsVideo: Boolean = false,
    mediaData: String = "",
    mediaType: String = "image",
    storyFont: String = StoryFontStyle.NOTO_NASTALIQ.key,
    musicTag: String = "",
    overlayText: String = "",
    overlayColorHex: Long = 0xFFFFFFFFL,
    overlayYFraction: Float = 0.5f,
    stickers: List<StoryStickerOverlay> = emptyList(),
    strokes: List<StoryDrawStroke> = emptyList(),
    activeStroke: StoryDrawStroke? = null,
    cropMode: String = "crop",
    rotationDegrees: Int = 0,
    interactiveOverlays: Boolean = false,
    onOverlayYChange: (Float) -> Unit = {},
    onStickerMove: (String, Float, Float) -> Unit = { _, _, _ -> },
    onStickerTap: (StoryStickerOverlay) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resolvedFontFamily = remember(storyFont) { StoryFontStyle.fromKey(storyFont).toFontFamily() }
    val contentScale = if (cropMode == "fit") ContentScale.Fit else ContentScale.Crop

    val imageModel = remember(localUri, mediaData, mediaType) {
        if (localUri == null && mediaType != "video" && !mediaData.startsWith("data:video")) {
            resolveImageModel(mediaData)
        } else null
    }

    val videoTempFile = remember(mediaData, mediaType) {
        if (localUri == null && (mediaType == "video" || mediaData.startsWith("data:video"))) {
            val b64 = mediaData.substringAfter("base64,", "")
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                if (bytes.isEmpty()) null
                else File(context.cacheDir, "story_play_${mediaData.hashCode()}.mp4").apply {
                    if (!exists() || length() != bytes.size.toLong()) {
                        writeBytes(bytes)
                    }
                }
            }.getOrNull()
        } else null
    }

    val isVideo = (localUri != null && localIsVideo) || videoTempFile != null

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF062C1E), EmeraldDeep, EmeraldDark)
                )
            )
    ) {
        val boxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
        val boxHeightPx = with(LocalDensity.current) { maxHeight.toPx() }.coerceAtLeast(1f)

        // 1. Base visual layer (Video, Photo, or Emerald Text Card)
        when {
            isVideo -> {
                StoryVideoSurface(
                    videoUri = localUri,
                    videoFile = videoTempFile,
                    cropMode = cropMode,
                    rotationDegrees = rotationDegrees,
                    modifier = Modifier.fillMaxSize()
                )
            }
            localUri != null -> {
                AsyncImage(
                    model = localUri,
                    contentDescription = "Story preview",
                    contentScale = contentScale,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = rotationDegrees.toFloat()
                        }
                )
            }
            imageModel != null -> {
                val storyCacheKey = remember(mediaData) {
                    computeImageCacheKey("story_media", mediaData)
                }
                val storyRequest = remember(storyCacheKey, imageModel) {
                    ImageRequest.Builder(context)
                        .data(imageModel)
                        .memoryCacheKey(storyCacheKey)
                        .diskCacheKey(storyCacheKey)
                        .diskCachePolicy(if (imageModel is ByteArray) CachePolicy.DISABLED else CachePolicy.ENABLED)
                        .crossfade(false)
                        .build()
                }
                key(storyCacheKey) {
                    AsyncImage(
                        model = storyRequest,
                        contentDescription = "Story media",
                        contentScale = contentScale,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationZ = rotationDegrees.toFloat()
                            }
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                listOf(EmeraldDeep, EmeraldDark, EmeraldMid)
                            )
                        )
                )
            }
        }

        // 2. Drawing strokes layer
        val allStrokes = remember(strokes, activeStroke) {
            if (activeStroke != null) strokes + activeStroke else strokes
        }
        if (allStrokes.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                allStrokes.forEach { stroke ->
                    if (stroke.points.isEmpty()) return@forEach
                    val color = Color(stroke.colorHex)
                    if (stroke.points.size == 1) {
                        val (nx, ny) = stroke.points.first()
                        drawCircle(
                            color = color,
                            radius = stroke.strokeWidth / 2f,
                            center = Offset(nx * w, ny * h)
                        )
                    } else {
                        val path = Path().apply {
                            val first = stroke.points.first()
                            moveTo(first.first * w, first.second * h)
                            for (i in 1 until stroke.points.size) {
                                val pt = stroke.points[i]
                                lineTo(pt.first * w, pt.second * h)
                            }
                        }
                        drawPath(
                            path = path,
                            color = color,
                            style = Stroke(
                                width = stroke.strokeWidth,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }
        }

        // 3. Music tag pill
        if (musicTag.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp)
                    .testTag("story_music_pill"),
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.56f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = EmeraldBright,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = musicTag,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 4. Stickers layer
        stickers.forEach { sticker ->
            val stickerSizePx = with(LocalDensity.current) { 46.dp.toPx() }
            val xPx = (sticker.xFraction * boxWidthPx - stickerSizePx / 2f)
                .coerceIn(0f, (boxWidthPx - stickerSizePx).coerceAtLeast(0f))
            val yPx = (sticker.yFraction * boxHeightPx - stickerSizePx / 2f)
                .coerceIn(0f, (boxHeightPx - stickerSizePx).coerceAtLeast(0f))

            Box(
                modifier = Modifier
                    .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                    .then(
                        if (interactiveOverlays) {
                            Modifier
                                .pointerInput(sticker.id, boxWidthPx, boxHeightPx) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val nextX = (sticker.xFraction + dragAmount.x / boxWidthPx)
                                            .coerceIn(0.08f, 0.92f)
                                        val nextY = (sticker.yFraction + dragAmount.y / boxHeightPx)
                                            .coerceIn(0.12f, 0.85f)
                                        onStickerMove(sticker.id, nextX, nextY)
                                    }
                                }
                                .clickable { onStickerTap(sticker) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(4.dp)
            ) {
                Text(
                    text = sticker.text,
                    fontSize = 36.sp
                )
            }
        }

        // 5. Text Overlay layer (Aa)
        if (overlayText.isNotBlank()) {
            val clampedY = overlayYFraction.coerceIn(0.16f, 0.84f)
            val offsetYPx = ((clampedY - 0.5f) * boxHeightPx).roundToInt()
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(0, offsetYPx) }
                    .padding(horizontal = 24.dp)
                    .then(
                        if (interactiveOverlays) {
                            Modifier.pointerInput(boxHeightPx) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val nextY = (overlayYFraction + dragAmount.y / boxHeightPx)
                                        .coerceIn(0.16f, 0.84f)
                                    onOverlayYChange(nextY)
                                }
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black.copy(alpha = 0.42f)
                ) {
                    Text(
                        text = overlayText,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = resolvedFontFamily,
                            fontSize = 24.sp,
                            lineHeight = 34.sp,
                            textDirection = TextDirection.Content,
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.7f),
                                blurRadius = 6f
                            )
                        ),
                        color = Color(overlayColorHex),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("story_overlay_text_view")
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryVideoSurface(
    videoUri: Uri?,
    videoFile: File?,
    cropMode: String,
    rotationDegrees: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var mediaPlayer by remember(videoUri, videoFile) { mutableStateOf<MediaPlayer?>(null) }
    var videoWidth by remember(videoUri, videoFile) { mutableIntStateOf(0) }
    var videoHeight by remember(videoUri, videoFile) { mutableIntStateOf(0) }
    var viewWidth by remember { mutableIntStateOf(0) }
    var viewHeight by remember { mutableIntStateOf(0) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    fun applyTransformMatrix(tv: TextureView?) {
        if (tv == null || viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val swapped = (rotationDegrees % 180) != 0
        val effectiveVideoW = if (swapped) videoHeight.toFloat() else videoWidth.toFloat()
        val effectiveVideoH = if (swapped) videoWidth.toFloat() else videoHeight.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val videoAspect = effectiveVideoW / effectiveVideoH

        val scaleX: Float
        val scaleY: Float
        if (cropMode == "fit") {
            if (videoAspect > viewAspect) {
                scaleX = 1f
                scaleY = viewAspect / videoAspect
            } else {
                scaleX = videoAspect / viewAspect
                scaleY = 1f
            }
        } else {
            if (videoAspect > viewAspect) {
                scaleX = videoAspect / viewAspect
                scaleY = 1f
            } else {
                scaleX = 1f
                scaleY = viewAspect / videoAspect
            }
        }
        val matrix = Matrix().apply {
            setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
            if (rotationDegrees != 0) {
                postRotate(rotationDegrees.toFloat(), viewWidth / 2f, viewHeight / 2f)
            }
        }
        tv.setTransform(matrix)
    }

    LaunchedEffect(cropMode, rotationDegrees, videoWidth, videoHeight, viewWidth, viewHeight) {
        applyTransformMatrix(textureViewRef)
    }

    DisposableEffect(videoUri, videoFile) {
        onDispose {
            runCatching {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            }
            mediaPlayer = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                textureViewRef = this
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        viewWidth = width
                        viewHeight = height
                        val surface = Surface(st)
                        val player = MediaPlayer()
                        runCatching {
                            when {
                                videoUri != null -> player.setDataSource(ctx, videoUri)
                                videoFile != null && videoFile.exists() -> player.setDataSource(videoFile.absolutePath)
                                else -> return@runCatching
                            }
                            player.setSurface(surface)
                            player.isLooping = true
                            player.setOnVideoSizeChangedListener { _, w, h ->
                                videoWidth = w
                                videoHeight = h
                                applyTransformMatrix(this@apply)
                            }
                            player.setOnPreparedListener { mp ->
                                mp.start()
                            }
                            player.prepareAsync()
                            mediaPlayer = player
                        }.onFailure {
                            runCatching { player.release() }
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        viewWidth = width
                        viewHeight = height
                        applyTransformMatrix(this@apply)
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        runCatching {
                            mediaPlayer?.stop()
                            mediaPlayer?.release()
                        }
                        mediaPlayer = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        update = { tv ->
            textureViewRef = tv
            applyTransformMatrix(tv)
        }
    )
}

@Composable
fun StoryEditorScreen(
    mediaUri: Uri?,
    contacts: List<ChatThread>,
    isPosting: Boolean,
    onPickMediaClick: () -> Unit,
    onClose: () -> Unit,
    onPostStory: (
        uri: Uri?,
        caption: String,
        storyFont: String,
        musicTag: String,
        overlayText: String,
        overlayColorHex: Long,
        overlayYFraction: Float,
        stickersJson: String,
        drawingJson: String,
        cropMode: String,
        rotationDegrees: Int
    ) -> Unit
) {
    BackHandler(enabled = !isPosting) {
        onClose()
    }

    val context = LocalContext.current
    val isVideo = remember(mediaUri) {
        mediaUri?.let { context.contentResolver.getType(it).orEmpty().startsWith("video/") } == true
    }

    var activeTool by remember { mutableStateOf(EditorToolMode.NONE) }
    var caption by remember { mutableStateOf("") }
    var showMentionBar by remember { mutableStateOf(false) }

    var musicTag by remember { mutableStateOf("") }
    var customMusicInput by remember { mutableStateOf("") }

    var overlayText by remember { mutableStateOf("") }
    var overlayFont by remember { mutableStateOf(StoryFontStyle.NOTO_NASTALIQ) }
    var overlayColorHex by remember { mutableStateOf(0xFFFFFFFFL) }
    var overlayYFraction by remember { mutableFloatStateOf(0.5f) }

    var cropMode by remember { mutableStateOf("crop") }
    var rotationDegrees by remember { mutableIntStateOf(0) }

    val stickers = remember { mutableStateListOf<StoryStickerOverlay>() }
    val strokes = remember { mutableStateListOf<StoryDrawStroke>() }
    var drawColorHex by remember { mutableStateOf(0xFF00E676L) }
    var activeDrawPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }

    val mentionCandidates = remember(contacts) {
        contacts.filter { it.username.isNotBlank() && it.threadType == "chat" && !it.blocked }
            .distinctBy { it.username.lowercase() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A110E))
            .testTag("story_editor_screen")
    ) {
        // Center live preview
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (activeTool == EditorToolMode.DRAW) {
                        Modifier.pointerInput(drawColorHex) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    activeDrawPoints = listOf((offset.x / w).coerceIn(0f, 1f) to (offset.y / h).coerceIn(0f, 1f))
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    val nx = (change.position.x / w).coerceIn(0f, 1f)
                                    val ny = (change.position.y / h).coerceIn(0f, 1f)
                                    activeDrawPoints = activeDrawPoints + (nx to ny)
                                },
                                onDragEnd = {
                                    if (activeDrawPoints.isNotEmpty()) {
                                        strokes.add(
                                            StoryDrawStroke(
                                                colorHex = drawColorHex,
                                                strokeWidth = 10f,
                                                points = activeDrawPoints
                                            )
                                        )
                                        activeDrawPoints = emptyList()
                                    }
                                },
                                onDragCancel = {
                                    activeDrawPoints = emptyList()
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            StoryMediaRenderer(
                localUri = mediaUri,
                localIsVideo = isVideo,
                storyFont = overlayFont.key,
                musicTag = musicTag,
                overlayText = overlayText,
                overlayColorHex = overlayColorHex,
                overlayYFraction = overlayYFraction,
                stickers = stickers,
                strokes = strokes,
                activeStroke = if (activeDrawPoints.isNotEmpty()) {
                    StoryDrawStroke(colorHex = drawColorHex, strokeWidth = 10f, points = activeDrawPoints)
                } else null,
                cropMode = cropMode,
                rotationDegrees = rotationDegrees,
                interactiveOverlays = activeTool != EditorToolMode.DRAW,
                onOverlayYChange = { overlayYFraction = it },
                onStickerMove = { id, nx, ny ->
                    val idx = stickers.indexOfFirst { it.id == id }
                    if (idx >= 0) {
                        stickers[idx] = stickers[idx].copy(xFraction = nx, yFraction = ny)
                    }
                },
                onStickerTap = { tapped ->
                    if (activeTool == EditorToolMode.CROP_STICKER) {
                        stickers.removeAll { it.id == tapped.id }
                    }
                }
            )
        }

        // Top gradient + toolbar: Close (✕) on left; Music (♪), Crop/Sticker, Text (Aa), Draw (✎) on right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EditorCircleIconButton(
                    onClick = { if (!isPosting) onClose() },
                    testTag = "story_editor_close"
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close story editor",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                EditorCircleIconButton(
                    onClick = { if (!isPosting) onPickMediaClick() },
                    testTag = "story_editor_pick_media"
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = "Change photo or video",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditorCircleIconButton(
                    active = activeTool == EditorToolMode.MUSIC || musicTag.isNotBlank(),
                    onClick = {
                        activeTool = if (activeTool == EditorToolMode.MUSIC) EditorToolMode.NONE else EditorToolMode.MUSIC
                    },
                    testTag = "story_tool_music"
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "Add music tag",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                EditorCircleIconButton(
                    active = activeTool == EditorToolMode.CROP_STICKER,
                    onClick = {
                        activeTool = if (activeTool == EditorToolMode.CROP_STICKER) EditorToolMode.NONE else EditorToolMode.CROP_STICKER
                    },
                    testTag = "story_tool_crop_sticker"
                ) {
                    Icon(
                        imageVector = Icons.Default.EmojiEmotions,
                        contentDescription = "Crop or add sticker",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                EditorCircleIconButton(
                    active = activeTool == EditorToolMode.TEXT || overlayText.isNotBlank(),
                    onClick = {
                        activeTool = if (activeTool == EditorToolMode.TEXT) EditorToolMode.NONE else EditorToolMode.TEXT
                    },
                    testTag = "story_tool_text"
                ) {
                    Text(
                        text = "Aa",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                EditorCircleIconButton(
                    active = activeTool == EditorToolMode.DRAW,
                    onClick = {
                        activeTool = if (activeTool == EditorToolMode.DRAW) EditorToolMode.NONE else EditorToolMode.DRAW
                    },
                    testTag = "story_tool_draw"
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Draw on story",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Bottom Area: Active Tool Sheet + Caption / @mention / Status (Contacts) / Send (➤)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f), Color.Black.copy(alpha = 0.92f))
                    )
                )
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Active Editing Panel
            AnimatedVisibility(visible = activeTool != EditorToolMode.NONE) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF14211B).copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (activeTool) {
                        EditorToolMode.MUSIC -> {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Add Music Tag",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row {
                                        if (musicTag.isNotBlank()) {
                                            TextButton(onClick = { musicTag = "" }) {
                                                Text("Remove", color = MintOnlineGlow)
                                            }
                                        }
                                        TextButton(onClick = { activeTool = EditorToolMode.NONE }) {
                                            Text("Done", color = EmeraldBright)
                                        }
                                    }
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(STORY_CURATED_MUSIC_TRACKS) { track ->
                                        val selected = musicTag == track
                                        FilterChip(
                                            selected = selected,
                                            onClick = { musicTag = track },
                                            label = { Text(track, maxLines = 1) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = EmeraldMid,
                                                selectedLabelColor = Color.White,
                                                labelColor = Color.White.copy(alpha = 0.85f)
                                            )
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = customMusicInput,
                                        onValueChange = { if (it.length <= 60) customMusicInput = it },
                                        placeholder = { Text("Or type song / artist...", color = Color.White.copy(alpha = 0.6f)) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = EmeraldBright,
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("story_music_custom_input")
                                    )
                                    Button(
                                        onClick = {
                                            if (customMusicInput.isNotBlank()) {
                                                musicTag = customMusicInput.trim()
                                                activeTool = EditorToolMode.NONE
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldBright)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = "Apply music", tint = EmeraldDeep)
                                    }
                                }
                            }
                        }

                        EditorToolMode.CROP_STICKER -> {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Crop, Rotate & Stickers",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    TextButton(onClick = { activeTool = EditorToolMode.NONE }) {
                                        Text("Done", color = EmeraldBright)
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = cropMode == "crop",
                                        onClick = { cropMode = "crop" },
                                        leadingIcon = { Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        label = { Text("Fill / Crop") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = EmeraldMid,
                                            selectedLabelColor = Color.White,
                                            labelColor = Color.White
                                        )
                                    )
                                    FilterChip(
                                        selected = cropMode == "fit",
                                        onClick = { cropMode = "fit" },
                                        leadingIcon = { Icon(Icons.Default.AspectRatio, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        label = { Text("Fit") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = EmeraldMid,
                                            selectedLabelColor = Color.White,
                                            labelColor = Color.White
                                        )
                                    )
                                    FilterChip(
                                        selected = false,
                                        onClick = { rotationDegrees = (rotationDegrees + 90) % 360 },
                                        leadingIcon = { Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        label = { Text("Rotate") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            labelColor = Color.White
                                        )
                                    )
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(STORY_STICKER_PALETTE) { emoji ->
                                        Surface(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    val idx = stickers.size
                                                    stickers.add(
                                                        StoryStickerOverlay(
                                                            id = "st_${System.currentTimeMillis()}_$idx",
                                                            text = emoji,
                                                            xFraction = (0.3f + (idx % 4) * 0.13f).coerceIn(0.18f, 0.82f),
                                                            yFraction = (0.35f + (idx / 4) * 0.12f).coerceIn(0.22f, 0.72f)
                                                        )
                                                    )
                                                }
                                                .testTag("story_sticker_$emoji"),
                                            shape = CircleShape,
                                            color = Color.White.copy(alpha = 0.12f)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(text = emoji, fontSize = 24.sp)
                                            }
                                        }
                                    }
                                }
                                if (stickers.isNotEmpty()) {
                                    Text(
                                        text = "Drag stickers on preview • Tap a sticker to remove (${stickers.size})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MintOnlineGlow
                                    )
                                }
                            }
                        }

                        EditorToolMode.TEXT -> {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Overlay Text (Aa)",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    TextButton(onClick = { activeTool = EditorToolMode.NONE }) {
                                        Text("Done", color = EmeraldBright)
                                    }
                                }
                                OutlinedTextField(
                                    value = overlayText,
                                    onValueChange = { if (it.length <= 140) overlayText = it },
                                    placeholder = { Text("Type text overlay (English or اردو)...", color = Color.White.copy(alpha = 0.6f)) },
                                    maxLines = 2,
                                    textStyle = TextStyle(
                                        color = Color.White,
                                        fontFamily = overlayFont.toFontFamily(),
                                        textDirection = TextDirection.Content
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = EmeraldBright,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.35f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("story_overlay_text_input")
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    STORY_COLOR_SWATCHES.forEach { hex ->
                                        val selected = overlayColorHex == hex
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(Color(hex))
                                                .border(
                                                    width = if (selected) 2.5.dp else 1.dp,
                                                    color = if (selected) EmeraldBright else Color.White.copy(alpha = 0.6f),
                                                    shape = CircleShape
                                                )
                                                .clickable { overlayColorHex = hex }
                                        )
                                    }
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(StoryFontStyle.entries, key = { it.key }) { style ->
                                        val selected = style == overlayFont
                                        FilterChip(
                                            selected = selected,
                                            onClick = { overlayFont = style },
                                            label = {
                                                Text(
                                                    text = style.displayName,
                                                    fontFamily = style.toFontFamily()
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = EmeraldMid,
                                                selectedLabelColor = Color.White,
                                                labelColor = Color.White.copy(alpha = 0.85f)
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        EditorToolMode.DRAW -> {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Draw on Story (✎)",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
                                            },
                                            enabled = strokes.isNotEmpty(),
                                            modifier = Modifier.testTag("story_draw_undo")
                                        ) {
                                            Icon(Icons.Default.Undo, contentDescription = "Undo stroke", tint = Color.White)
                                        }
                                        TextButton(
                                            onClick = { strokes.clear() },
                                            enabled = strokes.isNotEmpty()
                                        ) {
                                            Text("Clear", color = MintOnlineGlow)
                                        }
                                        TextButton(onClick = { activeTool = EditorToolMode.NONE }) {
                                            Text("Done", color = EmeraldBright)
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    STORY_COLOR_SWATCHES.forEach { hex ->
                                        val selected = drawColorHex == hex
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .clip(CircleShape)
                                                .background(Color(hex))
                                                .border(
                                                    width = if (selected) 3.dp else 1.dp,
                                                    color = if (selected) EmeraldBright else Color.White.copy(alpha = 0.65f),
                                                    shape = CircleShape
                                                )
                                                .clickable { drawColorHex = hex }
                                        )
                                    }
                                }
                            }
                        }

                        EditorToolMode.NONE -> Unit
                    }
                }
            }

            // @ Mention strip
            AnimatedVisibility(visible = showMentionBar) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF14211B).copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (mentionCandidates.isEmpty()) {
                        Text(
                            text = "No contacts yet to mention.",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.padding(12.dp)
                        )
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(mentionCandidates, key = { it.id }) { contact ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        val prefix = if (caption.isNotBlank() && !caption.endsWith(" ")) " " else ""
                                        val next = (caption + prefix + "@${contact.username} ").take(160)
                                        caption = next
                                        showMentionBar = false
                                    },
                                    label = { Text("@${contact.username}", color = Color.White) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = EmeraldDark
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Caption Input + @ Mention Button
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (caption.isEmpty()) {
                            Text(
                                text = "Add a caption...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                        BasicTextField(
                            value = caption,
                            onValueChange = { if (it.length <= 160) caption = it },
                            enabled = !isPosting,
                            maxLines = 3,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = Color.White,
                                fontFamily = overlayFont.toFontFamily(),
                                textDirection = TextDirection.Content
                            ),
                            cursorBrush = SolidColor(EmeraldBright),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("story_editor_caption_input")
                        )
                    }
                    IconButton(
                        onClick = { showMentionBar = !showMentionBar },
                        enabled = !isPosting,
                        modifier = Modifier.testTag("story_editor_mention_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AlternateEmail,
                            contentDescription = "Mention a contact",
                            tint = if (showMentionBar) EmeraldBright else Color.White
                        )
                    }
                }
            }

            // Bottom row: Status (Contacts) label on left + Send (➤) button on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.14f),
                    modifier = Modifier.testTag("story_editor_status_label")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = null,
                            tint = MintOnlineGlow,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Status (Contacts)",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                val canSend = (mediaUri != null ||
                    caption.trim().isNotEmpty() ||
                    overlayText.trim().isNotEmpty() ||
                    strokes.isNotEmpty() ||
                    stickers.isNotEmpty()) && !isPosting

                Surface(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(50.dp)
                        .clip(CircleShape)
                        .clickable(enabled = canSend) {
                            onPostStory(
                                mediaUri,
                                caption,
                                overlayFont.key,
                                musicTag,
                                overlayText,
                                overlayColorHex,
                                overlayYFraction,
                                StoryOverlayCodec.encodeStickers(stickers),
                                StoryOverlayCodec.encodeStrokes(strokes),
                                cropMode,
                                rotationDegrees
                            )
                        }
                        .testTag("story_editor_send_button"),
                    shape = CircleShape,
                    color = if (canSend) EmeraldBright else Color.White.copy(alpha = 0.28f),
                    shadowElevation = 6.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isPosting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                                color = EmeraldDeep
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send story",
                                tint = if (canSend) EmeraldDeep else Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorCircleIconButton(
    active: Boolean = false,
    onClick: () -> Unit,
    testTag: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(42.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = CircleShape,
        color = if (active) EmeraldMid else Color.Black.copy(alpha = 0.48f),
        border = BorderStroke(
            width = 1.dp,
            color = if (active) EmeraldBright else Color.White.copy(alpha = 0.25f)
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryViewerScreen(
    stories: List<StoryItem>,
    currentUserId: String,
    observeStoryViews: (String) -> Flow<List<StoryViewRecord>>,
    onStoryViewed: (StoryItem) -> Unit,
    onToggleLike: (StoryItem) -> Unit,
    onSendReaction: (StoryItem, String) -> Unit,
    onSendReply: (StoryItem, String) -> Unit,
    onDeleteStory: (StoryItem) -> Unit,
    onDismiss: () -> Unit
) {
    if (stories.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    BackHandler {
        onDismiss()
    }

    var currentIndex by remember(stories.firstOrNull()?.userId) { mutableIntStateOf(0) }
    val safeIndex = currentIndex.coerceIn(0, stories.lastIndex)
    val story = stories[safeIndex]
    val isOwner = story.userId == currentUserId

    var replyText by remember(story.id) { mutableStateOf("") }
    var showViewsSheet by remember { mutableStateOf(false) }
    var isHoldPaused by remember { mutableStateOf(false) }
    var progressFraction by remember(story.id) { mutableFloatStateOf(0f) }

    val viewsList by remember(story.id) {
        observeStoryViews(story.id)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val liveViewCount = max(viewsList.size, story.viewedByUids.size)
    var optimisticLiked by remember(story.id, story.likedByUids) {
        mutableStateOf(currentUserId in story.likedByUids)
    }
    val heartScale by animateFloatAsState(
        targetValue = if (optimisticLiked) 1.2f else 1f,
        label = "story_like_scale"
    )

    val stickers = remember(story.stickersJson) { StoryOverlayCodec.decodeStickers(story.stickersJson) }
    val strokes = remember(story.drawingJson) { StoryOverlayCodec.decodeStrokes(story.drawingJson) }

    LaunchedEffect(story.id) {
        onStoryViewed(story)
    }

    // Auto-advance timer (pauses while typing, viewing sheet, or press-holding)
    val shouldPause = isHoldPaused || showViewsSheet || replyText.isNotEmpty()
    LaunchedEffect(story.id, shouldPause, stories.size) {
        if (shouldPause) return@LaunchedEffect
        val totalDurationMs = if (story.mediaType == "video" || story.mediaData.startsWith("data:video")) {
            10_000L
        } else {
            6_000L
        }
        val stepMs = 50L
        while (progressFraction < 1f) {
            delay(stepMs)
            progressFraction = min(1f, progressFraction + stepMs.toFloat() / totalDurationMs.toFloat())
        }
        if (safeIndex < stories.lastIndex) {
            currentIndex = safeIndex + 1
            progressFraction = 0f
        } else {
            onDismiss()
        }
    }

    val postedAgoLabel = remember(story.createdAtMs) {
        formatRelativeStoryTime(story.createdAtMs)
    }
    val remainingMs = max(0L, story.expiresAtMs - System.currentTimeMillis())
    val remainingHrs = (remainingMs / 3_600_000L).toInt()
    val remainingMins = ((remainingMs % 3_600_000L) / 60_000L).toInt()
    val expiryLabel = when {
        remainingHrs >= 1 -> "${remainingHrs}h left"
        remainingMins >= 1 -> "${remainingMins}m left"
        else -> "Expiring soon"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A110E))
            .testTag("story_viewer_screen")
    ) {
        // Full-screen Story Media + Editor Overlays
        StoryMediaRenderer(
            mediaData = story.mediaData,
            mediaType = story.mediaType,
            storyFont = story.storyFont,
            musicTag = story.musicTag,
            overlayText = story.overlayText,
            overlayColorHex = story.overlayColorHex,
            overlayYFraction = story.overlayYFraction.toFloat(),
            stickers = stickers,
            strokes = strokes,
            cropMode = story.cropMode,
            rotationDegrees = story.rotationDegrees.toInt(),
            interactiveOverlays = false,
            modifier = Modifier.fillMaxSize()
        )

        // Left / Right tap zones & long-press pause
        Row(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(safeIndex, stories.size) {
                    detectTapGestures(
                        onPress = {
                            isHoldPaused = true
                            tryAwaitRelease()
                            isHoldPaused = false
                        },
                        onTap = { offset ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            if (offset.x < w * 0.35f) {
                                if (safeIndex > 0) {
                                    currentIndex = safeIndex - 1
                                    progressFraction = 0f
                                } else {
                                    progressFraction = 0f
                                }
                            } else {
                                if (safeIndex < stories.lastIndex) {
                                    currentIndex = safeIndex + 1
                                    progressFraction = 0f
                                } else {
                                    onDismiss()
                                }
                            }
                        }
                    )
                }
        ) {
            Spacer(modifier = Modifier.fillMaxSize())
        }

        // Top segmented progress bars & author header with Back (←) close button
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                stories.forEachIndexed { idx, _ ->
                    val segFraction = when {
                        idx < safeIndex -> 1f
                        idx == safeIndex -> progressFraction.coerceIn(0f, 1f)
                        else -> 0f
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.32f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(segFraction)
                                .background(EmeraldBright)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("story_viewer_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close story",
                            tint = Color.White
                        )
                    }
                    AvatarCircle(
                        name = story.authorName,
                        photoData = story.authorPhotoData,
                        hasStoryRing = true,
                        storyRingSeen = true,
                        size = 40.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = story.authorName,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$postedAgoLabel • $expiryLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MintOnlineGlow,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (isOwner) {
                    IconButton(
                        onClick = {
                            onDeleteStory(story)
                            if (stories.size <= 1) onDismiss()
                        },
                        modifier = Modifier.testTag("story_viewer_delete")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete story",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // Bottom caption & Controls (Owner: Views button; Peer: Emoji reactions + Reply box + Like)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f), Color.Black.copy(alpha = 0.9f))
                    )
                )
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (story.caption.isNotBlank()) {
                Text(
                    text = story.caption,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = StoryFontStyle.fromKey(story.storyFont).toFontFamily(),
                        fontSize = 19.sp,
                        lineHeight = 28.sp,
                        textDirection = TextDirection.Content,
                        shadow = Shadow(color = Color.Black.copy(alpha = 0.75f), blurRadius = 6f)
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .testTag("story_viewer_caption")
                )
            }

            if (isOwner) {
                Surface(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { showViewsSheet = true }
                        .testTag("story_viewer_views_button"),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Viewers",
                            tint = EmeraldBright,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (liveViewCount == 1) "Views • 1" else "Views • $liveViewCount",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                // Quick emoji reactions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    STORY_REACTION_EMOJIS.forEach { emoji ->
                        Surface(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .clickable { onSendReaction(story, emoji) }
                                .testTag("story_reaction_$emoji"),
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.14f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = emoji, fontSize = 21.sp)
                            }
                        }
                    }
                }

                // Reply box + Like button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        color = Color.White.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                if (replyText.isEmpty()) {
                                    Text(
                                        text = "Reply to ${story.authorName}...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = replyText,
                                    onValueChange = { if (it.length <= 200) replyText = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                    cursorBrush = SolidColor(EmeraldBright),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("story_viewer_reply_input")
                                )
                            }
                            if (replyText.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        val msg = replyText.trim()
                                        replyText = ""
                                        onSendReply(story, msg)
                                    },
                                    modifier = Modifier.testTag("story_viewer_reply_send")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send story reply",
                                        tint = EmeraldBright
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable {
                                optimisticLiked = !optimisticLiked
                                onToggleLike(story)
                            }
                            .testTag("story_viewer_like_button"),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (optimisticLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (optimisticLiked) "Unlike story" else "Like story",
                                tint = if (optimisticLiked) Color(0xFFFF4081) else Color.White,
                                modifier = Modifier
                                    .size(22.dp)
                                    .scale(heartScale)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showViewsSheet && isOwner) {
        StoryViewsBottomSheet(
            viewCount = liveViewCount,
            views = viewsList,
            onDismiss = { showViewsSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryViewsBottomSheet(
    viewCount: Int,
    views: List<StoryViewRecord>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("story_views_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 460.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        tint = EmeraldDark
                    )
                    Text(
                        text = "Viewed by $viewCount",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close viewers list")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (views.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No views yet. When your contacts view this story, they'll show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(views, key = { it.id.ifBlank { it.viewerUid } }) { viewer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("story_viewer_row_${viewer.viewerUid}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AvatarCircle(
                                name = viewer.viewerName.ifBlank { "Contact" },
                                photoData = viewer.viewerPhotoData,
                                size = 44.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = viewer.viewerName.ifBlank { viewer.viewerUsername.ifBlank { "Contact" } },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = buildString {
                                    if (viewer.viewerUsername.isNotBlank()) {
                                        append("@${viewer.viewerUsername} • ")
                                    }
                                    append(formatRelativeStoryTime(viewer.viewedAtMs))
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (viewer.liked) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = "Liked story",
                                    tint = Color(0xFFFF4081),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (viewer.reactionEmoji.isNotBlank()) {
                                Text(
                                    text = viewer.reactionEmoji,
                                    fontSize = 20.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatRelativeStoryTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return "Just now"
    val diffMs = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0L)
    val mins = diffMs / 60_000L
    val hrs = diffMs / 3_600_000L
    return when {
        mins < 1L -> "Just now"
        mins < 60L -> "${mins}m ago"
        else -> "${hrs}h ago"
    }
}
