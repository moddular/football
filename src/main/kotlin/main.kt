import org.http4k.client.ApacheClient
import org.http4k.core.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URL
import javax.imageio.ImageIO

private val client = ApacheClient()

private fun fetch(uri: Uri) =
    client(Request(Method.GET, uri)).let {
        when (it.status) {
            Status.OK -> Jsoup.parse(it.bodyString())
            else -> null
        }
    }

private fun coloursFrom(uri: Uri): List<String>? {
    val colours = mutableListOf<String>()

    val image = try {
        ImageIO.read(URL(uri.scheme, uri.host, uri.path))
    } catch (_: IOException) {
        null
    }

    if (image != null) {
        (0 until image.width).forEach { x ->
            (0 until image.height).forEach { y ->
                val colour = image.getRGB(x, y)
                val blue = colour and 0xff
                val green = colour and 0xff00 shr 8
                val red = colour and 0xff0000 shr 16
                colours.add("rgb($red,$green,$blue)")
            }
        }
    }

    val total = colours.size.toDouble()
    val sorted = colours.groupingBy { it }
        .eachCount()
        .toList()
        .sortedByDescending { (_, v) -> v }

    return sorted.takeWhile { it.second > total / 100 * 25 }
        .take(3)
        .let { if (it.isEmpty()) sorted.take(3) else it }
        .map { it.first }
        .let { if (it.isEmpty()) null else it }
}

private fun Document.extractColours(baseUri: Uri) =
    this.select("td.toccolours")
        .first()
        ?.let { element ->
            val colours = element.select("img[src*='/Kit_body_']")
                ?.first()
                ?.attr("src")
                ?.let { coloursFrom(Uri.of(it).scheme(baseUri.scheme)) }

            if (colours.isNullOrEmpty()) {
                element.select("img[src*='/Kit_body.svg']")
                    .first()
                    ?.closest("div")
                    ?.previousElementSibling()
                    ?.attr("style")
                    ?.split("\\s*;\\s*".toRegex())
                    ?.fold(mapOf<String, String>()) { acc, cur ->
                        val bits =  cur.split("\\s*:\\s*".toRegex())
                        if (bits.size == 2) acc + mapOf(bits[0] to bits[1])
                        else acc
                    }
                    ?.get("background-color")
                    ?.trim()
                    ?.let {
                        if (it.matches("^#[A-Fa-f0-9]{6}(/#?[A-Fa-f0-9]{6})?$".toRegex()))
                            it.split("/").map { colour ->
                                if (colour.startsWith("#")) colour else "#$colour"
                            }
                        else null
                    }
            } else {
                colours
            }
        }

private fun Document.extractLocationUris(baseUri: Uri) =
    this.select("th.infobox-label")
        .firstOrNull { setOf("ground", "home ground", "stadium", "location").contains(it.text().trim().lowercase()) }
        ?.nextElementSibling()
        ?.select("a")
        ?.filterNot { it.classNames().contains("new") }
        ?.map {
            val href = it.attr("href")
            if (href.contains("://")) Uri.of(href) else baseUri.path(href)
        }
        ?.let { if (it.isEmpty()) listOf(baseUri) else it }

private fun Document.extractCoordinates() =
    listOf("span.geo-dms", "#coordinates").mapNotNull { this.select(it).first() }
        .firstOrNull()
        ?.let {
            listOfNotNull(
                it.select("span.latitude").text(),
                it.select("span.longitude").text(),
                it.select("span[title='Breitengrad']").text(),
                it.select("span[title='Längengrad']").text()
            )
            .filter { text -> text.isNotEmpty() }
            .map { text ->
                text.replace("<[^>]+>".toRegex(), "")
                    .replace("&nbsp;", "")
                    .replace("\\s+".toRegex(), "")
                    .replace(",", ".")
            }
        }
        ?.map {
            val match = "^([0-9.]+)°([0-9.]+)′(?:([0-9.]+)?″)?([NSEWO])$".toRegex().matchEntire(it)
            match?.let { m ->
                val degrees = m.groups[1]?.value?.toDouble() ?: 0.0
                val minutes = m.groups[2]?.value?.toDouble() ?: 0.0
                val seconds = m.groups[3]?.value?.toDouble() ?: 0.0
                val multiplier = if (setOf("S", "W").contains(m.groups[4]?.value)) -1 else 1
                (degrees + (minutes / 60) + (seconds / 3600)) * multiplier
            }
        }
        ?.let { if (it.size == 2) it.zipWithNext() else emptyList() }
        ?.firstOrNull()


private fun Document.extractTeams(baseUri: Uri) =
    this.select("table.wikitable")
        .flatMap { table ->
            val country = table.previousElementSiblings()
                .firstOrNull { it.nodeName() == "h3" }
                ?.select(".mw-headline")
                ?.first()
                ?.text()

            table.select("th[scope='row'] a").map {
                Triple(country, it.text(), baseUri.path(it.attr("href")))
            }
        }

fun main() {
    val start = Uri.of("https://en.wikipedia.org/wiki/List_of_top-division_football_clubs_in_UEFA_countries")
    val teams = fetch(start)?.extractTeams(start)

    teams?.forEach { team ->
        val (country, name, uri) = team

        val teamPage = fetch(uri)
        val colours = teamPage?.extractColours(uri)

        val locationPages = teamPage?.extractLocationUris(uri)

        val location = locationPages
            ?.fold(null) { acc: Pair<Double?, Double?>?, cur ->
                acc ?: fetch(cur)?.extractCoordinates()
            } ?: locationPages
            ?.map { page ->
                fetch(page)?.extractLocationUris(page)?.let {
                    it.fold(null) { acc: Pair<Double?, Double?>?, cur ->
                        acc ?: fetch(cur)?.extractCoordinates()
                    }
                }
            }
            ?.firstOrNull()

        val line = if (colours != null && location != null) {
            "${country}|${name}|${location.first},${location.second}|${colours.joinToString(",")}\n"
        } else if (colours == null) {
            "Unable to fetch colour for ${name}\n"
        } else {
            "Unable to fetch location for ${name}\n"
        }
        print(line)
        Thread.sleep(1000L)
    }
}
