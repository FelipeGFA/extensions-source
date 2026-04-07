package eu.kanade.tachiyomi.extension.pt.argoscomics

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal data class ArgosComicsFilterValue(
    val filter: String,
    val term: String,
)

internal fun getArgosComicsFilterList(): FilterList = FilterList(
    Filter.Header("O site aceita apenas um filtro por vez"),
    ArgosComicsStatusFilter(),
    ArgosComicsTypeFilter(),
    ArgosComicsGenreFilter(),
)

internal fun FilterList.selectedArgosComicsFilter(): ArgosComicsFilterValue? = asSequence()
    .mapNotNull {
        when (it) {
            is ArgosComicsStatusFilter -> it.selected
            is ArgosComicsTypeFilter -> it.selected
            is ArgosComicsGenreFilter -> it.selected
            else -> null
        }
    }
    .firstOrNull()

internal open class ArgosComicsSelectFilter(
    name: String,
    private val options: List<Pair<String, ArgosComicsFilterValue?>>,
) : Filter.Select<String>(
    name,
    options.map { it.first }.toTypedArray(),
) {
    val selected: ArgosComicsFilterValue?
        get() = options[state].second
}

internal class ArgosComicsStatusFilter :
    ArgosComicsSelectFilter(
        "Status",
        STATUS_OPTIONS,
    )

internal class ArgosComicsTypeFilter :
    ArgosComicsSelectFilter(
        "Tipo",
        TYPE_OPTIONS,
    )

internal class ArgosComicsGenreFilter :
    ArgosComicsSelectFilter(
        "Genero",
        GENRE_OPTIONS,
    )

private val STATUS_OPTIONS = listOf(
    "Todos" to null,
    "Em breve" to ArgosComicsFilterValue("status", "COMING_SOON"),
    "Completo" to ArgosComicsFilterValue("status", "FINISHED"),
    "Hiato" to ArgosComicsFilterValue("status", "HIATUS"),
    "Ativo" to ArgosComicsFilterValue("status", "ACTIVE"),
    "Em dia" to ArgosComicsFilterValue("status", "UP_TO_DATE"),
)

private val TYPE_OPTIONS = listOf(
    "Todos" to null,
    "Manga" to ArgosComicsFilterValue("flag", "🇯🇵"),
    "Manhua Chines" to ArgosComicsFilterValue("flag", "🇨🇳"),
    "Manhwa Coreano" to ArgosComicsFilterValue("flag", "🇰🇷"),
)

private val GENRE_OPTIONS = listOf(
    "Todos" to null,
    "Ação" to ArgosComicsFilterValue("genero", "Ação"),
    "Adulto" to ArgosComicsFilterValue("genero", "Adulto"),
    "Alien" to ArgosComicsFilterValue("genero", "Alien"),
    "Apocalipse" to ArgosComicsFilterValue("genero", "Apocalipse"),
    "Artes Marciais" to ArgosComicsFilterValue("genero", "Artes Marciais"),
    "Aventura" to ArgosComicsFilterValue("genero", "Aventura"),
    "Censurado" to ArgosComicsFilterValue("genero", "Censurado"),
    "Comédia" to ArgosComicsFilterValue("genero", "Comédia"),
    "Cultivo" to ArgosComicsFilterValue("genero", "Cultivo"),
    "Demônio" to ArgosComicsFilterValue("genero", "Demônio"),
    "Drama" to ArgosComicsFilterValue("genero", "Drama"),
    "Dungeons" to ArgosComicsFilterValue("genero", "Dungeons"),
    "Ecchi" to ArgosComicsFilterValue("genero", "Ecchi"),
    "Escolar" to ArgosComicsFilterValue("genero", "Escolar"),
    "Evolução" to ArgosComicsFilterValue("genero", "Evolução"),
    "Fantasia" to ArgosComicsFilterValue("genero", "Fantasia"),
    "Fazendinha" to ArgosComicsFilterValue("genero", "Fazendinha"),
    "Goblin" to ArgosComicsFilterValue("genero", "Goblin"),
    "Gyaru" to ArgosComicsFilterValue("genero", "Gyaru"),
    "Hárem" to ArgosComicsFilterValue("genero", "Hárem"),
    "Histórico" to ArgosComicsFilterValue("genero", "Histórico"),
    "Isekai" to ArgosComicsFilterValue("genero", "Isekai"),
    "Jogo" to ArgosComicsFilterValue("genero", "Jogo"),
    "Magia" to ArgosComicsFilterValue("genero", "Magia"),
    "Manga" to ArgosComicsFilterValue("genero", "Manga"),
    "Manhua" to ArgosComicsFilterValue("genero", "Manhua"),
    "Manhwa" to ArgosComicsFilterValue("genero", "Manhwa"),
    "MC Overpower" to ArgosComicsFilterValue("genero", "MC Overpower"),
    "Mistério" to ArgosComicsFilterValue("genero", "Mistério"),
    "Murim" to ArgosComicsFilterValue("genero", "Murim"),
    "Mutação" to ArgosComicsFilterValue("genero", "Mutação"),
    "Reencarnação" to ArgosComicsFilterValue("genero", "Reencarnação"),
    "Romance" to ArgosComicsFilterValue("genero", "Romance"),
    "Sci-fi" to ArgosComicsFilterValue("genero", "Sci-fi"),
    "Seinen" to ArgosComicsFilterValue("genero", "Seinen"),
    "Shoujo" to ArgosComicsFilterValue("genero", "Shoujo"),
    "Sistema" to ArgosComicsFilterValue("genero", "Sistema"),
    "Sobrenatural" to ArgosComicsFilterValue("genero", "Sobrenatural"),
    "Sobrevivência" to ArgosComicsFilterValue("genero", "Sobrevivência"),
    "Terror" to ArgosComicsFilterValue("genero", "Terror"),
    "Vampiro" to ArgosComicsFilterValue("genero", "Vampiro"),
    "Viagem No Tempo" to ArgosComicsFilterValue("genero", "Viagem No Tempo"),
    "Vida Cotidiana" to ArgosComicsFilterValue("genero", "Vida Cotidiana"),
    "Vilão" to ArgosComicsFilterValue("genero", "Vilão"),
    "Vingança" to ArgosComicsFilterValue("genero", "Vingança"),
    "Zumbis" to ArgosComicsFilterValue("genero", "Zumbis"),
)
