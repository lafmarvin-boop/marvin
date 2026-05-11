package com.marvin.assistant.nlu

/**
 * Set of actions Marvin can dispatch. Add new intents here, then handle them
 * in [com.marvin.assistant.actions.ActionExecutor].
 */
sealed class MarvinIntent {

    data class SendSms(val recipient: String, val message: String) : MarvinIntent()
    data class CallContact(val recipient: String) : MarvinIntent()
    data class WhatsAppMessage(val recipient: String, val message: String) : MarvinIntent()

    sealed class Spotify : MarvinIntent() {
        data object Play : Spotify()
        data object Pause : Spotify()
        data object Next : Spotify()
        data object Previous : Spotify()
        data class Search(val query: String) : Spotify()
    }

    data class WazeNavigate(val destination: String) : MarvinIntent()

    data class OpenApp(val appKey: String) : MarvinIntent()

    /** Read-only banking: announce balance, recent ops. */
    data class BankRead(val bank: BankKind, val request: BankRequest) : MarvinIntent()

    data object FamilyWallShowLocations : MarvinIntent()
    data class Ecovacs(val command: EcovacsAction) : MarvinIntent()

    /** L'utilisateur veut entrer en mode discussion multi-tours. */
    data object StartDiscussion : MarvinIntent()

    /** L'utilisateur veut sortir du mode discussion. */
    data object EndDiscussion : MarvinIntent()

    /** Efface toutes les données stockées (clé API, réglages, historique). */
    data object WipeAllData : MarvinIntent()

    /** Mode dodo : Jarvis ne réagit plus qu'à « bonjour ». */
    data object GoToSleep : MarvinIntent()

    /** Apprentissage : "quand je dis X comprends Y" → ajoute une correction. */
    data class AddCorrection(val heard: String, val meant: String) : MarvinIntent()

    /** Programme un rappel à un instant absolu. */
    data class AddReminder(val text: String, val triggerAtMs: Long) : MarvinIntent()

    /** Réponse calculée localement (calc, conversion, etc.) — pas d'appel LLM. */
    data class LocalAnswer(val text: String) : MarvinIntent()

    /** Lit à voix haute les SMS récents (de quelqu'un en particulier ou tous). */
    data class ReadRecentSms(val fromContact: String? = null, val limit: Int = 3) : MarvinIntent()

    /** Lit à voix haute les notifications non lues. */
    data object ReadUnreadNotifications : MarvinIntent()

    /** Lit à voix haute les appels manqués récents. */
    data object ReadMissedCalls : MarvinIntent()

    /** Lit à voix haute les emails non lus (via notifications Gmail/Outlook). */
    data object ReadEmails : MarvinIntent()

    /** Reconnaissance musicale via AudD. */
    data object RecognizeMusic : MarvinIntent()

    /** Exécute une routine pré-définie (ex. "routine matin" = méteo + agenda + news). */
    data class RunRoutine(val name: String) : MarvinIntent()

    /** Demande une traduction. Si targetLanguage absent, Claude détecte. */
    data class Translate(val text: String, val targetLanguage: String? = null) : MarvinIntent()

    /** Capture une photo et l'analyse via Claude vision. */
    data class TakePhotoAndAnalyze(val question: String = "Qu'est-ce que tu vois sur cette image ?") : MarvinIntent()

    /** Note vocale : « Jarvis prends une note : ... » */
    data class AddNote(val text: String) : MarvinIntent()
    data object ReadNotes : MarvinIntent()
    data object ClearNotes : MarvinIntent()

    /** Liste les commandes disponibles à voix haute. */
    data object Help : MarvinIntent()

    /** Crée un événement dans le calendrier Android. */
    data class CreateCalendarEvent(
        val title: String,
        val startMs: Long,
        val durationMinutes: Int = 60
    ) : MarvinIntent()

    /** Mémoire long terme : ajouter un fait. */
    data class RememberFact(val fact: String) : MarvinIntent()
    /** Mémoire long terme : oublier un fait par mot-clé. */
    data class ForgetFact(val query: String) : MarvinIntent()
    /** Mémoire long terme : énumérer ce que Jarvis sait. */
    data object ListMemory : MarvinIntent()

    /** Smart home via Home Assistant. */
    data class SmartLight(val name: String, val on: Boolean, val brightness: Int? = null) : MarvinIntent()
    data class SmartSwitch(val name: String, val on: Boolean) : MarvinIntent()
    data class SmartScene(val name: String) : MarvinIntent()

    /** Liste de courses : ajouter / lire / supprimer / vider. */
    data class ShoppingAdd(val item: String) : MarvinIntent()
    data object ShoppingRead : MarvinIntent()
    data class ShoppingRemove(val item: String) : MarvinIntent()
    data object ShoppingClear : MarvinIntent()

    /** Énumère les rappels actifs. */
    data object ListReminders : MarvinIntent()

    /** Annule tous les rappels. */
    data object ClearReminders : MarvinIntent()

    /** Aucun match local — à transmettre au backend LLM (Claude ou Gemma). */
    data class Unknown(val raw: String) : MarvinIntent()
}

enum class BankKind { BOURSOBANK, BANQUE_POP }
enum class BankRequest { BALANCE, LAST_OPS }
enum class EcovacsAction { START, PAUSE, DOCK }
