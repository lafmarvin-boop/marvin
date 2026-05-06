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

    /** Aucun match local — à transmettre au backend LLM (Claude ou Gemma). */
    data class Unknown(val raw: String) : MarvinIntent()
}

enum class BankKind { BOURSOBANK, BANQUE_POP }
enum class BankRequest { BALANCE, LAST_OPS }
enum class EcovacsAction { START, PAUSE, DOCK }
