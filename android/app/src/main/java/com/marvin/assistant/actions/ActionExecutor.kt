package com.marvin.assistant.actions

import android.content.Context
import com.marvin.assistant.nlu.MarvinIntent

class ActionExecutor(private val context: Context) {

    private val sms = SmsAction(context)
    private val whatsApp = WhatsAppAction(context)
    private val spotify = SpotifyAction(context)
    private val waze = WazeAction(context)
    private val openApp = OpenAppAction(context)
    private val familyWall = FamilyWallAction(context)
    private val ecovacs = EcovacsAction(context)
    private val bank = BankAction(context)

    /** Returns the spoken feedback (may be empty). */
    suspend fun execute(intent: MarvinIntent): String = when (intent) {
        is MarvinIntent.SendSms -> sms.send(intent.recipient, intent.message)
        is MarvinIntent.CallContact -> sms.call(intent.recipient)
        is MarvinIntent.WhatsAppMessage -> whatsApp.send(intent.recipient, intent.message)
        is MarvinIntent.Spotify -> spotify.handle(intent)
        is MarvinIntent.WazeNavigate -> waze.navigate(intent.destination)
        is MarvinIntent.OpenApp -> openApp.open(intent.appKey)
        is MarvinIntent.BankRead -> bank.read(intent.bank, intent.request)
        MarvinIntent.FamilyWallShowLocations -> familyWall.showLocations()
        is MarvinIntent.Ecovacs -> ecovacs.handle(intent.command)
        is MarvinIntent.Unknown -> "Désolé, j'ai pas compris."
        // Ces intents sont gérés en amont par AssistantService et ne devraient
        // jamais atteindre ActionExecutor — branches vides pour exhaustivité.
        MarvinIntent.StartDiscussion,
        MarvinIntent.EndDiscussion,
        MarvinIntent.GoToSleep,
        MarvinIntent.WipeAllData,
        MarvinIntent.ListReminders,
        MarvinIntent.ClearReminders,
        MarvinIntent.ReadUnreadNotifications,
        MarvinIntent.ReadMissedCalls,
        is MarvinIntent.AddCorrection,
        is MarvinIntent.AddReminder,
        is MarvinIntent.LocalAnswer,
        is MarvinIntent.ReadRecentSms,
        is MarvinIntent.RunRoutine,
        is MarvinIntent.Translate,
        is MarvinIntent.ShoppingAdd,
        MarvinIntent.ShoppingRead,
        is MarvinIntent.ShoppingRemove,
        MarvinIntent.ShoppingClear,
        is MarvinIntent.TakePhotoAndAnalyze,
        is MarvinIntent.SmartLight,
        is MarvinIntent.SmartSwitch,
        is MarvinIntent.SmartScene,
        is MarvinIntent.RememberFact,
        is MarvinIntent.ForgetFact,
        MarvinIntent.ListMemory,
        MarvinIntent.Help,
        is MarvinIntent.CreateCalendarEvent,
        is MarvinIntent.AddNote,
        MarvinIntent.ReadNotes,
        MarvinIntent.ClearNotes,
        MarvinIntent.ReadEmails,
        MarvinIntent.RecognizeMusic,
        is MarvinIntent.StartInterpreter,
        MarvinIntent.StepsCount,
        MarvinIntent.SelfTest -> ""
    }
}
