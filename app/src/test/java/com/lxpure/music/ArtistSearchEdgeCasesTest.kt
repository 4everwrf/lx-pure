package com.lxpure.music

import com.lxpure.music.core.musicsdk.NetEaseProvider
import com.lxpure.music.core.musicsdk.QqMusicProvider
import com.lxpure.music.core.musicsdk.asJsonObjectOrNull
import com.lxpure.music.data.local.DownloadEntity
import com.lxpure.music.data.local.FavoriteEntity
import com.lxpure.music.data.local.HistoryEntity
import com.lxpure.music.data.local.PlaybackSessionStore
import com.lxpure.music.data.local.PlaylistTrackEntity
import com.lxpure.music.data.local.roomJson
import com.lxpure.music.domain.model.ArtistParser
import com.lxpure.music.domain.model.ArtistRowLayoutPlanner
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.DownloadStatus
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.PlaybackQueueSource
import com.lxpure.music.domain.model.QueueSourceType
import com.lxpure.music.domain.model.RepeatMode
import com.lxpure.music.domain.model.Track
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class ArtistSearchEdgeCasesTest {

    private val testJson = Json { ignoreUnknownKeys = true; isLenient = true }

    // =========================================================================
    // 1. ArtistParser: Empty, blank, control chars, invisible chars, spaces
    // =========================================================================

    @Test
    fun emptyBlankTabsNewlinesNulNbspAndZeroWidthReturnEmpty() {
        val blankInputs = listOf(
            "",
            " ",
            "     ",
            "\t",
            "\t\t  \t",
            "\n",
            "\r",
            "\r\n",
            "\n\r\n",
            "\u0000",
            "\u0000\u0000\u0000",
            "\u00A0",
            "\u00A0\u00A0",
            "\u200B",
            "\u200C",
            "\u200D",
            "\uFEFF",
            "\u200B\u200C\u200D\uFEFF",
            " \t\r\n\u0000\u00A0\u200B\u200C\u200D\uFEFF "
        )
        blankInputs.forEachIndexed { idx, input ->
            assertTrue(
                "Blank/control input #$idx should produce emptyList, got ${ArtistParser.parseRawString(input)}",
                ArtistParser.parseRawString(input).isEmpty()
            )
        }

        // Valid artists surrounded by / separated by control, NBSP, and zero-width characters
        assertEquals(
            listOf("周杰伦", "费玉清"),
            ArtistParser.parseRawString("\uFEFF\u200B周杰伦\u200C / \u00A0费玉清\u200D")
        )
        assertEquals(
            listOf("Artist A", "Artist B", "Artist C", "Artist D"),
            ArtistParser.parseRawString("Artist\u00A0A\u0000Artist\tB\r\nArtist C\nArtist D")
        )
        assertEquals(
            listOf("Taylor Swift"),
            ArtistParser.parseRawString("\t\u00A0Taylor\u00A0\u00A0Swift\u200B\t")
        )
    }

    @Test
    fun fullWidthIdeographicSpaceIsTreatedAsWhitespaceAndTrimmed() {
        // CJK full-width space U+3000 is ubiquitous in Chinese ID3 tags & web payloads
        assertTrue(
            "Pure U+3000 should be treated as unknown/blank placeholder",
            ArtistParser.isUnknownPlaceholder("\u3000")
        )
        assertTrue(
            "Pure U+3000 should parse to emptyList, got ${ArtistParser.parseRawString("\u3000")}",
            ArtistParser.parseRawString("\u3000").isEmpty()
        )
        assertTrue(
            "Repeated U+3000 should parse to emptyList, got ${ArtistParser.parseRawString("\u3000\u3000")}",
            ArtistParser.parseRawString("\u3000\u3000").isEmpty()
        )
        assertTrue(
            "U+3000-padded placeholder should still be recognized as unknown",
            ArtistParser.isUnknownPlaceholder("\u3000未知歌手\u3000")
        )
        assertEquals(
            listOf("周杰伦"),
            ArtistParser.parseRawString("\u3000周杰伦\u3000")
        )
        assertEquals(
            listOf("周杰伦", "陈奕迅"),
            ArtistParser.parseRawString("\u3000周杰伦\u3000/\u3000陈奕迅\u3000")
        )
        assertTrue(
            "Structured artistNames containing only U+3000 should yield emptyList",
            ArtistParser.extractArtists(rawArtist = "", structuredArtists = listOf("\u3000")).isEmpty()
        )
    }

    @Test
    fun fullWidthIdeographicSpaceInternalNormalizationDedupAndPlaceholders() {
        // Leading/trailing U+3000 is stripped by Kotlin's Char.isWhitespace trim(),
        // but internal U+3000 is NOT matched by Java Regex("\\s+"), breaking dedup, placeholders, and spaced ×
        assertTrue(
            "Unknown\\u3000Artist should normalize U+3000 to space and match 'unknown artist' placeholder",
            ArtistParser.isUnknownPlaceholder("Unknown\u3000Artist")
        )
        assertEquals(
            "Internal U+3000 should normalize to ASCII space so duplicates collapse",
            listOf("Jay Chou"),
            ArtistParser.parseRawString("Jay\u3000Chou / Jay Chou")
        )
        assertEquals(
            "Spaced multiplication sign surrounded by U+3000 should split collaborators",
            listOf("周杰伦", "五月天"),
            ArtistParser.parseRawString("周杰伦\u3000×\u3000五月天")
        )
    }

    // =========================================================================
    // 2. ArtistParser: Unknown placeholders
    // =========================================================================

    @Test
    fun unknownPlaceholdersAllStandardFormsCaseVariationsAndMixedValid() {
        val placeholders = listOf(
            "",
            "未知歌手",
            "未知",
            "未知艺术家",
            "未知艺人",
            "unknown",
            "UNKNOWN",
            "Unknown",
            "unknown artist",
            "Unknown Artist",
            "UNKNOWN ARTIST",
            "<unknown>",
            "<UNKNOWN>",
            "(unknown)",
            "(Unknown)",
            "[unknown]",
            "[UNKNOWN]",
            "null",
            "NULL",
            "Null",
            "nil",
            "NIL",
            "none",
            "NONE",
            "-",
            "--",
            "---",
            "?",
            "???"
        )
        for (p in placeholders) {
            assertTrue("Expected isUnknownPlaceholder('$p') == true", ArtistParser.isUnknownPlaceholder(p))
            assertTrue(
                "Expected isUnknownPlaceholder('  $p  ') == true",
                ArtistParser.isUnknownPlaceholder(" \t\u00A0$p\u200B ")
            )
            assertTrue(
                "Expected parseRawString('$p') to be empty, got ${ArtistParser.parseRawString(p)}",
                ArtistParser.parseRawString(p).isEmpty()
            )
            assertTrue(
                "Expected parseRawString('  $p  ') to be empty",
                ArtistParser.parseRawString("  $p  ").isEmpty()
            )
        }

        // Mixed valid artists + placeholders must keep only valid artists in order
        assertEquals(
            listOf("周杰伦", "陈奕迅", "林俊杰"),
            ArtistParser.parseRawString("未知歌手 / 周杰伦 / UNKNOWN ARTIST / null / 陈奕迅 / - / ??? / [unknown] / 林俊杰 / none")
        )
    }

    @Test
    fun naPlaceholderInRawStringIsNotSplitIntoFakeArtistsNAndA() {
        // "n/a" is explicitly listed in ArtistParser.unknownPlaceholders; rawSplitRegex must not split it into ["N", "A"]
        assertTrue(ArtistParser.isUnknownPlaceholder("N/A"))
        assertTrue(ArtistParser.isUnknownPlaceholder("n/a"))
        assertTrue(
            "Structured ['N/A'] should be empty",
            ArtistParser.extractArtists("", listOf("N/A")).isEmpty()
        )
        assertTrue(
            "parseRawString('N/A') should be empty, got ${ArtistParser.parseRawString("N/A")}",
            ArtistParser.parseRawString("N/A").isEmpty()
        )
        assertTrue(
            "parseRawString('n/a') should be empty, got ${ArtistParser.parseRawString("n/a")}",
            ArtistParser.parseRawString("n/a").isEmpty()
        )
        assertTrue(
            "parseRawString('  N/A  ') should be empty, got ${ArtistParser.parseRawString("  N/A  ")}",
            ArtistParser.parseRawString("  N/A  ").isEmpty()
        )
        assertEquals(
            listOf("周杰伦", "陈奕迅"),
            ArtistParser.parseRawString("周杰伦 / N/A / 陈奕迅")
        )
    }

    // =========================================================================
    // 3. ArtistParser: All delimiters
    // =========================================================================

    @Test
    fun allDelimitersIndividualMixedRepeatedAndBoundaryPositions() {
        val delimiters = listOf("/", "／", "、", ",", "，", ";", "；", "&", "＆", "|", "｜", " × ")
        for (d in delimiters) {
            assertEquals(
                "Delimiter '$d' failed",
                listOf("周杰伦", "林俊杰"),
                ArtistParser.parseRawString("周杰伦${d}林俊杰")
            )
            assertEquals(
                "Spaced delimiter '$d' failed",
                listOf("周杰伦", "林俊杰", "陈奕迅"),
                ArtistParser.parseRawString("周杰伦 $d 林俊杰 $d 陈奕迅")
            )
        }

        // Mixed delimiters in one string
        assertEquals(
            listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"),
            ArtistParser.parseRawString("A/B／C、D,E，F;G；H&I＆J|K｜L")
        )
        assertEquals(
            listOf("周杰伦", "五月天", "林俊杰"),
            ArtistParser.parseRawString("周杰伦 × 五月天 × 林俊杰")
        )

        // Repeated, leading, and trailing delimiters
        assertEquals(
            listOf("周杰伦", "费玉清"),
            ArtistParser.parseRawString("/// 周杰伦 /// && ,, ；； 费玉清 |||")
        )

        // Delimiter-only strings produce empty list
        assertTrue(ArtistParser.parseRawString("/／、,，;；&＆|｜").isEmpty())
        assertTrue(ArtistParser.parseRawString(" / / & & , , ; ; | | ").isEmpty())
    }

    // =========================================================================
    // 4. ArtistParser: Protected atomic bands & Kuwo pre-transformed strings
    // =========================================================================

    @Test
    fun allProtectedAtomicBandsAloneCombinedAndCaseVariants() {
        val allProtected = listOf(
            "Crosby, Stills, Nash & Young",
            "Tom Petty & The Heartbreakers",
            "Daryl Hall & John Oates",
            "Bob Marley & The Wailers",
            "Nick Cave & The Bad Seeds",
            "Sly & The Family Stone",
            "Emerson, Lake & Palmer",
            "Florence & The Machine",
            "Hootie & The Blowfish",
            "Echo & The Bunnymen",
            "Crosby, Stills & Nash",
            "Earth, Wind & Fire",
            "Angus & Julia Stone",
            "Tyler, The Creator",
            "Simon & Garfunkel",
            "Belle & Sebastian",
            "Of Monsters & Men",
            "Coheed & Cambria",
            "Kool & The Gang",
            "Mumford & Sons",
            "Above & Beyond",
            "Brooks & Dunn",
            "Chase & Status",
            "Hall & Oates",
            "Tegan & Sara",
            "Iron & Wine",
            "She & Him",
            "AC/DC"
        )

        // Alone
        for (band in allProtected) {
            assertEquals("Protected band alone failed: $band", listOf(band), ArtistParser.parseRawString(band))
        }

        // Combined with other artists via various delimiters
        assertEquals(
            listOf("AC/DC", "Metallica", "Iron Maiden"),
            ArtistParser.parseRawString("AC/DC / Metallica & Iron Maiden")
        )
        assertEquals(
            listOf("Simon & Garfunkel", "Bob Dylan"),
            ArtistParser.parseRawString("Simon & Garfunkel, Bob Dylan")
        )
        assertEquals(
            listOf("Tyler, The Creator", "Kali Uchis", "A\$AP Rocky"),
            ArtistParser.parseRawString("Tyler, The Creator / Kali Uchis (feat. A\$AP Rocky)")
        )
        assertEquals(
            listOf("Earth, Wind & Fire", "Florence & The Machine", "Hall & Oates"),
            ArtistParser.parseRawString("Earth, Wind & Fire / Florence & The Machine & Hall & Oates")
        )
        assertEquals(
            listOf("Crosby, Stills, Nash & Young", "Crosby, Stills & Nash"),
            ArtistParser.parseRawString("Crosby, Stills, Nash & Young / Crosby, Stills & Nash")
        )
        assertEquals(
            listOf("Daryl Hall & John Oates", "Hall & Oates"),
            ArtistParser.parseRawString("Daryl Hall & John Oates; Hall & Oates")
        )

        // Different case variants must stay atomic and preserve original casing
        assertEquals(listOf("ac/dc"), ArtistParser.parseRawString("ac/dc"))
        assertEquals(listOf("Ac/Dc", "Queen"), ArtistParser.parseRawString("Ac/Dc / Queen"))
        assertEquals(listOf("TYLER, THE CREATOR"), ArtistParser.parseRawString("TYLER, THE CREATOR"))
        assertEquals(
            listOf("simon & garfunkel", "bob dylan"),
            ArtistParser.parseRawString("simon & garfunkel / bob dylan")
        )
        assertEquals(
            listOf("EARTH, WIND & FIRE", "THE EMOTIONS"),
            ArtistParser.parseRawString("EARTH, WIND & FIRE & THE EMOTIONS")
        )
        assertEquals(
            listOf("florence & the machine"),
            ArtistParser.parseRawString("florence & the machine")
        )
        assertEquals(
            listOf("HALL & OATES"),
            ArtistParser.parseRawString("HALL & OATES")
        )
    }

    @Test
    fun kuwoStandardTransformedMultiArtistStringsParseCleanly() {
        // Kuwo transforms "周杰伦&温岚" -> "周杰伦 / 温岚" and "周杰伦 & 温岚" -> "周杰伦  /  温岚"
        assertEquals(listOf("周杰伦", "温岚"), ArtistParser.parseRawString("周杰伦 / 温岚"))
        assertEquals(listOf("周杰伦", "温岚"), ArtistParser.parseRawString("周杰伦  /  温岚"))
        assertEquals(listOf("林俊杰", "蔡卓妍", "阿杜"), ArtistParser.parseRawString("林俊杰  /  蔡卓妍  /  阿杜"))
    }

    @Test
    fun kuwoPreTransformedProtectedBandSimonAndGarfunkelPreserved() {
        // KuwoProvider blindly runs .replace("&", " / ") on ARTIST without populating artistNames:
        // "Simon & Garfunkel" becomes "Simon  /  Garfunkel", "Earth, Wind & Fire" becomes "Earth, Wind  /  Fire".
        assertEquals(
            "Kuwo-transformed 'Simon  /  Garfunkel' should remain protected instead of splitting into ['Simon', 'Garfunkel']",
            listOf("Simon & Garfunkel"),
            ArtistParser.parseRawString("Simon  /  Garfunkel")
        )
        assertEquals(
            "Kuwo-transformed 'Earth, Wind  /  Fire' should remain protected",
            listOf("Earth, Wind & Fire"),
            ArtistParser.parseRawString("Earth, Wind  /  Fire")
        )
    }

    @Test
    fun protectedBandWithExtraInternalWhitespacePreserved() {
        // Double spaces or tabs inside a protected band name should still match after whitespace normalization
        assertEquals(
            listOf("Simon & Garfunkel"),
            ArtistParser.parseRawString("Simon  &  Garfunkel")
        )
        assertEquals(
            listOf("Tyler, The Creator"),
            ArtistParser.parseRawString("Tyler,  The Creator")
        )
    }

    // =========================================================================
    // 5. ArtistParser: Featuring variants & false-positive resistance
    // =========================================================================

    @Test
    fun featuringVariantsAndNonFeaturingNamesWithFtSubstring() {
        // Inline featuring variants
        assertEquals(listOf("Jay Chou", "Lara"), ArtistParser.parseRawString("Jay Chou feat. Lara"))
        assertEquals(listOf("Jay Chou", "Lara"), ArtistParser.parseRawString("Jay Chou ft. Lara"))
        assertEquals(listOf("Jay Chou", "Lara"), ArtistParser.parseRawString("Jay Chou featuring Lara"))
        assertEquals(listOf("Jay Chou", "Lara"), ArtistParser.parseRawString("Jay Chou FEAT. Lara"))
        assertEquals(listOf("Jay Chou", "Lara"), ArtistParser.parseRawString("Jay Chou FT. Lara"))
        assertEquals(listOf("Jay Chou", "Lara"), ArtistParser.parseRawString("Jay Chou Featuring: Lara"))
        assertEquals(listOf("Jay Chou", "Lara"), ArtistParser.parseRawString("Jay Chou feat.Lara"))
        assertEquals(listOf("Jay Chou", "Lara"), ArtistParser.parseRawString("Jay Chou feat Lara"))
        assertEquals(listOf("Jay Chou", "Lara"), ArtistParser.parseRawString("Jay Chou ft: Lara"))

        // Bracketed featuring variants (ASCII & full-width)
        assertEquals(listOf("A", "B"), ArtistParser.parseRawString("A (feat. B)"))
        assertEquals(listOf("A", "B", "C"), ArtistParser.parseRawString("A [ft. B & C]"))
        assertEquals(listOf("周杰伦", "温岚", "弹头"), ArtistParser.parseRawString("周杰伦（feat. 温岚、弹头）"))
        assertEquals(listOf("周杰伦", "费玉清"), ArtistParser.parseRawString("周杰伦【featuring：费玉清】"))

        // Combined inline + bracketed featuring
        assertEquals(listOf("A", "B", "C"), ArtistParser.parseRawString("A feat. B (ft. C)"))
        assertEquals(listOf("A", "B", "C", "D"), ArtistParser.parseRawString("A feat. B (ft. C & D)"))

        // Featuring with protected band
        assertEquals(listOf("Kanye West", "AC/DC"), ArtistParser.parseRawString("Kanye West (feat. AC/DC)"))
        assertEquals(
            listOf("Frank Ocean", "Tyler, The Creator"),
            ArtistParser.parseRawString("Frank Ocean feat. Tyler, The Creator")
        )

        // Empty featuring with trailing space inside parens
        assertEquals(listOf("A"), ArtistParser.parseRawString("A (feat. )"))
        assertEquals(listOf("A"), ArtistParser.parseRawString("A (ft.   )"))

        // Non-featuring words containing "ft" must NOT be split
        assertEquals(listOf("A (ftw)"), ArtistParser.parseRawString("A (ftw)"))
        assertEquals(listOf("Daft Punk"), ArtistParser.parseRawString("Daft Punk"))
        assertEquals(listOf("Taylor Swift"), ArtistParser.parseRawString("Taylor Swift"))
        assertEquals(listOf("Soft Cell"), ArtistParser.parseRawString("Soft Cell"))
        assertEquals(listOf("Ft. Worth"), ArtistParser.parseRawString("Ft. Worth"))
        assertEquals(listOf("Leftfield"), ArtistParser.parseRawString("Leftfield"))
        assertEquals(
            listOf("Daft Punk", "Taylor Swift", "Soft Cell", "Leftfield"),
            ArtistParser.parseRawString("Daft Punk / Taylor Swift / Soft Cell / Leftfield")
        )
    }

    @Test
    fun emptyBracketedFeatWithoutSpaceStrippedCleanly() {
        // "A (feat. )" strips to ["A"], so "A (feat.)" and "A [ft.]" should behave identically
        assertEquals(
            "Empty '(feat.)' without space should be stripped just like '(feat. )'",
            listOf("A"),
            ArtistParser.parseRawString("A (feat.)")
        )
        assertEquals(
            "Empty '[ft.]' without space should be stripped",
            listOf("A"),
            ArtistParser.parseRawString("A [ft.]")
        )
    }

    @Test
    fun ftWorthAsSecondArtistNotCorruptedByInlineFeatRegex() {
        // Standalone "Ft. Worth" works, but after " / " inlineFeatRegex matches "\s+ft\.\s*" and corrupts it to "Worth"
        assertEquals(
            "'Ft. Worth' after delimiter should stay intact, not become 'Worth'",
            listOf("Artist A", "Ft. Worth"),
            ArtistParser.parseRawString("Artist A / Ft. Worth")
        )
    }

    @Test
    fun nestedParenthesesInsideFeaturingDoesNotProduceStrayBracketToken() {
        // Nested parens like "(feat. 王菲 (Live))" stop at the first ')' and leave stray ")" as an artist chip
        val parsed = ArtistParser.parseRawString("陈奕迅 (feat. 王菲 (Live))")
        assertFalse(
            "Stray closing parenthesis ')' should never appear as an artist token; got $parsed",
            parsed.contains(")")
        )
    }

    // =========================================================================
    // 6. ArtistParser: Special valid international, symbol, emoji, RTL names
    // =========================================================================

    @Test
    fun specialValidInternationalSymbolEmojiAndRtlArtistNames() {
        val singles = listOf(
            "G.E.M. 邓紫棋",
            "S.H.E",
            "A-Lin",
            "P!nk",
            "f(x)",
            "YOASOBI",
            "米津玄師",
            "방탄소년단 (BTS)",
            "SPY×FAMILY",
            "陈奕迅 (Eason Chan)",
            "m-flo",
            "Lil Nas X",
            "The xx",
            "XXXTENTACION",
            "V",
            "IU",
            "🎧 DJ 音乐人",
            "🔥Flame",
            "🎵",
            "عمرو دياب",
            "فيروز",
            "עידן רייכל"
        )
        for (name in singles) {
            assertEquals("Special artist failed: $name", listOf(name), ArtistParser.parseRawString(name))
        }

        // Combined special artists
        assertEquals(
            listOf("G.E.M. 邓紫棋", "S.H.E", "A-Lin", "P!nk", "f(x)"),
            ArtistParser.parseRawString("G.E.M. 邓紫棋 / S.H.E / A-Lin / P!nk / f(x)")
        )
        assertEquals(
            listOf("SPY×FAMILY", "YOASOBI", "米津玄師"),
            ArtistParser.parseRawString("SPY×FAMILY × YOASOBI / 米津玄師")
        )
        assertEquals(
            listOf("방탄소년단 (BTS)", "V", "IU", "Lil Nas X", "The xx", "XXXTENTACION", "m-flo"),
            ArtistParser.parseRawString("방탄소년단 (BTS) / V / IU / Lil Nas X / The xx / XXXTENTACION / m-flo")
        )
        assertEquals(
            listOf("عمرو دياب", "فيروز", "עידן רייכל"),
            ArtistParser.parseRawString("عمرو دياب / فيروز / עידן רייכל")
        )
        assertEquals(
            listOf("🎧 DJ 音乐人", "🔥Flame"),
            ArtistParser.parseRawString("🎧 DJ 音乐人 & 🔥Flame")
        )
    }

    // =========================================================================
    // 7. ArtistParser: Case-insensitive deduplication preserving first appearance
    // =========================================================================

    @Test
    fun caseInsensitiveDeduplicationPreservesFirstAppearance() {
        assertEquals(
            listOf("Jay Chou", "Lara"),
            ArtistParser.parseRawString("Jay Chou / jay chou / JAY CHOU / Lara / lara")
        )
        assertEquals(
            listOf("jay chou", "LARA"),
            ArtistParser.parseRawString("jay chou / Jay Chou / LARA / Lara")
        )
        assertEquals(
            listOf("AC/DC", "Metallica"),
            ArtistParser.parseRawString("AC/DC / ac/dc / Ac/Dc / Metallica / METALLICA")
        )
        assertEquals(
            listOf("周杰伦", "费玉清"),
            ArtistParser.parseRawString("周杰伦 / 费玉清 / 周杰伦 / 费玉清")
        )
        assertEquals(
            listOf("Taylor Swift", "Ed Sheeran"),
            ArtistParser.extractArtists(
                rawArtist = "ignored",
                structuredArtists = listOf("Taylor Swift", "taylor swift", "Ed Sheeran", "ED SHEERAN")
            )
        )
    }

    // =========================================================================
    // 8. ArtistParser: Structured artistNames precedence, dirty items, fallback
    // =========================================================================

    @Test
    fun structuredArtistNamesPrecedenceDirtyItemsAndPlaceholderFallback() {
        // Structured array takes strict precedence over rawArtist and preserves custom / & , names
        assertEquals(
            listOf("Custom/Band", "Foo & Bar", "Smith, John"),
            ArtistParser.extractArtists(
                rawArtist = "Fallback Artist / Ignored",
                structuredArtists = listOf("Custom/Band", "Foo & Bar", "Smith, John")
            )
        )

        // Dirty structured items: blanks, placeholders, protected names, and embedded unambiguous delimiters
        assertEquals(
            listOf("AC/DC", "A", "B"),
            ArtistParser.extractArtists(
                rawArtist = "Ignored",
                structuredArtists = listOf("  ", "未知歌手", "AC/DC", "A / B")
            )
        )
        assertEquals(
            listOf("A", "B", "C", "D", "E", "F", "G", "H"),
            ArtistParser.extractArtists(
                rawArtist = "Ignored",
                structuredArtists = listOf("A\u0000B", "C；D", "E、F", "G／H")
            )
        )

        // Fallback to rawArtist when ALL structured items are blank or placeholders
        assertEquals(
            listOf("周杰伦", "费玉清"),
            ArtistParser.extractArtists(
                rawArtist = "周杰伦 / 费玉清",
                structuredArtists = listOf("", "   ", "\t\u200B", "未知歌手", "Unknown Artist", "null", "N/A", "???")
            )
        )

        // Both structured and raw are placeholders -> emptyList
        assertTrue(
            ArtistParser.extractArtists(
                rawArtist = "未知歌手",
                structuredArtists = listOf(" ", "unknown", "null")
            ).isEmpty()
        )
    }

    @Test
    fun structuredArtistItemContainingNewlineSplitsCleanly() {
        // structuredSplitRegex explicitly includes \r\n, so embedded newlines in a structured item should split
        assertEquals(
            "Embedded '\\n' in structuredArtists should split into two artists (structuredSplitRegex contains \\r\\n)",
            listOf("Artist A", "Artist B"),
            ArtistParser.extractArtists(
                rawArtist = "",
                structuredArtists = listOf("Artist A\nArtist B")
            )
        )
        assertEquals(
            "Embedded '\\r\\n' in structuredArtists should split into two artists",
            listOf("Artist A", "Artist B"),
            ArtistParser.extractArtists(
                rawArtist = "",
                structuredArtists = listOf("Artist A\r\nArtist B")
            )
        )
    }

    // =========================================================================
    // 9. ArtistParser: Pathological & ReDoS stress tests
    // =========================================================================

    @Test
    fun pathological500ArtistChorusAnd10kDelimiterParensCompleteUnder200Ms() {
        // Warm up regex engine once
        ArtistParser.parseRawString("周杰伦 / 费玉清")

        // 1. 500-artist chorus
        val chorus500 = (1..500).joinToString(" / ") { "歌手$it" }
        val t1 = System.nanoTime()
        val parsedChorus = ArtistParser.parseRawString(chorus500)
        val elapsedChorusMs = (System.nanoTime() - t1) / 1_000_000L
        assertEquals(500, parsedChorus.size)
        assertEquals("歌手1", parsedChorus.first())
        assertEquals("歌手500", parsedChorus.last())
        assertTrue("500-artist chorus took ${elapsedChorusMs}ms (limit 200ms)", elapsedChorusMs < 200L)

        // 2. 10,000-char string with repeated delimiters
        val repeatedDelims = "Start" + " / & , ; 、 ".repeat(910) + "End"
        assertTrue("Expected >= 10,000 chars, got ${repeatedDelims.length}", repeatedDelims.length >= 10_000)
        val t2 = System.nanoTime()
        val parsedDelims = ArtistParser.parseRawString(repeatedDelims)
        val elapsedDelimsMs = (System.nanoTime() - t2) / 1_000_000L
        assertEquals(listOf("Start", "End"), parsedDelims)
        assertTrue("10k delimiter string took ${elapsedDelimsMs}ms (limit 200ms)", elapsedDelimsMs < 200L)

        // 3. 10,000-char string with repeated parentheses
        val repeatedParens = "(".repeat(5_000) + ")".repeat(5_000)
        val t3 = System.nanoTime()
        val parsedParens = ArtistParser.parseRawString(repeatedParens)
        val elapsedParensMs = (System.nanoTime() - t3) / 1_000_000L
        assertEquals(1, parsedParens.size)
        assertTrue("10k parens string took ${elapsedParensMs}ms (limit 200ms)", elapsedParensMs < 200L)

        // 4. 500 repeated protected bands (sentinel replacement stress)
        val repeatedProtected = (1..500).joinToString(" / ") { "AC/DC" }
        val t4 = System.nanoTime()
        val parsedProtected = ArtistParser.parseRawString(repeatedProtected)
        val elapsedProtectedMs = (System.nanoTime() - t4) / 1_000_000L
        assertEquals(listOf("AC/DC"), parsedProtected)
        assertTrue("500 repeated AC/DC took ${elapsedProtectedMs}ms (limit 200ms)", elapsedProtectedMs < 200L)
    }

    @Test
    fun pathologicalUnclosedFeaturingDoesNotTriggerQuadraticReDoS() {
        // Adversarial ReDoS probes targeting bracketedFeatRegex overlapping quantifiers (\s* / [\s.:：]+ followed by [^)\\]）】]+)
        ArtistParser.parseRawString("A (feat. B)")

        val unclosedSpaces = "(feat." + " ".repeat(10_000) + "x"
        val t1 = System.nanoTime()
        ArtistParser.parseRawString(unclosedSpaces)
        val elapsed1Ms = (System.nanoTime() - t1) / 1_000_000L

        val unclosedFeaturingSpaces = "(featuring" + " ".repeat(10_000) + "x"
        val t2 = System.nanoTime()
        ArtistParser.parseRawString(unclosedFeaturingSpaces)
        val elapsed2Ms = (System.nanoTime() - t2) / 1_000_000L

        val repeatedUnclosedFeat = "(feat. ".repeat(1_500)
        val t3 = System.nanoTime()
        ArtistParser.parseRawString(repeatedUnclosedFeat)
        val elapsed3Ms = (System.nanoTime() - t3) / 1_000_000L

        assertTrue(
            "Quadratic ReDoS in bracketedFeatRegex: unclosed '(feat.'+10k spaces+x=${elapsed1Ms}ms, " +
                "unclosed '(featuring'+10k spaces+x=${elapsed2Ms}ms, " +
                "1500x unclosed '(feat. '=${elapsed3Ms}ms (limit 200ms each)",
            elapsed1Ms < 200L && elapsed2Ms < 200L && elapsed3Ms < 200L
        )
    }

    // =========================================================================
    // 10. Provider JSON & Persistence Round-Trip Integration Tests
    // =========================================================================

    @Test
    fun netEaseAndQqProviderJsonParsingPopulatesArtistNamesAndDecodesHtmlEntities() {
        val http = OkHttpClient()

        // --- NetEaseProvider.parseSongObj ---
        val wyProvider = NetEaseProvider(http)
        val wyMultiObj = testJson.parseToJsonElement(
            """
            {
              "id": 90001,
              "name": "跨界合作曲",
              "ar": [
                null,
                {"name": "   "},
                {"name": "Custom/Band"},
                {"name": "Simon & Garfunkel"},
                {"name": "周杰伦"},
                {"name": null}
              ],
              "al": {"id": 500, "name": "测试专辑", "picUrl": "http://p1.music.126.net/test.jpg"},
              "dt": 240000
            }
            """.trimIndent()
        ).asJsonObjectOrNull()!!
        val wyTrack = wyProvider.parseSongObj(wyMultiObj, null)
        assertNotNull(wyTrack)
        assertEquals(listOf("Custom/Band", "Simon & Garfunkel", "周杰伦"), wyTrack!!.artistNames)
        assertEquals("Custom/Band / Simon & Garfunkel / 周杰伦", wyTrack.artist)
        assertEquals(listOf("Custom/Band", "Simon & Garfunkel", "周杰伦"), ArtistParser.extractArtists(wyTrack))

        // NetEase empty ar -> fallback "未知歌手", empty artistNames, non-clickable empty extractArtists
        val wyUnknownObj = testJson.parseToJsonElement(
            """{"id": 90002, "name": "无歌手曲", "ar": [], "al": null, "dt": 180000}"""
        ).asJsonObjectOrNull()!!
        val wyUnknown = wyProvider.parseSongObj(wyUnknownObj, null)!!
        assertEquals("未知歌手", wyUnknown.artist)
        assertTrue(wyUnknown.artistNames.isEmpty())
        assertTrue(ArtistParser.extractArtists(wyUnknown).isEmpty())

        // --- QqMusicProvider.parseQqSong with HTML entities ---
        val qqProvider = QqMusicProvider(http)
        val qqObj = testJson.parseToJsonElement(
            """
            {
              "mid": "001QQTest",
              "id": 777888,
              "title": "Rock &amp; Roll &#39;99",
              "interval": 215,
              "singer": [
                {"mid": "s1", "name": "Simon &amp; Garfunkel"},
                {"mid": "s2", "name": "Guns N&#39; Roses"},
                {"mid": "s3", "name": "O&#x27;Connor &amp; Co."},
                {"mid": "s4", "name": "<em>周杰伦</em>"},
                {"mid": "s5", "name": "&quot;Weird Al&quot; Yankovic"},
                {"mid": "s6", "name": "&nbsp;  "},
                {"mid": "s7", "name": "<em></em>"},
                null
              ],
              "album": {"mid": "alb001", "name": "Live &amp; Acoustic"}
            }
            """.trimIndent()
        ).asJsonObjectOrNull()!!
        val qqTrack = qqProvider.parseQqSong(qqObj)
        assertNotNull(qqTrack)
        val expectedQqArtists = listOf(
            "Simon & Garfunkel",
            "Guns N' Roses",
            "O'Connor & Co.",
            "周杰伦",
            "\"Weird Al\" Yankovic"
        )
        assertEquals(expectedQqArtists, qqTrack!!.artistNames)
        assertEquals(expectedQqArtists.joinToString(" / "), qqTrack.artist)
        assertEquals(expectedQqArtists, ArtistParser.extractArtists(qqTrack))
        assertEquals("Rock & Roll '99", qqTrack.title)
        assertEquals("Live & Acoustic", qqTrack.album)

        // QQ all-blank-entity singers -> "未知歌手", empty artistNames, non-clickable
        val qqEmptySingers = testJson.parseToJsonElement(
            """
            {
              "mid": "002QQEmpty",
              "title": "纯音乐",
              "singer": [{"name": "&nbsp;"}, {"name": "<em> </em>"}]
            }
            """.trimIndent()
        ).asJsonObjectOrNull()!!
        val qqEmptyTrack = qqProvider.parseQqSong(qqEmptySingers)!!
        assertEquals("未知歌手", qqEmptyTrack.artist)
        assertTrue(qqEmptyTrack.artistNames.isEmpty())
        assertTrue(ArtistParser.extractArtists(qqEmptyTrack).isEmpty())
    }

    @Test
    fun structuredArtistContainingSemicolonOrDoubleEncodedAmpNotShattered() {
        // When a structured QQ singer name has double-encoded "&amp;amp;" (decoded once to "&amp;")
        // or a real semicolon artist/unit name like "Steins;Gate", structuredSplitRegex triggers parseRawString
        // and shatters it on BOTH '&' and ';' into fake tokens like ["O'Connor", "amp", "Co."].
        val qqProvider = QqMusicProvider(OkHttpClient())
        val qqObj = testJson.parseToJsonElement(
            """
            {
              "mid": "003QQSemi",
              "title": "Double Encoded Entity Test",
              "singer": [
                {"mid": "s1", "name": "O&#x27;Connor &amp;amp; Co."}
              ]
            }
            """.trimIndent()
        ).asJsonObjectOrNull()!!
        val qqTrack = qqProvider.parseQqSong(qqObj)!!
        assertEquals(listOf("O'Connor &amp; Co."), qqTrack.artistNames)
        assertEquals(
            "Structured QQ artist containing literal '&amp;' should not be shattered into ['O'Connor', 'amp', 'Co.']",
            listOf("O'Connor &amp; Co."),
            ArtistParser.extractArtists(qqTrack)
        )
    }

    @Test
    fun roomEntitiesAndPlaybackSessionStorePreserveArtistNamesAndSupportLegacyJson() {
        val richTrack = Track(
            id = "wy_rich_1",
            title = "合作曲",
            artist = "Custom/Band / Foo & Bar / 周杰伦",
            album = "测试专辑",
            source = MusicSource.WY,
            songmid = "rich_1",
            durationMs = 230_000L,
            artistNames = listOf("Custom/Band", "Foo & Bar", "周杰伦")
        )

        // 1. Room entities round-trip
        val fav = FavoriteEntity.fromTrack(richTrack)
        val favDecoded = fav.toTrackOrNull()
        assertNotNull(favDecoded)
        assertEquals(richTrack.artistNames, favDecoded!!.artistNames)
        assertEquals(richTrack.artistNames, ArtistParser.extractArtists(favDecoded))

        val encodedJson = roomJson.encodeToString(richTrack)
        val plTrack = PlaylistTrackEntity(playlistId = 1L, trackId = richTrack.id, trackJson = encodedJson, position = 0)
        assertEquals(richTrack.artistNames, plTrack.toTrackOrNull()!!.artistNames)

        val hist = HistoryEntity(trackId = richTrack.id, trackJson = encodedJson)
        assertEquals(richTrack.artistNames, hist.toTrackOrNull()!!.artistNames)

        val dl = DownloadEntity(
            trackId = richTrack.id,
            trackJson = encodedJson,
            quality = AudioQuality.FLAC.lxType,
            status = DownloadStatus.COMPLETED.name,
            progress = 1f,
            filePath = "/tmp/rich.flac",
            totalBytes = 10_000L,
            errorMessage = null,
            updatedAt = 1L
        )
        assertEquals(richTrack.artistNames, dl.toTrackOrNull()!!.artistNames)

        // 2. Legacy Room JSON without artistNames field
        val legacyTrackJson = """{"id":"kw_old","title":"千里之外","artist":"周杰伦 / 费玉清","album":"依然范特西","source":"KW","songmid":"555","durationMs":256000}"""
        val legacyFav = FavoriteEntity(trackId = "kw_old", trackJson = legacyTrackJson).toTrackOrNull()
        assertNotNull(legacyFav)
        assertTrue(legacyFav!!.artistNames.isEmpty())
        assertEquals(listOf("周杰伦", "费玉清"), ArtistParser.extractArtists(legacyFav))

        // 3. PlaybackSessionStore full round-trip + legacy playback_queue_v1.json without artistNames
        val tempDir = Files.createTempDirectory("lx_artist_session_test").toFile()
        try {
            val store = PlaybackSessionStore(tempDir)
            store.saveQueueAndCursor(
                queue = listOf(richTrack),
                queueSource = PlaybackQueueSource(type = QueueSourceType.SEARCH, title = "搜索"),
                currentIndex = 0,
                positionMs = 42_000L,
                durationMs = 230_000L,
                repeatMode = RepeatMode.LIST_LOOP,
                activeQualityBadge = "SQ",
                activeProviderName = "Test"
            )

            val restored = PlaybackSessionStore(tempDir).loadSession()
            assertNotNull(restored)
            assertEquals(1, restored!!.queue.size)
            assertEquals(richTrack.artistNames, restored.queue[0].artistNames)
            assertEquals(richTrack.artistNames, ArtistParser.extractArtists(restored.queue[0]))

            // Overwrite queue file with legacy v1 JSON lacking "artistNames"
            val legacyQueueFile = File(tempDir, "playback_queue_v1.json")
            legacyQueueFile.writeText(
                """
                {
                  "version": 1,
                  "queue": [
                    {
                      "id": "tx_legacy_1",
                      "title": "屋顶",
                      "artist": "周杰伦 / 温岚",
                      "album": "吴宗宪",
                      "source": "TX",
                      "songmid": "001",
                      "durationMs": 319000
                    }
                  ],
                  "savedAt": 1700000000000
                }
                """.trimIndent()
            )
            val legacyRestored = PlaybackSessionStore(tempDir).loadSession()
            assertNotNull(legacyRestored)
            assertEquals("tx_legacy_1", legacyRestored!!.queue[0].id)
            assertTrue(legacyRestored.queue[0].artistNames.isEmpty())
            assertEquals(listOf("周杰伦", "温岚"), ArtistParser.extractArtists(legacyRestored.queue[0]))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 11. ArtistRowLayoutPlanner: Property-based (1,000 cases) & Edge Cases
    // =========================================================================

    @Test
    fun artistRowLayoutPlannerPropertyBasedInvariantsAcross1000RandomCases() {
        val rng = Random(20261125L)

        for (caseIdx in 0 until 1_000) {
            val artistCount = when (caseIdx % 10) {
                0 -> 0
                1, 2 -> 1
                3, 4 -> rng.nextInt(2, 5)
                5, 6 -> rng.nextInt(5, 20)
                else -> rng.nextInt(0, 101)
            }
            val maxWidth = when (caseIdx % 8) {
                0 -> 0f
                1 -> rng.nextFloat() * 60f
                else -> rng.nextFloat() * 1200f
            }
            val artistWidths = FloatArray(artistCount) {
                if (rng.nextInt(20) == 0) 0f else rng.nextFloat() * 350f
            }
            val separatorWidth = rng.nextFloat() * 20f
            val singleChevronWidth = rng.nextFloat() * 32f
            val baseBadgeWidth = rng.nextFloat() * 70f
            val badgeFn: (Int) -> Float = { hidden ->
                baseBadgeWidth + hidden.toString().length * 7f
            }
            val albumFullWidth = if (rng.nextInt(4) == 0) 0f else rng.nextFloat() * 450f
            val minAlbumDisplayWidth = rng.nextFloat() * 120f
            val minTruncatedArtistWidth = rng.nextFloat() * 120f

            val plan = ArtistRowLayoutPlanner.plan(
                artistCount = artistCount,
                maxWidth = maxWidth,
                artistWidthAt = { idx -> artistWidths[idx] },
                separatorWidth = separatorWidth,
                singleArtistChevronWidth = singleChevronWidth,
                overflowBadgeWidthForHidden = badgeFn,
                albumFullWidth = albumFullWidth,
                minAlbumDisplayWidth = minAlbumDisplayWidth,
                minTruncatedArtistWidth = minTruncatedArtistWidth
            )

            val ctx = "case=$caseIdx count=$artistCount maxW=$maxWidth sep=$separatorWidth chev=$singleChevronWidth " +
                "albumW=$albumFullWidth minAlb=$minAlbumDisplayWidth minTrunc=$minTruncatedArtistWidth plan=$plan"

            if (artistCount > 0 && maxWidth > 0f) {
                // Invariant 1: Partition conservation
                assertEquals("visibleCount + hiddenCount != artistCount ($ctx)", artistCount, plan.visibleCount + plan.hiddenCount)
                assertTrue("visibleCount < 0 ($ctx)", plan.visibleCount >= 0)
                assertTrue("hiddenCount < 0 ($ctx)", plan.hiddenCount >= 0)

                // Invariant 2: Exact rendered width fits within maxWidth when not truncated and not summary-only
                if (!plan.truncateSingleVisibleArtist && !plan.showCompactSummaryOnly && plan.visibleCount > 0) {
                    val renderedWidth = if (artistCount == 1) {
                        artistWidths[0].coerceAtLeast(0f) + singleChevronWidth
                    } else {
                        var sum = 0f
                        for (i in 0 until plan.visibleCount) {
                            sum = if (i == 0) artistWidths[0].coerceAtLeast(0f) else sum + separatorWidth + artistWidths[i].coerceAtLeast(0f)
                        }
                        if (plan.hiddenCount > 0) {
                            sum += badgeFn(plan.hiddenCount).coerceAtLeast(0f)
                        }
                        sum
                    }
                    assertTrue(
                        "Rendered width $renderedWidth exceeded maxWidth $maxWidth ($ctx)",
                        renderedWidth <= maxWidth + 0.01f
                    )
                }

                // Invariant 3: Album must never show when hiddenCount > 0
                if (plan.hiddenCount > 0) {
                    assertFalse("showAlbum must be false when hiddenCount > 0 ($ctx)", plan.showAlbum)
                }

                // Invariant 4: For 2+ artists, if not all fit, hiddenCount must be > 0
                if (artistCount >= 2) {
                    var totalAll = 0f
                    var allFit = true
                    for (i in 0 until artistCount) {
                        val w = artistWidths[i].coerceAtLeast(0f)
                        totalAll = if (i == 0) w else totalAll + separatorWidth + w
                        if (totalAll > maxWidth) {
                            allFit = false
                            break
                        }
                    }
                    if (!allFit) {
                        assertTrue("Expected hiddenCount > 0 when allFit == false ($ctx)", plan.hiddenCount > 0)
                    } else {
                        assertEquals("Expected all visible when allFit == true ($ctx)", artistCount, plan.visibleCount)
                        assertEquals("Expected 0 hidden when allFit == true ($ctx)", 0, plan.hiddenCount)
                    }
                }

                // Invariant 5: Structural consistency of flags
                if (plan.showCompactSummaryOnly) {
                    assertEquals("Compact summary must have visibleCount=0 ($ctx)", 0, plan.visibleCount)
                    assertEquals("Compact summary must hide all ($ctx)", artistCount, plan.hiddenCount)
                    assertFalse("Compact summary must not show album ($ctx)", plan.showAlbum)
                    assertFalse("Compact summary must not truncate single artist ($ctx)", plan.truncateSingleVisibleArtist)
                }
                if (plan.truncateSingleVisibleArtist) {
                    assertEquals("truncateSingleVisibleArtist requires visibleCount=1 ($ctx)", 1, plan.visibleCount)
                }
            } else {
                assertEquals("Zero/empty guard must return visibleCount=0 ($ctx)", 0, plan.visibleCount)
                assertEquals("Zero/empty guard must return hiddenCount=0 ($ctx)", 0, plan.hiddenCount)
            }
        }
    }

    @Test
    fun artistRowLayoutPlannerLazyMeasurementBoundOn100And10000Artists() {
        // 100 artists on 300f width across realistic chip width profiles: measureCalls <= visibleCount + 3
        val profiles = listOf(
            "uniform-70" to { _: Int -> 70f },
            "uniform-90" to { _: Int -> 90f },
            "varied-55-120" to { i: Int -> 55f + (i * 17 % 65).toFloat() },
            "long-first" to { i: Int -> if (i == 0) 280f else 60f },
            "huge-first" to { i: Int -> if (i == 0) 500f else 60f }
        )
        for ((label, widthProvider) in profiles) {
            var measureCalls = 0
            val plan = ArtistRowLayoutPlanner.plan(
                artistCount = 100,
                maxWidth = 300f,
                artistWidthAt = { idx ->
                    measureCalls++
                    widthProvider(idx)
                },
                separatorWidth = 6f,
                singleArtistChevronWidth = 20f,
                overflowBadgeWidthForHidden = { hidden -> 36f + hidden.toString().length * 8f },
                albumFullWidth = 100f,
                minAlbumDisplayWidth = 60f,
                minTruncatedArtistWidth = 56f
            )
            assertEquals(100, plan.visibleCount + plan.hiddenCount)
            assertTrue(
                "Profile '$label': measureCalls ($measureCalls) exceeded visibleCount (${plan.visibleCount}) + 3",
                measureCalls <= plan.visibleCount + 3
            )
        }

        // Huge 10,000-artist stress: must stay O(visible) and finish instantaneously
        var hugeMeasureCalls = 0
        val t0 = System.nanoTime()
        val hugePlan = ArtistRowLayoutPlanner.plan(
            artistCount = 10_000,
            maxWidth = 300f,
            artistWidthAt = {
                hugeMeasureCalls++
                75f
            },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { 68f },
            albumFullWidth = 80f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
        assertEquals(10_000, hugePlan.visibleCount + hugePlan.hiddenCount)
        assertTrue("10k artists measured $hugeMeasureCalls times (expected <= ${hugePlan.visibleCount + 3})", hugeMeasureCalls <= hugePlan.visibleCount + 3)
        assertTrue("10k artists took ${elapsedMs}ms", elapsedMs < 50L)
    }

    @Test
    fun artistRowLayoutPlannerBoundaryZeroNegativeArtistWidthsAndExactFit() {
        // artistCount <= 0 and maxWidth <= 0f
        val negCount = ArtistRowLayoutPlanner.plan(
            artistCount = -5,
            maxWidth = 300f,
            artistWidthAt = { error("Should not be called") },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { error("Should not be called") },
            albumFullWidth = 80f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        assertEquals(0, negCount.visibleCount)
        assertEquals(0, negCount.hiddenCount)

        val negWidth = ArtistRowLayoutPlanner.plan(
            artistCount = 3,
            maxWidth = -100f,
            artistWidthAt = { error("Should not be called") },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { error("Should not be called") },
            albumFullWidth = 0f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        assertEquals(0, negWidth.visibleCount)
        assertEquals(0, negWidth.hiddenCount)

        // Negative artist widths and negative badge widths are coerced to 0f
        val negItemWidths = ArtistRowLayoutPlanner.plan(
            artistCount = 3,
            maxWidth = 100f,
            artistWidthAt = { -50f },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { -20f },
            albumFullWidth = 50f,
            minAlbumDisplayWidth = 40f,
            minTruncatedArtistWidth = 56f
        )
        assertEquals(3, negItemWidths.visibleCount)
        assertEquals(0, negItemWidths.hiddenCount)
        assertTrue(negItemWidths.showAlbum)

        // Exact fit: 2 artists totaling exactly maxWidth (147 + 6 + 147 = 300)
        val exactFit = ArtistRowLayoutPlanner.plan(
            artistCount = 2,
            maxWidth = 300f,
            artistWidthAt = { 147f },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { 50f },
            albumFullWidth = 80f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        assertEquals(2, exactFit.visibleCount)
        assertEquals(0, exactFit.hiddenCount)
        assertFalse(exactFit.showAlbum)
        assertFalse(exactFit.truncateSingleVisibleArtist)
    }

    @Test
    fun artistRowLayoutPlannerSingleArtistWithHiddenAlbumDoesNotFlagTruncateWhenArtistFits() {
        // w0 (90) + chevron (20) = 110 <= maxWidth (120), album (80) cannot fit so showAlbum == false.
        // Because the artist completely fits in maxWidth once the album is hidden, truncateSingleVisibleArtist should be false.
        val plan = ArtistRowLayoutPlanner.plan(
            artistCount = 1,
            maxWidth = 120f,
            artistWidthAt = { 90f },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { 48f },
            albumFullWidth = 80f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        assertEquals(1, plan.visibleCount)
        assertEquals(0, plan.hiddenCount)
        assertFalse("Album cannot fit on 120f", plan.showAlbum)
        assertFalse(
            "Artist (110f) fits entirely inside maxWidth (120f) when album is hidden, so truncateSingleVisibleArtist should be false",
            plan.truncateSingleVisibleArtist
        )
    }

    @Test
    fun artistRowLayoutPlannerGuardsAgainstNegativeLayoutParameters() {
        // Negative minAlbumDisplayWidth must not cause album to show when 0px remain or allow artist > maxWidth without truncation
        val planNegMinAlbum = ArtistRowLayoutPlanner.plan(
            artistCount = 1,
            maxWidth = 100f,
            artistWidthAt = { 150f },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { 48f },
            albumFullWidth = 80f,
            minAlbumDisplayWidth = -100f,
            minTruncatedArtistWidth = 56f
        )
        assertTrue(
            "Single artist (170f) > maxWidth (100f) MUST set truncateSingleVisibleArtist=true even if minAlbumDisplayWidth is negative; got $planNegMinAlbum",
            planNegMinAlbum.truncateSingleVisibleArtist
        )

        // Negative separatorWidth must not shrink cumulative multi-artist width below the sum of individual widths
        val planNegSep = ArtistRowLayoutPlanner.plan(
            artistCount = 3,
            maxWidth = 100f,
            artistWidthAt = { 80f },
            separatorWidth = -100f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { 40f },
            albumFullWidth = 0f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        assertTrue(
            "Three 80f artists cannot all fit in 100f even if caller passes negative separatorWidth; got $planNegSep",
            planNegSep.hiddenCount > 0
        )
    }

    @Test
    fun artistRowLayoutPlannerHandlesNaNWidthsSafely() {
        var calls = 0
        val nanMaxWidthPlan = ArtistRowLayoutPlanner.plan(
            artistCount = 10,
            maxWidth = Float.NaN,
            artistWidthAt = {
                calls++
                80f
            },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { 48f },
            albumFullWidth = 80f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        assertEquals(
            "NaN maxWidth should be treated as invalid/empty layout (visibleCount=0), got $nanMaxWidthPlan (measureCalls=$calls)",
            0,
            nanMaxWidthPlan.visibleCount
        )
        assertFalse(nanMaxWidthPlan.showAlbum)
    }

    @Test
    fun auditFollowupEdgeCasesZwjEmojiKuwoFallbackSentinelAndSingleArtistBalance() {
        // ZWJ emoji preserved while soft hyphen & Bidi marks stripped
        assertEquals(listOf("👩‍🎤 DJ", "❤️‍🔥 Band"), ArtistParser.parseRawString("👩‍🎤 DJ / ❤️‍🔥 Band"))
        assertEquals(listOf("周杰伦"), ArtistParser.parseRawString("周\u00AD杰\u200E伦"))

        // Numeric commas, K/DA, sentinel collision immunity, local placeholders & fallbackDisplayName
        assertEquals(listOf("10,000 Maniacs"), ArtistParser.parseRawString("10,000 Maniacs"))
        assertEquals(listOf("K/DA", "Madison Beer"), ArtistParser.parseRawString("K/DA / Madison Beer"))
        assertEquals(listOf("Artist \uE000P0\uE001", "AC/DC"), ArtistParser.parseRawString("Artist \uE000P0\uE001 / AC/DC"))
        assertTrue(ArtistParser.parseRawString("本地音频").isEmpty())
        assertEquals("本地音频", ArtistParser.fallbackDisplayName("本地音频"))
        assertEquals("未知歌手", ArtistParser.fallbackDisplayName(" / / & , "))
        assertEquals("未知歌手", ArtistParser.fallbackDisplayName("<unknown>"))

        // Kuwo helper preserves bands & turns bare "&" into "未知歌手"
        val (kwBandDisplay, kwBandList) = com.lxpure.music.core.musicsdk.KuwoProvider.parseKuwoArtist("Simon &amp; Garfunkel")
        assertEquals("Simon & Garfunkel", kwBandDisplay)
        assertEquals(listOf("Simon & Garfunkel"), kwBandList)
        val (kwAmpDisplay, kwAmpList) = com.lxpure.music.core.musicsdk.KuwoProvider.parseKuwoArtist("&amp;")
        assertEquals("未知歌手", kwAmpDisplay)
        assertTrue(kwAmpList.isEmpty())

        // MusicAggregator backfills structured artistNames from alternates when primary has empty artistNames
        val kwPrimary = Track(id = "kw_1", title = "Song", artist = "Custom/Band", source = MusicSource.KW)
        val kgAlt = Track(id = "kg_1", title = "Song", artist = "Custom/Band", source = MusicSource.KG, artistNames = listOf("Custom/Band"))
        val selected = com.lxpure.music.core.musicsdk.MusicAggregator.selectPrimaryAndAlternates(listOf(kwPrimary, kgAlt))
        assertEquals(MusicSource.KW, selected.source)
        assertEquals(listOf("Custom/Band"), selected.artistNames)

        // Long single artist + 70%-width album must NOT crush artist down to 56dp
        val balanced = ArtistRowLayoutPlanner.plan(
            artistCount = 1,
            maxWidth = 300f,
            artistWidthAt = { 260f },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { 48f },
            albumFullWidth = 210f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        assertTrue(balanced.showAlbum)
        assertTrue(balanced.truncateSingleVisibleArtist)
        assertTrue(
            "Single artist cap should stay >= 60% (180f), got ${balanced.singleArtistMaxWidth}",
            (balanced.singleArtistMaxWidth ?: 0f) >= 180f
        )
    }
}
