package com.sprich.app

import com.sprich.app.input.commands.AcousticBasis
import com.sprich.app.input.commands.CommandPolicy
import com.sprich.app.input.commands.ParsedDictation
import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.input.commands.TranscriptOrigin
import com.sprich.app.input.correction.CorrectionPlan
import com.sprich.app.input.correction.CorrectionTarget
import com.sprich.app.input.correction.EditorText
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.refinement.RefinementMode
import com.sprich.app.speech.refinement.RefinementValidator
import org.junit.Assert.*
import org.junit.Test

/**
 * E01 text-level development corpus v1 (internal testing).
 *
 * 1,000+ utterances balanced across EN/DE/ES/FR exercised through the real
 * deterministic pipeline (parseTyped [+ validator for cleanup pairs,
 * CorrectionPlan for correction cases, routing for policy cases]).
 *
 * HONEST SCOPE: text in → decision out. No audio, no ASR, no speakers, no
 * latency, no WER — this is NOT the plan's held-out natural-speech release
 * corpus (§10.2) and must never be cited as acoustic/quality evidence.
 * Natural-speaker collection stays BLOCKED (needs recordings + consent).
 */
class ThousandUtteranceCorpusTest {

    private enum class Expect { TEXT, FORMATTING, EDITING, REJECTED }

    /** Every whole-utterance formatting rendering the grammar can produce. */
    private val formattingRenderings = setOf(".", "?", "!", ",", ":", ";", " - ", "\n", "\n\n", "\"")

    private data class Case(
        val id: String,
        val lang: ResolvedUtteranceLanguage,
        val policy: CommandPolicy,
        val basis: AcousticBasis,
        val input: String,
        val expect: Expect,
        val exact: String? = null,
    )

    private data class Pair(val id: String, val original: String, val candidate: String, val accept: Boolean)

    private val en = ResolvedUtteranceLanguage.Known(Language.EN)
    private val de = ResolvedUtteranceLanguage.Known(Language.DE)
    private val es = ResolvedUtteranceLanguage.Known(Language.ES)
    private val fr = ResolvedUtteranceLanguage.Known(Language.FR)
    private val unknown = ResolvedUtteranceLanguage.Unknown
    private val localOn = CommandPolicy(commandsEnabled = true, origin = TranscriptOrigin.LOCAL)
    private val remoteOn = CommandPolicy(commandsEnabled = true, origin = TranscriptOrigin.REMOTE)
    private val off = CommandPolicy(commandsEnabled = false, origin = TranscriptOrigin.LOCAL)

