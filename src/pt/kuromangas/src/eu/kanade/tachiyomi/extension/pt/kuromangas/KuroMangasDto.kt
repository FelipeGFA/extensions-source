package eu.kanade.tachiyomi.extension.pt.kuromangas

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.text.SimpleDateFormat

@Serializable
class MangaListResponse(
    val data: List<MangaDto>,
    val pagination: PaginationDto,
)

@Serializable
class PaginationDto(
    val page: Int,
    @JsonNames("total_pages") val totalPages: Int? = null,
    val hasNext: Boolean? = null,
) {
    fun hasNextPage() = hasNext ?: (page < (totalPages ?: 1))
}

@Serializable
class MangaDto(
    val id: Int,
    val title: String,
    val description: String? = null,
    val status: String? = null,
    @SerialName("cover_image") val coverImage: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: List<String>? = null,
    @SerialName("alternative_titles") val alternativeTitles: List<String>? = null,
) {
    fun toSManga(cdnUrl: String) = SManga.create().apply {
        url = "/manga/$id"
        title = this@MangaDto.title
        thumbnail_url = coverImage?.let { "$cdnUrl/${it.removePrefix("/").removePrefix("uploads/")}" }
        description = buildString {
            this@MangaDto.description?.let { append(it) }
            alternativeTitles?.takeIf { it.isNotEmpty() }?.let {
                if (isNotEmpty()) append("\n\n")
                append("Títulos alternativos: ${it.joinToString()}")
            }
        }.takeIf { it.isNotBlank() }
        author = this@MangaDto.author
        artist = this@MangaDto.artist
        genre = genres?.joinToString()
        status = when (this@MangaDto.status?.lowercase()) {
            "ongoing", "em andamento" -> SManga.ONGOING
            "completed", "completo" -> SManga.COMPLETED
            "hiatus", "em hiato" -> SManga.ON_HIATUS
            "cancelled", "cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class MangaDetailsResponse(
    val manga: MangaDto,
    val chapters: List<ChapterDto>,
)

@Serializable
class ChapterDto(
    val id: Int,
    val title: String? = null,
    @SerialName("chapter_number") val chapterNumber: String? = null,
    @SerialName("upload_date") val uploadDate: String? = null,
) {
    fun toSChapter(mangaId: Int, dateFormat: SimpleDateFormat) = SChapter.create().apply {
        url = "/chapter/$mangaId/$id"
        name = buildString {
            chapterNumber?.toFloatOrNull()?.let { append("Capítulo ${it.toString().removeSuffix(".0")}") }
            this@ChapterDto.title?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append(" - ")
                append(it)
            }
        }.ifBlank { "Capítulo $id" }
        chapter_number = chapterNumber?.toFloatOrNull() ?: 0f
        date_upload = uploadDate?.let { dateFormat.tryParse(it) } ?: 0L
    }
}

@Serializable
class ChapterPagesResponse(val pages: List<String>)
