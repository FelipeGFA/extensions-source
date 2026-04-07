package eu.kanade.tachiyomi.extension.pt.argoscomics

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
class HomeDto(
    val lastUpdates: List<MangaDto>,
)

@Serializable
class LoginResponseDto(
    val user: LoginUserDto? = null,
    val message: String? = null,
) {
    @Serializable
    class LoginUserDto(
        val id: String,
    )
}

@Serializable
class MangaDto(
    private val id: String,
    private val title: String,
    @SerialName("cover_image")
    private val thumbnail: String,
    @SerialName("link")
    private val slug: String,
    private val status: String = "",
) {
    fun toSManga() = SManga.create().apply {
        this.title = this@MangaDto.title
        this.thumbnail_url = this@MangaDto.thumbnail
        this.url = "/${this@MangaDto.id}/${this@MangaDto.slug}"
        this.status = when (this@MangaDto.status.lowercase(Locale.ROOT)) {
            "active", "up_to_date" -> SManga.ONGOING
            "hiatus" -> SManga.ON_HIATUS
            "finished" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class ProjectsPageDto(
    val projects: List<MangaDto>,
    val pagination: PaginationDto,
    val name: String? = null,
)

@Serializable
class PaginationDto(
    val currentPage: Int,
    val totalPages: Int,
    val totalCount: Int,
    val limit: Int,
    val hasNextPage: Boolean,
    val hasPreviousPage: Boolean,
)

@Serializable
class MangaDetailsDto(
    private val title: String,
    @SerialName("alt_titles")
    private val altTitles: List<String> = emptyList(),
    @SerialName("cover_image")
    private val thumbnail: String,
    private val status: String = "",
    @SerialName("synopsis")
    private val description: String,
    private val genders: List<Value> = emptyList(),
    private val artists: List<Value> = emptyList(),
    private val authors: List<Value> = emptyList(),
) {

    fun toSManga() = SManga.create().apply {
        this.title = this@MangaDetailsDto.title
        this.thumbnail_url = this@MangaDetailsDto.thumbnail
        this.status = when (this@MangaDetailsDto.status.lowercase(Locale.ROOT)) {
            "active", "up_to_date", "coming_soon" -> SManga.ONGOING
            "hiatus" -> SManga.ON_HIATUS
            "finished" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        this.description = buildString {
            append(this@MangaDetailsDto.description)
            if (altTitles.isNotEmpty()) {
                appendLine("\n\nTitulos alternativos: ${altTitles.joinToString()}")
            }
        }

        genders.joinToString { it.name }.takeIf(String::isNotBlank)?.let { genre = it }
        authors.joinToString { it.name }.takeIf(String::isNotBlank)?.let { author = it }
        artists.joinToString { it.name }.takeIf(String::isNotBlank)?.let { artist = it }
    }

    @Serializable
    class Value(
        val name: String,
    )
}

@Serializable
class VolumeChapterDto(
    @SerialName("groups")
    val volumes: List<ChapterListDto>,
) {
    @Serializable
    class ChapterListDto(
        val chapters: List<ChapterDto>,
    )

    @Serializable
    class ChapterDto(
        @SerialName("title")
        val number: Float,
        @SerialName("created_at")
        val createdAt: String,
    )

    fun toChapterList(pathSegment: String): List<SChapter> = volumes
        .flatMap(ChapterListDto::chapters)
        .map {
            SChapter.create().apply {
                name = formatChapterNumber(it.number)
                chapter_number = it.number
                date_upload = DATE_FORMAT.tryParse(it.createdAt)
                url = "$pathSegment/capitulo/${formatChapterNumber(it.number)}"
            }
        }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private fun formatChapterNumber(number: Float): String = number.toString().removeSuffix(".0")
    }
}

@Serializable
class PagesDto(
    val pages: List<ImageDto>,
) {
    fun toPageList(): List<Page> = pages
        .sortedBy(ImageDto::pageNumber)
        .mapIndexed { index, image ->
            Page(index, imageUrl = image.src)
        }

    @Serializable
    class ImageDto(
        @SerialName("photo")
        val src: String,
        val pageNumber: Int,
    )
}