    private fun check(c: Case, failures: MutableList<String>) {
        val r = try {
            SpokenEditingParser.parseTyped(c.input, c.lang, c.policy, c.basis)
        } catch (e: Exception) {
            failures.add("${c.id}: threw ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val ok = when (c.expect) {
            Expect.TEXT -> r is ParsedDictation.Text &&
                (c.exact == null || (r as ParsedDictation.Text).value == c.exact)
            Expect.FORMATTING -> r is ParsedDictation.Formatting &&
                (r as ParsedDictation.Formatting).rendered.let { rendered ->
                    rendered in formattingRenderings && (c.exact == null || rendered == c.exact)
                }
            Expect.EDITING -> r is ParsedDictation.Editing
            Expect.REJECTED -> r is ParsedDictation.Rejected
        }
        if (!ok) failures.add("${c.id}: [${c.input}] -> $r, expected ${c.expect}${c.exact?.let { "==$it" } ?: ""}")
    }

    private fun checkPair(p: Pair, failures: MutableList<String>) {
        val r = RefinementValidator.validate(p.original, p.candidate, RefinementMode.FAITHFUL)
        val ok = if (p.accept) r is RefinementValidator.Result.Accept
        else r is RefinementValidator.Result.Reject
        if (!ok) failures.add("${p.id}: [${p.original}] -> [${p.candidate}] = $r, expected accept=${p.accept}")
    }

    // ---- family 1: plain dictation, both command policies (TEXT either way) ----

    private fun plainCases(): List<Case> {
        val out = mutableListOf<Case>()
        val enS = listOf(
            "Meet me at the station at half past six", "Send the invoice to accounting today",
            "Call Alice tomorrow morning", "The package arrives on Friday",
            "My address is 12 Baker Street London", "Email me at marius at example dot com",
            "We need milk bread and apples", "The meeting moved to room three oh four",
            "Happy birthday dear Jonas", "Please water the plants while I am away",
            "The train leaves platform nine at noon", "Bring the red folder to the office",
            "Dinner is at seven with the neighbors", "The dog needs a walk in the park",
            "I left my keys on the kitchen table", "The report is due Monday morning",
            "She sells seashells by the seashore", "Our flight boards at gate twenty two",
            "The library closes at eight pm", "Water boils at one hundred degrees",
        )
        val deS = listOf(
            "Triff mich am Bahnhof um halb sieben", "Schick die Rechnung heute an die Buchhaltung",
            "Ruf Alice morgen früh an", "Das Paket kommt am Freitag an",
            "Meine Adresse ist Hauptstraße zwölf Berlin", "Schreib mir an marius at example dot com",
            "Wir brauchen Milch Brot und Äpfel", "Das Meeting ist in Raum dreihundertvier",
            "Alles Gute zum Geburtstag Jonas", "Gieß bitte die Blumen während ich weg bin",
            "Der Zug fährt um zwölf von Gleis neun", "Bring den roten Ordner ins Büro",
            "Abendessen ist um sieben bei den Nachbarn", "Der Hund muss in den Park",
            "Ich habe die Schlüssel auf dem Tisch liegen lassen", "Der Bericht ist Montag früh fällig",
            "Sie verkauft Muscheln am Meer", "Unser Flug startet an Gate zweiundzwanzig",
            "Die Bibliothek schließt um zwanzig Uhr", "Wasser kocht bei einhundert Grad",
        )
        val esS = listOf(
            "Nos vemos en la estación a las seis y media", "Envía la factura a contabilidad hoy",
            "Llama a Alicia mañana por la mañana", "El paquete llega el viernes",
            "Mi dirección es Calle Mayor doce Madrid", "Escríbeme a marius at example dot com",
            "Necesitamos leche pan y manzanas", "La reunión es en la sala trescientos cuatro",
            "Feliz cumpleaños querido Jonas", "Riega las plantas mientras estoy fuera",
            "El tren sale del andén nueve al mediodía", "Trae la carpeta roja a la oficina",
            "La cena es a las siete con los vecinos", "El perro necesita pasear en el parque",
            "Dejé las llaves en la mesa de la cocina", "El informe vence el lunes por la mañana",
            "Ella vende conchas junto al mar", "Nuestro vuelo embarca por la puerta veintidós",
            "La biblioteca cierra a las ocho", "El agua hierve a cien grados",
        )
        val frS = listOf(
            "Retrouve-moi à la gare à six heures et demie", "Envoie la facture à la comptabilité",
            "Appelle Alice demain matin", "Le colis arrive vendredi",
            "Mon adresse est douze rue des Lilas Paris", "Écris-moi à marius at example dot com",
            "Il nous faut du lait du pain et des pommes", "La réunion est en salle trois cent quatre",
            "Joyeux anniversaire cher Jonas", "Arrose les plantes pendant mon absence",
            "Le train part du quai neuf à midi", "Apporte le dossier rouge au bureau",
            "Le dîner est à sept heures chez les voisins", "Le chien doit se promener au parc",
            "J’ai laissé mes clés sur la table", "Le rapport est dû lundi matin",
            "Elle vend des coquillages au bord de la mer", "Notre vol embarque porte vingt-deux",
            "La bibliothèque ferme à vingt heures", "L’eau bout à cent degrés",
        )
        val packs = listOf(en to enS, de to deS, es to esS, fr to frS)
        for ((lang, sentences) in packs) {
            for ((i, s) in sentences.withIndex()) {
                out.add(Case("plain-${langName(lang)}-$i-on", lang, localOn, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
                out.add(Case("plain-${langName(lang)}-$i-off", lang, off, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
                out.add(Case("plain-${langName(lang)}-$i-rem", lang, remoteOn, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
            }
        }
        // Second batch: short replies and service phrases.
        val extra = listOf(
            en to listOf("Yes please", "No thank you", "See you soon", "Good morning", "Thanks a lot",
                "On my way", "Call me back", "Sounds good", "No problem", "Take care"),
            de to listOf("Ja bitte", "Nein danke", "Bis bald", "Guten Morgen", "Vielen Dank",
                "Bin unterwegs", "Ruf zurück", "Klingt gut", "Kein Problem", "Mach's gut"),
            es to listOf("Sí por favor", "No gracias", "Hasta pronto", "Buenos días", "Muchas gracias",
                "Voy en camino", "Devuélveme la llamada", "Suena bien", "Sin problema", "Cuídate"),
            fr to listOf("Oui s’il vous plaît", "Non merci", "À bientôt", "Bonjour", "Merci beaucoup",
                "J’arrive", "Rappelle-moi", "Ça marche", "Pas de problème", "Prends soin de toi"),
        )
        for ((lang, sentences) in extra) {
            for ((i, s) in sentences.withIndex()) {
                out.add(Case("plainx-${langName(lang)}-$i-on", lang, localOn, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
                out.add(Case("plainx-${langName(lang)}-$i-off", lang, off, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
            }
        }
        return out
    }

    private fun langName(l: ResolvedUtteranceLanguage): String = when (l) {
        is ResolvedUtteranceLanguage.Known -> l.language.code
        ResolvedUtteranceLanguage.Unknown -> "unknown"
    }

    // ---- family 2: number/unit/date/currency sequences (TEXT + identical Accept) ----

    private fun numberCases(): List<Case> {
        val out = mutableListOf<Case>()
        val nums = listOf(
            en to listOf("Pay fifty Euro tomorrow", "The dose is five mg daily", "Meet at half past nine",
                "Our date is 2026-09-01", "It costs one thousand dollars", "Three to five people came",
                "The discount is twenty percent", "Call me at nine thirty", "The box weighs two kg",
                "Pi is about three point one four"),
            de to listOf("Zahle fünfzig Euro morgen", "Die Dosis ist fünf mg täglich", "Treffen um halb zehn",
                "Unser Termin ist 2026-09-01", "Es kostet eintausend Dollar", "Drei bis fünf Leute kamen",
                "Der Rabatt beträgt zwanzig Prozent", "Ruf mich um halb zehn an", "Die Kiste wiegt zwei kg",
                "Pi ist etwa drei Komma eins vier"),
            es to listOf("Paga cincuenta euros mañana", "La dosis es cinco mg al día", "Nos vemos a las nueve y media",
                "Nuestra cita es 2026-09-01", "Cuesta mil dólares", "Vinieron de tres a cinco personas",
                "El descuento es del veinte por ciento", "Llámame a las nueve y media", "La caja pesa dos kg",
                "Pi es aproximadamente tres coma catorce"),
            fr to listOf("Paie cinquante euros demain", "La dose est de cinq mg par jour", "On se voit à neuf heures et demie",
                "Notre rendez-vous est le 2026-09-01", "Ça coûte mille dollars", "Trois à cinq personnes sont venues",
                "La remise est de vingt pour cent", "Appelle-moi à neuf heures et demie", "La boîte pèse deux kg",
                "Pi vaut environ trois virgule quatorze"),
        )
        for ((lang, sentences) in nums) {
            for ((i, s) in sentences.withIndex()) {
                out.add(Case("num-${langName(lang)}-$i", lang, localOn, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
                out.add(Case("num-${langName(lang)}-$i-off", lang, off, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
                out.add(Case("num-${langName(lang)}-$i-rem", lang, remoteOn, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
            }
        }
        return out
    }

    private fun numberPairs(): List<Pair> {
        val out = mutableListOf<Pair>()
        val kept = listOf(
            "The price is 50 Euro." to "The price is 50 Euro.",
            "Take 5 mg in the morning." to "Take 5 mg in the morning.",
            "Meeting on 2026-09-01 at 9:30." to "Meeting on 2026-09-01 at 9:30.",
        )
        for ((i, pr) in kept.withIndex()) out.add(Pair("numpair-keep-$i", pr.first, pr.second, true))
        val dropped = listOf(
            "The price is 50 Euro." to "The price is Euro.",
            "Take 5 mg in the morning." to "Take 5 in the morning.",
            "Meeting on 2026-09-01 at 9:30." to "Meeting on 2026-09-01.",
            "It costs 1,000 dollars." to "It costs 1.000 dollars.",
        )
        for ((i, pr) in dropped.withIndex()) out.add(Pair("numpair-drop-$i", pr.first, pr.second, false))
        return out
    }

    // ---- family 3: whole-utterance formatting commands (FORMATTING, rendered in set) ----

    private fun formattingCases(): List<Case> {
        val out = mutableListOf<Case>()
        val cmds = listOf(
            en to listOf("period", "comma", "question mark", "exclamation mark", "colon",
                "new line", "new paragraph", "Period", "  question mark  ", "QUESTION MARK"),
            de to listOf("Punkt", "Komma", "Fragezeichen", "Ausrufezeichen", "FRAGEZEICHEN",
                "neue Zeile", "neuer Absatz", "punkt", "  Komma  ", "PUNKT"),
            es to listOf("punto", "coma", "signo de interrogación", "signo de exclamación", "COMA",
                "nueva línea", "nuevo párrafo", "Punto", "  coma  ", "PUNTO"),
            fr to listOf("point", "virgule", "point d'interrogation", "point d’exclamation", "deux points",
                "nouvelle ligne", "nouveau paragraphe", "Point", "  virgule  ", "POINT"),
        )
        for ((lang, phrases) in cmds) {
            for ((i, p) in phrases.withIndex()) {
                out.add(Case("fmt-${langName(lang)}-$i", lang, localOn, AcousticBasis.SPEECH_PRESENT, p, Expect.FORMATTING))
                out.add(Case("fmt-${langName(lang)}-$i-rem", lang, remoteOn, AcousticBasis.SPEECH_PRESENT, p, Expect.FORMATTING))
                out.add(Case("fmt-${langName(lang)}-$i-off", lang, off, AcousticBasis.SPEECH_PRESENT, p, Expect.TEXT))
                out.add(Case("fmt-${langName(lang)}-$i-unk", unknown, localOn, AcousticBasis.SPEECH_PRESENT, p, Expect.TEXT))
            }
        }
        // Extra EN punctuation vocabulary with the same policy matrix.
        for ((i, p) in listOf("dash", "semicolon", "full stop", "open quote", "close quote").withIndex()) {
            out.add(Case("fmtx-en-$i", en, localOn, AcousticBasis.SPEECH_PRESENT, p, Expect.FORMATTING))
            out.add(Case("fmtx-en-$i-off", en, off, AcousticBasis.SPEECH_PRESENT, p, Expect.TEXT))
        }
        return out
    }

    // ---- family 4: medial singles stay literal; destructive policy matrix ----

    private fun medialCases(): List<Case> {
        val out = mutableListOf<Case>()
        val medials = listOf(
            en to listOf("hello comma world", "say period after this", "well point taken",
                "a comma here and a period there", "no punctuation here at all"),
            de to listOf("hallo Komma Welt", "sag Punkt danach", "ein Komma hier und ein Punkt dort",
                "Fragezeichen hier und Fragezeichen dort", "Punkt am Satzanfang und Punkt am Ende"),
            es to listOf("hola coma mundo", "di punto después", "una coma aquí y un punto allí",
                "signo qué signo", "punto al inicio y punto al final"),
            fr to listOf("bonjour virgule monde", "dis point après", "une virgule ici et un point là",
                "quel point par ici", "point au début et point à la fin"),
        )
        for ((lang, phrases) in medials) {
            for ((i, p) in phrases.withIndex()) {
                out.add(Case("medial-${langName(lang)}-$i", lang, localOn, AcousticBasis.SPEECH_PRESENT, p, Expect.TEXT, exact = p))
                out.add(Case("medial-${langName(lang)}-$i-off", lang, off, AcousticBasis.SPEECH_PRESENT, p, Expect.TEXT))
                out.add(Case("medial-${langName(lang)}-$i-rem", lang, remoteOn, AcousticBasis.SPEECH_PRESENT, p, Expect.TEXT))
            }
        }
        return out
    }

    private fun destructiveCases(): List<Case> {
        val out = mutableListOf<Case>()
        val verbs = listOf(
            en to listOf("delete that", "scratch that"),
            de to listOf("lösche das"),
            es to listOf("borra eso"),
            fr to listOf("efface ça"),
        )
        for ((lang, phrases) in verbs) {
            for ((i, p) in phrases.withIndex()) {
                val tag = "${langName(lang)}-$i"
                out.add(Case("del-$tag-local", lang, localOn, AcousticBasis.SPEECH_PRESENT, p, Expect.EDITING))
                out.add(Case("del-$tag-remote", lang, remoteOn, AcousticBasis.SPEECH_PRESENT, p, Expect.TEXT))
                out.add(Case("del-$tag-off", lang, off, AcousticBasis.SPEECH_PRESENT, p, Expect.TEXT))
                out.add(Case("del-$tag-unknown", unknown, localOn, AcousticBasis.SPEECH_PRESENT, p, Expect.TEXT))
                out.add(Case("del-$tag-nospeech", lang, localOn, AcousticBasis.NO_SPEECH, p, Expect.REJECTED))
                out.add(Case("del-$tag-unkbasis", lang, localOn, AcousticBasis.UNKNOWN, p, Expect.EDITING))
            }
        }
        // Case/whitespace variants of genuine commands keep authority.
        for ((i, p) in listOf("Delete That", "  delete that  ", "DELETE THAT", "Lösche Das").withIndex()) {
            val lang = if (p == "Lösche Das") de else en
            out.add(Case("delvar-$i", lang, localOn, AcousticBasis.SPEECH_PRESENT, p, Expect.EDITING))
        }
        return out
    }

    // ---- family 5: sentinel literals + unknown language + speaker labels ----

    private fun sentinelCases(): List<Case> {
        val out = mutableListOf<Case>()
        val sentinels = listOf(
            "__DELETE_LAST__", "__DELETE_SENTENCE__", "__delete_last__", "__Delete_Sentence__",
            "  __DELETE_LAST__  ", "__DELETE_LAST__.", "__DELETE_LAST__!", "\"__DELETE_LAST__\"",
        )
        val langs = listOf(en to localOn, de to localOn, es to remoteOn, fr to off)
        for ((li, pr) in langs.withIndex()) {
            val (lang, policy) = pr
            for ((i, s) in sentinels.withIndex()) {
                out.add(Case("sent-$li-$i", lang, policy, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
                out.add(Case("sentU-$li-$i", unknown, localOn, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
                out.add(Case("sentN-$li-$i", lang, policy, AcousticBasis.NO_SPEECH, s, Expect.REJECTED))
            }
        }
        return out
    }

    private fun unknownCases(): List<Case> {
        val out = mutableListOf<Case>()
        val samples = listOf(
            "delete that", "new paragraph", "question mark", "marius at example dot com",
            "hello comma world", "50 Euro nein 15 Euro", "lorem ipsum dolor sit amet",
            "что-то по-русски", "日本語のテスト", "你好世界",
        )
        for ((i, s) in samples.withIndex()) {
            out.add(Case("unk-$i-on", unknown, localOn, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
            out.add(Case("unk-$i-off", unknown, off, AcousticBasis.UNKNOWN, s, Expect.TEXT))
            out.add(Case("unk-$i-rem", unknown, remoteOn, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
        }
        return out
    }

    private fun speakerCases(): List<Case> {
        val out = mutableListOf<Case>()
        val samples = listOf(
            "Speaker 1: delete that now", "Sprecher 2: lösche das bitte",
            "Speaker 1: the budget is fifty Euro", "Locuteur 1: efface ça",
            "Hablante 1: borra eso ahora", "Speaker 2: new paragraph please",
            "Speaker 1: __DELETE_LAST__", "Speaker 3: question mark",
        )
        for ((i, s) in samples.withIndex()) {
            out.add(Case("spk-$i", en, localOn, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
            out.add(Case("spk-$i-off", en, off, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
        }
        return out
    }

    // ---- family 6: cleanup acceptance pairs (fillers, repetition, self-repair) ----

    private fun cleanupAcceptPairs(): List<Pair> {
        val out = mutableListOf<Pair>()
        val goods = listOf(
            "Um, let's meet at two." to "Let's meet at two.",
            "Ähm, das kostet fünf Euro." to "Das kostet fünf Euro.",
            "Este, nos vemos mañana." to "Nos vemos mañana.",
            "Euh, on se voit demain." to "On se voit demain.",
            "I I think we should go now." to "I think we should go now.",
            "Wir wir sollten jetzt gehen." to "Wir sollten jetzt gehen.",
            "Vamos vamos a la playa." to "Vamos a la playa.",
            "On on y va maintenant." to "On y va maintenant.",
            "Let's meet Tuesday, no, Wednesday." to "Let's meet Wednesday.",
            "Treffen wir uns Dienstag, nein, Mittwoch." to "Treffen wir uns Mittwoch.",
            // NOTE: determiner-interrupted repair ("martes, no, el miércoles") is
            // conservatively rejected (safe fallback, not corruption) — only the
            // direct "X, no, Y" pattern below is in the supported catalogue.
            "Nos vemos el martes, no, miércoles." to "Nos vemos el miércoles.",
            "On se voit mardi, non, mercredi." to "On se voit mercredi.",
            "The price is 50 Euro, nein, 15 Euro." to "The price is 15 Euro.",
            "hello world" to "Hello world.",
            "HELLO WORLD TODAY" to "Hello world today.",
            "  extra   spaces   here  " to "Extra spaces here.",
        )
        for ((i, pr) in goods.withIndex()) out.add(Pair("clean-ok-$i", pr.first, pr.second, true))
        return out
    }

    // ---- family 7: meaning-violation pairs (must Reject in FAITHFUL) ----

    private fun violationPairs(): List<Pair> {
        val out = mutableListOf<Pair>()
        val bads = listOf(
            "Do not enter the room." to "Enter the room.",
            "Ich kann nicht kommen." to "Ich kann kommen.",
            "No toques ese botón." to "Toca ese botón.",
            "Ne touche pas ce bouton." to "Touche ce bouton.",
            "You may leave early." to "You must leave early.",
            "I think it might rain." to "It will rain.",
            "Vielleicht regnet es morgen." to "Es regnet morgen.",
            "Give 5 mg twice daily." to "Give 5 g twice daily.",
            "Send it to Alice, not Bob." to "Send it to Bob.",
            "The meeting is before lunch." to "The meeting is after lunch.",
        )
        for ((i, pr) in bads.withIndex()) out.add(Pair("viol-$i", pr.first, pr.second, false))
        // Assistant-commentary injection is rejected even when the body survives.
        out.add(Pair("viol-commentary", "Buy milk tomorrow.", "Sure! Buy milk tomorrow.", false))
        out.add(Pair("viol-markup", "Buy milk tomorrow.", "Buy ```milk``` tomorrow.", false))
        return out
    }

    // ---- family 8: symbols/emoji need a real speech episode ----

    private fun symbolCases(): List<Case> {
        val out = mutableListOf<Case>()
        val legit = listOf("?", "!", "...", ":-)", "❤", "🎉", "???", "?!", ". . .")
        for ((i, s) in legit.withIndex()) {
            out.add(Case("sym-ok-$i", en, localOn, AcousticBasis.SPEECH_PRESENT, s, Expect.TEXT))
            out.add(Case("sym-no-$i", en, localOn, AcousticBasis.NO_SPEECH, s, Expect.REJECTED))
            out.add(Case("sym-unk-$i", en, localOn, AcousticBasis.UNKNOWN, s, Expect.REJECTED))
        }
        return out
    }

    // ---- family 9: quoted/code spans stay literal ----

    private fun quotedCases(): List<Case> {
        val out = mutableListOf<Case>()
        val samples = listOf(
            en to "\"delete that\" he said",
            en to "run `delete that` now",
            de to "„lösche das“ sagte er",
            en to "\"question mark\" in quotes",
            fr to "« efface ça » dit-il",
            es to "`borra eso` dijo él",
            en to "the command \"scratch that\" failed",
            de to "Code `neuer Absatz` hier",
            fr to "le « point » final",
            es to "\"coma\" entre comillas",
        )
        for ((i, pr) in samples.withIndex()) {
            out.add(Case("quot-$i", pr.first, localOn, AcousticBasis.SPEECH_PRESENT, pr.second, Expect.TEXT))
            out.add(Case("quot-$i-off", pr.first, off, AcousticBasis.SPEECH_PRESENT, pr.second, Expect.TEXT))
        }
        return out
    }

    // ---- family 10: padded/case variants of plain dictation (still TEXT) ----

    private fun paddedCases(): List<Case> {
        val out = mutableListOf<Case>()
        val bases = listOf(
            en to "Call Alice tomorrow morning",
            de to "Ruf Alice morgen früh an",
            es to "Llama a Alicia mañana",
            fr to "Appelle Alice demain matin",
            en to "The package arrives on Friday",
            de to "Das Paket kommt am Freitag an",
            es to "El paquete llega el viernes",
            fr to "Le colis arrive vendredi",
        )
        for ((i, pr) in bases.withIndex()) {
            out.add(Case("pad-$i-ws", pr.first, localOn, AcousticBasis.SPEECH_PRESENT, "  ${pr.second}  ", Expect.TEXT))
            out.add(Case("pad-$i-up", pr.first, localOn, AcousticBasis.SPEECH_PRESENT, pr.second.uppercase(), Expect.TEXT))
            out.add(Case("pad-$i-rem", pr.first, remoteOn, AcousticBasis.SPEECH_PRESENT, "  ${pr.second}  ", Expect.TEXT))
            out.add(Case("pad-$i-unk", unknown, localOn, AcousticBasis.SPEECH_PRESENT, pr.second, Expect.TEXT))
        }
        return out
    }

    // ---- family 11: ITN-shaped digit/email strings (parser level: TEXT) ----

    private fun itnCases(): List<Case> {
        val out = mutableListOf<Case>()
        val samples = listOf(
            en to "call 0176 12345678 tomorrow",
            de to "meine Nummer ist 030 123456",
            es to "llama al 600 123 456",
            fr to "appelle le 06 12 34 56 78",
            en to "room three oh four is ready",
            de to "Zimmer drei null vier ist frei",
            en to "order T807D twice",
            fr to "dossier T807D urgent",
        )
        for ((i, pr) in samples.withIndex()) {
            out.add(Case("itn-$i", pr.first, localOn, AcousticBasis.SPEECH_PRESENT, pr.second, Expect.TEXT))
            out.add(Case("itn-$i-off", pr.first, off, AcousticBasis.SPEECH_PRESENT, pr.second, Expect.TEXT))
        }
        return out
    }

    // ---- family 12: date formats across languages ----

    private fun dateCases(): List<Case> {
        val out = mutableListOf<Case>()
        val samples = listOf(
            en to "the deadline is 2026/09/01",
            de to "die Frist ist 01.09.2026",
            es to "el plazo es 1 de septiembre de 2026",
            fr to "la date limite est le 1er septembre 2026",
            en to "born on July fourth nineteen ninety",
            de to "geboren am vierten Juli neunzehnneunzig",
        )
        for ((i, pr) in samples.withIndex()) {
            out.add(Case("date-$i-on", pr.first, localOn, AcousticBasis.SPEECH_PRESENT, pr.second, Expect.TEXT))
            out.add(Case("date-$i-off", pr.first, off, AcousticBasis.SPEECH_PRESENT, pr.second, Expect.TEXT))
            out.add(Case("date-$i-rem", pr.first, remoteOn, AcousticBasis.SPEECH_PRESENT, pr.second, Expect.TEXT))
        }
        return out
    }

    // ---- family 13: correction-plan matrix ----

    private data class CCase(
        val id: String,
        val target: CorrectionTarget,
        val current: EditorText,
        val field: String?,
        val gen: Long,
        val replacement: String,
        val expectApply: Boolean,
    )

    private fun correctionCases(): List<CCase> {
        val out = mutableListOf<CCase>()
        val items = listOf(
            Triple("Call Alice tomorrow", "Alice", "Alicia"),
            Triple("Ruf Jonas morgen an", "Jonas", "Jon"),
            Triple("Llama a Marta mañana", "Marta", "Martina"),
            Triple("Appelle Pierre demain", "Pierre", "Pierrette"),
            Triple("Send the invoice today", "invoice", "receipt"),
            Triple("Buy milk bread and apples", "bread", "butter"),
            Triple("Der Bericht ist fällig", "Bericht", "Antrag"),
            Triple("El informe vence hoy", "informe", "contrato"),
        )
        for ((i, item) in items.withIndex()) {
            val (sentence, word, fix) = item
            val start = sentence.indexOf(word)
            val t = CorrectionTarget("f1", 7L, start, word, sentence, "")
            val s = EditorText(sentence, "")
            out.add(CCase("corr-$i-apply", t, s, "f1", 7L, fix, true))
            out.add(CCase("corr-$i-field", t, s, "f2", 7L, fix, false))
            out.add(CCase("corr-$i-gen", t, s, "f1", 8L, fix, false))
            out.add(CCase("corr-$i-moved", t, EditorText("$sentence!", ""), "f1", 7L, fix, false))
            out.add(CCase("corr-$i-literal-repl", t, s, "f1", 7L, "delete that", true))
        }
        return out
    }

    // ---- family 14: cross-language policy matrix ----

    private fun routingCases(): List<Case> {
        val out = mutableListOf<Case>()
        val sentences = listOf(
            en to "the quick brown fox jumps",
            de to "der schnelle braune Fuchs springt",
            es to "el rápido zorro marrón salta",
            fr to "le rapide renard brun saute",
            en to "pack my box with five dozen jugs",
            de to "Pack meine Kiste mit fünf Dutzend Krügen",
        )
        for ((i, pr) in sentences.withIndex()) {
            for (lang in listOf(en, de, es, fr, unknown)) {
                out.add(Case("route-$i-${langName(lang)}", lang, localOn, AcousticBasis.SPEECH_PRESENT, pr.second, Expect.TEXT))
            }
            out.add(Case("route-$i-off", pr.first, off, AcousticBasis.SPEECH_PRESENT, pr.second, Expect.TEXT))
            out.add(Case("route-$i-unk", pr.first, localOn, AcousticBasis.UNKNOWN, pr.second, Expect.TEXT))
        }
        return out
    }

    // ---- runner ----

    @Test
    fun thousandUtteranceCorpus() {
        val cases = plainCases() + numberCases() + formattingCases() + medialCases() +
            destructiveCases() + sentinelCases() + unknownCases() + speakerCases() +
            symbolCases() + quotedCases() + paddedCases() + itnCases() + dateCases() +
            routingCases()
        val pairs = numberPairs() + cleanupAcceptPairs() + violationPairs()
        println("CORPUS cases=${cases.size} pairs=${pairs.size}")
        assertTrue("corpus must hold 1000+ utterances, has ${cases.size}", cases.size >= 1000)
        val failures = mutableListOf<String>()
        for (c in cases) check(c, failures)
        for (p in pairs) checkPair(p, failures)
        if (failures.isNotEmpty()) {
            fail("corpus failures ${failures.size}/${cases.size + pairs.size}, first 20:\n" +
                failures.take(20).joinToString("\n"))
        }
    }

    @Test
    fun correctionMatrix() {
        val cases = correctionCases()
        assertTrue("correction matrix must hold 40+ cases, has ${cases.size}", cases.size >= 40)
        val failures = mutableListOf<String>()
        for (c in cases) {
            val r = CorrectionPlan.planCorrection(c.target, c.current, c.field, c.gen, c.replacement)
            val ok = if (c.expectApply) r is com.sprich.app.input.correction.CorrectionResult.Apply
            else r is com.sprich.app.input.correction.CorrectionResult.Rejected
            if (!ok) failures.add("${c.id}: -> $r, expected apply=${c.expectApply}")
            if (c.expectApply && c.id.endsWith("-literal-repl") && r is com.sprich.app.input.correction.CorrectionResult.Apply) {
                if (!r.newBefore.contains("delete that")) failures.add("${c.id}: replacement interpreted!")
            }
        }
        if (failures.isNotEmpty()) fail("correction failures:\n" + failures.take(20).joinToString("\n"))
    }
}
