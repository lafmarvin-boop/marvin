package com.marvin.assistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentParserTest {

    private val parser = IntentParser()

    @Test fun `appelle est ancre au debut`() {
        // "comment je m'appelle marvin" ne doit PAS matcher CallContact
        val r = parser.parse("comment je m'appelle marvin")
        assertTrue("Expected Unknown, got $r", r is MarvinIntent.Unknown)
    }

    @Test fun `appelle marie matche CallContact`() {
        val r = parser.parse("appelle marie")
        assertEquals(MarvinIntent.CallContact("marie"), r)
    }

    @Test fun `calc simple court-circuite Claude`() {
        val r = parser.parse("combien font 12 fois 8")
        assertTrue(r is MarvinIntent.LocalAnswer)
        assertTrue((r as MarvinIntent.LocalAnswer).text.contains("96"))
    }

    @Test fun `calc puissance`() {
        val r = parser.parse("2 puissance 10")
        assertTrue(r is MarvinIntent.LocalAnswer)
        assertTrue((r as MarvinIntent.LocalAnswer).text.contains("1024"))
    }

    @Test fun `racine carree`() {
        val r = parser.parse("racine carrée de 144")
        assertTrue(r is MarvinIntent.LocalAnswer)
        assertTrue((r as MarvinIntent.LocalAnswer).text.contains("12"))
    }

    @Test fun `wipe all`() {
        assertEquals(MarvinIntent.WipeAllData, parser.parse("efface tout"))
        assertEquals(MarvinIntent.WipeAllData, parser.parse("supprime toutes mes données"))
    }

    @Test fun `sleep mode`() {
        assertEquals(MarvinIntent.GoToSleep, parser.parse("va dormir"))
        assertEquals(MarvinIntent.GoToSleep, parser.parse("fais dodo"))
        assertEquals(MarvinIntent.GoToSleep, parser.parse("hors service"))
    }

    @Test fun `spotify play`() {
        assertEquals(MarvinIntent.Spotify.Play, parser.parse("joue de la musique"))
    }

    @Test fun `sms send`() {
        val r = parser.parse("envoie un sms à marie pour dire bonjour")
        assertTrue(r is MarvinIntent.SendSms)
        assertEquals("marie", (r as MarvinIntent.SendSms).recipient)
        assertEquals("bonjour", r.message)
    }

    @Test fun `correction add`() {
        val r = parser.parse("quand je dis l'air comprends l'heure")
        assertTrue(r is MarvinIntent.AddCorrection)
        assertEquals("l'air", (r as MarvinIntent.AddCorrection).heard)
        assertEquals("l'heure", r.meant)
    }

    @Test fun `reminder dans X minutes`() {
        val r = parser.parse("rappelle moi de sortir le four dans 10 minutes")
        assertTrue(r is MarvinIntent.AddReminder)
        assertTrue((r as MarvinIntent.AddReminder).text.contains("sortir"))
    }

    @Test fun `shopping add`() {
        val r = parser.parse("ajoute du lait à la liste de courses")
        assertTrue(r is MarvinIntent.ShoppingAdd)
        assertEquals("du lait", (r as MarvinIntent.ShoppingAdd).item)
    }

    @Test fun `routine`() {
        val r = parser.parse("lance ma routine matin")
        assertTrue(r is MarvinIntent.RunRoutine)
        assertEquals("matin", (r as MarvinIntent.RunRoutine).name)
    }

    @Test fun `traduction`() {
        val r = parser.parse("traduis bonjour en anglais")
        assertTrue(r is MarvinIntent.Translate)
        val t = r as MarvinIntent.Translate
        assertEquals("bonjour", t.text)
        assertEquals("anglais", t.targetLanguage)
    }

    @Test fun `memory remember`() {
        val r = parser.parse("souviens-toi que ma femme s'appelle marie")
        assertTrue(r is MarvinIntent.RememberFact)
    }

    @Test fun `help`() {
        assertEquals(MarvinIntent.Help, parser.parse("qu'est-ce que tu sais faire"))
        assertEquals(MarvinIntent.Help, parser.parse("aide-moi"))
    }

    @Test fun `unknown`() {
        val r = parser.parse("bla bla bla blip")
        assertTrue("Expected Unknown, got $r", r is MarvinIntent.Unknown)
    }

    @Test fun `blank input`() {
        val r = parser.parse("")
        assertTrue(r is MarvinIntent.Unknown)
    }

    @Test fun `read sms`() {
        assertEquals(MarvinIntent.ReadRecentSms(), parser.parse("lis mes derniers sms"))
        val r = parser.parse("lis les messages de marie")
        assertTrue(r is MarvinIntent.ReadRecentSms)
        assertEquals("marie", (r as MarvinIntent.ReadRecentSms).fromContact)
    }

    @Test fun `take photo`() {
        val r = parser.parse("prends une photo")
        assertTrue(r is MarvinIntent.TakePhotoAndAnalyze)
    }
}
