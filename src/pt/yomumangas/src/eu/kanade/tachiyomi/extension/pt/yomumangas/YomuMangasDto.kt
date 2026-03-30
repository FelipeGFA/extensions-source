package eu.kanade.tachiyomi.extension.pt.yomumangas

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
class YomuMangasSearchDto(
    val mangas: List<YomuMangasMangaDto> = emptyList(),
    val page: Int = 0,
    val pages: Int = 0,
) {
    val hasNextPage: Boolean
        get() = page < pages
}

@Serializable
class YomuMangasDetailsDto(
    val manga: YomuMangasMangaDto,
)

@Serializable
class YomuMangasMangaDto(
    val id: Int,
    val slug: String,
    val title: String,
    val cover: String? = null,
    val status: String = "",
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    @Serializable(with = GenreListSerializer::class)
    val genres: List<YomuMangasFilterValueDto> = emptyList(),
    @Serializable(with = TagListSerializer::class)
    val tags: List<YomuMangasFilterValueDto> = emptyList(),
    val description: String? = null,
) {

    fun toSManga(): SManga = SManga.create().apply {
        title = this@YomuMangasMangaDto.title
        author = authors.joinedOrNull()
        artist = artists.joinedOrNull()
        genre = (genres + tags)
            .map(YomuMangasFilterValueDto::name)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString()
            .ifBlank { null }
        description = this@YomuMangasMangaDto.description
            ?.trim()
            ?.takeIf(String::isNotBlank)
        status = when (this@YomuMangasMangaDto.status) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETE" -> SManga.COMPLETED
            "HIATUS", "ONHOLD" -> SManga.ON_HIATUS
            "CANCELLED" -> SManga.CANCELLED
            "ARCHIVED" -> SManga.PUBLISHING_FINISHED
            else -> SManga.UNKNOWN
        }
        thumbnail_url = YomuMangas.toImageUrl(cover)
        url = YomuMangas.mangaUrl(id, slug)
    }
}

@Serializable
class YomuMangasFilterValueDto(
    val id: Int,
    val name: String,
)

@Serializable
class YomuMangasChaptersDto(
    val chapters: List<YomuMangasChapterDto> = emptyList(),
    val scans: List<YomuMangasScanDto> = emptyList(),
)

@Serializable
class YomuMangasChapterDto(
    val id: Int,
    val chapter: String,
    val volume: Int? = null,
    val title: String? = null,
    @SerialName("uploaded_at") val uploadedAt: String? = null,
    val extra: Boolean = false,
    val scans: List<Int> = emptyList(),
) {

    fun toSChapter(
        mangaId: Int,
        slug: String,
        scansById: Map<Int, YomuMangasScanDto>,
    ): SChapter = SChapter.create().apply {
        val chapterLabel = chapter.removeSuffix(".0")
        name = buildList {
            add(
                buildString {
                    volume?.let {
                        append("Vol. ")
                        append(it)
                        append(' ')
                    }
                    append(if (extra) "Extra" else "Capi­tulo $chapterLabel")
                },
            )
            title?.trim()?.takeIf(String::isNotBlank)?.let(::add)
        }.joinToString(" - ")
        chapter_number = chapter.toFloatOrNull() ?: -1f
        scanlator = scans.mapNotNull { scansById[it]?.name }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString()
            .ifBlank { null }
        date_upload = uploadedAt?.let { DATE_FORMATTER.tryParse(it) } ?: 0L
        url = YomuMangas.mangaUrl(mangaId, slug, chapter)
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }
}

@Serializable
class YomuMangasScanDto(
    val id: Int,
    val name: String,
)

private object GenreListSerializer : JsonTransformingSerializer<List<YomuMangasFilterValueDto>>(
    ListSerializer(YomuMangasFilterValueDto.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement = normalizeList(element, genreOptions)
}

private object TagListSerializer : JsonTransformingSerializer<List<YomuMangasFilterValueDto>>(
    ListSerializer(YomuMangasFilterValueDto.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement = normalizeList(element, tagOptions)
}

private fun normalizeList(
    element: JsonElement,
    options: List<FilterOption>,
): JsonElement = JsonArray(
    element.jsonArray.map { item ->
        if (item is JsonObject) {
            buildJsonObject {
                put("id", item["id"] ?: JsonPrimitive(-1))
                put("name", item["name"] ?: JsonPrimitive(""))
            }
        } else {
            val id = item.jsonPrimitive.int
            val option = options.firstOrNull { it.id == id.toString() } ?: FilterOption("Unknown", id.toString())
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("name", JsonPrimitive(option.name.trim()))
            }
        }
    },
)

private fun List<String>.joinedOrNull(): String? = map(String::trim)
    .filter(String::isNotBlank)
    .distinct()
    .joinToString()
    .ifBlank { null }
