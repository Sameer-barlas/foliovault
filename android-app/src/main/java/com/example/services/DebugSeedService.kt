package com.example.services

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Debug-only Seed Loader (Locked Decision #9):
 * Strictly guarded by `BuildConfig.DEBUG && isAdmin`.
 * Generates 3 editorial sample books with 15 Canvas-drawn parchment pages each,
 * compressed on-device to WebP (<= 150 KB target, <= 300 KB hard max).
 */
object DebugSeedService {

    private data class SeedBookSpec(
        val title: String,
        val author: String,
        val category: String,
        val description: String,
        val accentHex: Int,
        val pageCount: Int,
    )

    private val specs = listOf(
        SeedBookSpec(
            title = "Atlas of Classical Mechanics",
            author = "Prof. H. Al-Mansoor",
            category = "Physics",
            description = "Illustrated vector diagrams and worked derivations for competitive engineering prep.",
            accentHex = Color.parseColor("#7C2D2D"),
            pageCount = 15,
        ),
        SeedBookSpec(
            title = "Organic Synthesis Compendium",
            author = "Dr. S. Rehman",
            category = "Chemistry",
            description = "Reaction mechanisms, stereochemistry plates, and quick-reference review sheets.",
            accentHex = Color.parseColor("#2E5E4E"),
            pageCount = 15,
        ),
        SeedBookSpec(
            title = "Architectural Proportions & Form",
            author = "Studio Meridian Press",
            category = "Design",
            description = "Measured geometric plates exploring proportion, symmetry, and structural rhythm.",
            accentHex = Color.parseColor("#8B6B3E"),
            pageCount = 15,
        ),
    )

    suspend fun seedSampleLibrary(
        isAdmin: Boolean,
        adminEmail: String,
        adminService: AdminService,
        onProgress: (String) -> Unit,
    ): Result<Int> {
        if (!BuildConfig.DEBUG || !isAdmin) {
            return Result.failure(
                SecurityException("Seed loader is strictly available only to admins in debug builds.")
            )
        }

        return runCatching {
            var createdBooks = 0
            for ((bookIdx, spec) in specs.withIndex()) {
                onProgress("Creating book ${bookIdx + 1}/${specs.size}: ${spec.title}")
                val coverBytes = withContext(Dispatchers.Default) {
                    val coverBmp = renderCoverBitmap(spec)
                    try {
                        ImageCodecService.compressBitmapToBudget(
                            source = coverBmp,
                            maxWidthPx = 480,
                            targetBytes = 40 * 1024,
                            hardMaxBytes = FirestoreConfig.MAX_COVER_BYTES,
                        ).bytes
                    } finally {
                        coverBmp.recycle()
                    }
                }

                val bookId = adminService.createBook(
                    title = spec.title,
                    author = spec.author,
                    description = spec.description,
                    category = spec.category,
                    coverWebPBytes = coverBytes,
                    adminEmail = adminEmail,
                ).getOrThrow()

                for (pageNum in 1..spec.pageCount) {
                    onProgress("${spec.title}: generating page $pageNum/${spec.pageCount}")
                    val encodedPage = withContext(Dispatchers.Default) {
                        val pageBmp = renderParchmentPageBitmap(spec, pageNum)
                        try {
                            ImageCodecService.compressBitmapToBudget(
                                source = pageBmp,
                                maxWidthPx = FirestoreConfig.TARGET_PAGE_WIDTH_PX,
                                targetBytes = FirestoreConfig.TARGET_PAGE_BYTES,
                                hardMaxBytes = FirestoreConfig.MAX_PAGE_BYTES,
                            )
                        } finally {
                            pageBmp.recycle()
                        }
                    }

                    adminService.insertPageAtPosition(
                        bookId = bookId,
                        position = OrderIndexMath.InsertPosition.End,
                        encoded = encodedPage,
                        caption = "Plate $pageNum — ${spec.title}",
                    ).getOrThrow()
                }
                createdBooks++
            }
            createdBooks
        }
    }

    private fun renderCoverBitmap(spec: SeedBookSpec): Bitmap {
        val w = 480
        val h = 640
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(spec.accentHex)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6D5B8")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRect(RectF(28f, 28f, w - 28f, h - 28f), borderPaint)
        canvas.drawRect(RectF(40f, 40f, w - 40f, h - 40f), borderPaint.apply { strokeWidth = 1.5f })

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6D5B8")
            textSize = 22f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(spec.category.uppercase(), w / 2f, 110f, labelPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFBF5")
            textSize = 40f
            typeface = Typeface.SERIF
            textAlign = Paint.Align.CENTER
        }
        val words = spec.title.split(" ")
        var y = 260f
        words.chunked(2).forEach { chunk ->
            canvas.drawText(chunk.joinToString(" "), w / 2f, y, titlePaint)
            y += 52f
        }

        val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F3ECE0")
            textSize = 24f
            typeface = Typeface.SANS_SERIF
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(spec.author, w / 2f, h - 96f, authorPaint)

        return bitmap
    }

    private fun renderParchmentPageBitmap(spec: SeedBookSpec, pageNumber: Int): Bitmap {
        val w = 960
        val h = 1320
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Warm Parchment background
        canvas.drawColor(Color.parseColor("#FAF6EE"))

        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DDD4C4")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRect(RectF(48f, 48f, w - 48f, h - 48f), framePaint)

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5C544B")
            textSize = 24f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText("${spec.category.uppercase()}  •  FOLIO ${"%02d".format(pageNumber)}", 84f, 110f, headerPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1917")
            textSize = 44f
            typeface = Typeface.SERIF
        }
        canvas.drawText(spec.title, 84f, 180f, titlePaint)

        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.accentHex
            strokeWidth = 5f
        }
        canvas.drawLine(84f, 206f, 340f, 206f, rulePaint)

        // Editorial geometric illustration unique per page
        val cx = w / 2f
        val cy = 560f
        val shapeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.accentHex
            alpha = 36
            style = Paint.Style.FILL
        }
        val shapeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.accentHex
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        val radius = 140f + (pageNumber % 4) * 26f
        canvas.drawCircle(cx, cy, radius, shapeFill)
        canvas.drawCircle(cx, cy, radius, shapeStroke)
        canvas.drawRect(
            RectF(cx - radius * 0.7f, cy - radius * 0.7f, cx + radius * 0.7f, cy + radius * 0.7f),
            shapeStroke,
        )
        canvas.drawLine(cx - radius, cy, cx + radius, cy, shapeStroke)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, shapeStroke)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1917")
            textSize = 28f
            typeface = Typeface.SANS_SERIF
        }
        val notes = listOf(
            "Figure $pageNumber.1 — Primary Reference Diagram",
            "Author: ${spec.author}",
            "This study plate is rendered on-device for debug verification",
            "of 12-page batch pagination, pinch zoom, and WebP byte storage.",
        )
        var textY = 860f
        notes.forEach { line ->
            canvas.drawText(line, 84f, textY, bodyPaint)
            textY += 46f
        }

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5C544B")
            textSize = 24f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("— Page $pageNumber of ${spec.pageCount} —", w / 2f, h - 84f, footerPaint)

        return bitmap
    }
}
