package eu.kanade.tachiyomi.extension.pt.plumacomics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream

internal class PlumaComicsImageBuilder(
    private val client: OkHttpClient,
    private val drmHelper: PlumaComicsDrmHelper,
) {

    fun buildCompositeResponse(
        request: Request,
        chapterId: String,
        pageNumber: Int,
        session: ChapterSession,
    ): Response {
        val stripCount = session.stripCount(pageNumber)
        val stripBitmaps = mutableListOf<Bitmap>()

        try {
            repeat(stripCount) { stripIndex ->
                val stripResponse = client.newCall(
                    request.newBuilder()
                        .url(drmHelper.buildStripImageUrl(chapterId, pageNumber, stripIndex))
                        .build(),
                ).execute()

                if (!stripResponse.isSuccessful) {
                    return stripResponse
                }

                val decryptedBytes = stripResponse.use { response ->
                    PlumaImageDecrypt.decryptImageBytes(
                        encryptedBytes = response.body.bytes(),
                        baseSeed = session.baseSeed,
                    )
                }
                val bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                    ?: throw IllegalStateException("Failed to decode strip $stripIndex for page $pageNumber")
                stripBitmaps += bitmap
            }

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(stitchBitmaps(stripBitmaps).toResponseBody(JPEG_MEDIA_TYPE))
                .build()
        } finally {
            stripBitmaps.forEach(Bitmap::recycle)
        }
    }

    private fun stitchBitmaps(bitmaps: List<Bitmap>): ByteArray {
        require(bitmaps.isNotEmpty()) { "Missing strip bitmaps" }

        val width = bitmaps.maxOf(Bitmap::getWidth)
        val height = bitmaps.sumOf(Bitmap::getHeight)
        val stitched = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(stitched)

        var offsetY = 0f
        bitmaps.forEach { bitmap ->
            canvas.drawBitmap(bitmap, 0f, offsetY, null)
            offsetY += bitmap.height
        }

        return ByteArrayOutputStream().use { stream ->
            stitched.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            stitched.recycle()
            stream.toByteArray()
        }
    }

    private companion object {
        val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}
