package com.marvin.assistant.nlu

/**
 * Rule-based French command parser. Order matters: first match wins.
 *
 * Pourquoi pas de LLM par défaut: les commandes sont peu nombreuses et bien
 * formées, donc les regex sont plus rapides, plus déterministes et zéro coût.
 * On peut brancher [LlmIntentParser] en fallback si on rate.
 */
class IntentParser {

    fun parse(raw: String): MarvinIntent {
        val text = raw.trim().lowercase()
        if (text.isBlank()) return MarvinIntent.Unknown(raw)

        // Calcul local : court-circuite Claude pour les opérations simples.
        // Réponse instantanée, zéro coût. Si pas reconnu comme calcul,
        // on tombe sur les rules normales.
        CalcParser.tryCompute(text)?.let { return MarvinIntent.LocalAnswer(it) }

        for (rule in rules) {
            val match = rule.regex.find(text) ?: continue
            return rule.build(match)
        }
        return MarvinIntent.Unknown(raw)
    }

    private data class Rule(val regex: Regex, val build: (MatchResult) -> MarvinIntent)

    private val rules: List<Rule> = listOf(
        // ---- Mode discussion ----
        Rule(Regex("""^(discutons|on discute|j'aimerais discuter|on parle|parlons)\b""")) {
            MarvinIntent.StartDiscussion
        },
        Rule(Regex("""^(merci|fin (?:de la )?discussion|stop|arrête (?:la )?discussion|c'est tout)\b""")) {
            MarvinIntent.EndDiscussion
        },

        // ---- Wipe complet ----
        Rule(Regex("""(efface|supprime|détruis|détruit) tout""")) { MarvinIntent.WipeAllData },
        Rule(Regex("""(efface|supprime) (?:toutes |toutes mes |mes )données""")) { MarvinIntent.WipeAllData },
        Rule(Regex("""remise à zéro (?:complète|totale)?""")) { MarvinIntent.WipeAllData },
        Rule(Regex("""reset (?:total|complet)?""")) { MarvinIntent.WipeAllData },

        // ---- Apprentissage : corrections de prononciation ----
        // "quand je dis l'air comprends l'heure"
        // "quand j'dis X tu comprends Y"
        // "quand je dis X tu entends Y"
        Rule(Regex("""quand (?:je|j')\s*dis (.+?) (?:tu )?(?:comprends|entends|note|enregistre|c'est) (.+)""")) {
            MarvinIntent.AddCorrection(it.groupValues[1].trim(), it.groupValues[2].trim())
        },

        // ---- Rappels / Timer / Réveil ----
        // "rappelle-moi de sortir le four dans 10 minutes"
        // "rappelle moi d'appeler maman à 18 heures"
        // "rappelle moi dans une heure de boire de l'eau"
        // "réveille moi à 7 heures" / "réveille-moi demain à 8 heures"
        // "mets un timer de 10 minutes"
        Rule(Regex("""(?:rappelle[- ]?moi|rappelle-moi|note) (?:de |d')(.+?) (dans .+|à .+|demain.+|pour .+)$""")) {
            val text = it.groupValues[1].trim()
            val whenStr = it.groupValues[2].trim()
            val ts = TimeParser.parse(whenStr) ?: return@Rule MarvinIntent.Unknown(it.value)
            MarvinIntent.AddReminder(text, ts)
        },
        Rule(Regex("""(?:rappelle[- ]?moi|rappelle-moi|note) (dans .+?|à .+?|demain.+?) (?:de |d'|que je |qu'on )(.+)$""")) {
            val whenStr = it.groupValues[1].trim()
            val text = it.groupValues[2].trim()
            val ts = TimeParser.parse(whenStr) ?: return@Rule MarvinIntent.Unknown(it.value)
            MarvinIntent.AddReminder(text, ts)
        },
        Rule(Regex("""(?:réveille[- ]?moi|réveille-moi)\s+(.+)$""")) {
            val whenStr = it.groupValues[1].trim()
            val ts = TimeParser.parse(whenStr) ?: return@Rule MarvinIntent.Unknown(it.value)
            MarvinIntent.AddReminder("réveil", ts)
        },
        Rule(Regex("""(?:mets |programme |lance )?un timer (?:de |sur |à )?(.+)$""")) {
            val whenStr = "dans " + it.groupValues[1].trim()
            val ts = TimeParser.parse(whenStr) ?: return@Rule MarvinIntent.Unknown(it.value)
            MarvinIntent.AddReminder("timer", ts)
        },
        Rule(Regex("""(?:liste|montre|dis|donne)(?:[- ]moi)?\s+(?:mes |les )?rappels""")) {
            MarvinIntent.ListReminders
        },
        Rule(Regex("""(?:efface|supprime|annule)\s+(?:tous |mes )?(?:les )?rappels""")) {
            MarvinIntent.ClearReminders
        },

        // ---- Mode dodo ----
        Rule(Regex("""fais (?:dodo|une sieste|la sieste)""")) { MarvinIntent.GoToSleep },
        Rule(Regex("""va dormir""")) { MarvinIntent.GoToSleep },
        Rule(Regex("""(?:mets[- ]toi |mets toi |va )en (?:pause|veille)""")) { MarvinIntent.GoToSleep },
        Rule(Regex("""hors service""")) { MarvinIntent.GoToSleep },

        // ---- Spotify ----
        Rule(Regex("""(joue|lance|mets) (?:de la )?musique""")) { MarvinIntent.Spotify.Play },
        Rule(Regex("""(?:mets|joue|lance) (.+?) sur spotify""")) {
            MarvinIntent.Spotify.Search(it.groupValues[1].trim())
        },
        Rule(Regex("""(?:reprends|continue) la musique""")) { MarvinIntent.Spotify.Play },
        Rule(Regex("""(?:pause|stop|arrête)(?: la musique)?""")) { MarvinIntent.Spotify.Pause },
        Rule(Regex("""(musique|chanson|titre|morceau) suivant""")) { MarvinIntent.Spotify.Next },
        Rule(Regex("""(musique|chanson|titre|morceau) précédent""")) { MarvinIntent.Spotify.Previous },

        // ---- Waze ----
        Rule(Regex("""(?:emmène|emmène-moi|guide|guide-moi|navigue|itinéraire|direction|va|vas|allons|emmene|emmene-moi) (?:vers |à |au |aux |chez |a )(.+)""")) {
            MarvinIntent.WazeNavigate(it.groupValues[1].trim())
        },

        // ---- SMS ----
        Rule(Regex("""(?:envoie|écris|écrit|envois) (?:un )?(?:sms|texto|message) (?:à |a )([\p{L}\s'-]+?) (?:pour (?:dire|lui dire) |disant |que )(.+)""")) {
            MarvinIntent.SendSms(it.groupValues[1].trim(), it.groupValues[2].trim())
        },
        // "lis mes derniers messages" / "lit moi les sms de marie" / "lis les messages de papa"
        Rule(Regex("""(?:lis|lit|lit-moi|lis moi|donne|montre)(?:[- ]moi)?\s+(?:les |mes )?(?:derniers? )?(?:sms|messages|texto|textos)\s+(?:de |du |reçus de )([\p{L}\s'-]+)""")) {
            MarvinIntent.ReadRecentSms(fromContact = it.groupValues[1].trim())
        },
        Rule(Regex("""(?:lis|lit|lit-moi|lis moi|donne|montre)(?:[- ]moi)?\s+(?:les |mes )?(?:derniers? )?(?:sms|messages|texto|textos)""")) {
            MarvinIntent.ReadRecentSms()
        },
        // "lis mes notifications" / "qu'est-ce que j'ai en notification"
        Rule(Regex("""(?:lis|lit|lit-moi|lis moi|donne|montre)(?:[- ]moi)?\s+(?:les |mes )?notif(?:ication)?s?""")) {
            MarvinIntent.ReadUnreadNotifications
        },
        Rule(Regex("""(?:est-ce que )?(?:j'ai|y'a|il y a)\s+(?:des |de )?notif(?:ication)?s?""")) {
            MarvinIntent.ReadUnreadNotifications
        },
        // "lis mes appels manqués" / "qui m'a appelé"
        Rule(Regex("""(?:lis|lit|donne)(?:[- ]moi)?\s+(?:les |mes )?appels?\s+(?:manqués?|en absence)""")) {
            MarvinIntent.ReadMissedCalls
        },
        Rule(Regex("""qui (?:m'a|m a)\s+appelé""")) { MarvinIntent.ReadMissedCalls },

        // ---- Routines ----
        // "lance ma routine matin" / "routine matin" / "fais la routine soir"
        Rule(Regex("""(?:lance|démarre|exécute|fais|active)\s+(?:ma |la |une )?routine\s+(.+)""")) {
            MarvinIntent.RunRoutine(it.groupValues[1].trim())
        },
        Rule(Regex("""^routine\s+(.+)""")) {
            MarvinIntent.RunRoutine(it.groupValues[1].trim())
        },

        // ---- Traduction ----
        // "traduis 'il fait beau' en anglais"
        // "comment dit-on bonjour en espagnol"
        // "traduis-moi bonjour en italien"
        Rule(Regex("""(?:traduis|traduit|traduire)(?:[- ]moi)?\s+["«]?(.+?)["»]?\s+en\s+([\p{L}]+)$""")) {
            MarvinIntent.Translate(it.groupValues[1].trim(), it.groupValues[2].trim())
        },
        Rule(Regex("""comment (?:dit-on|dit on|on dit|ça se dit)\s+["«]?(.+?)["»]?\s+en\s+([\p{L}]+)""")) {
            MarvinIntent.Translate(it.groupValues[1].trim(), it.groupValues[2].trim())
        },
        Rule(Regex("""(?:traduis|traduit)(?:[- ]moi)?\s+["«]?(.+?)["»]?$""")) {
            MarvinIntent.Translate(it.groupValues[1].trim(), null)
        },

        // ---- Help ----
        // "qu'est-ce que tu sais faire" / "aide" / "que peux-tu faire"
        Rule(Regex("""(?:qu'est-ce que tu sais faire|que peux[- ]tu faire|aide moi|aide-moi|de l'aide|liste tes commandes)""")) {
            MarvinIntent.Help
        },

        // ---- Memoire long terme ----
        // "souviens-toi que ma femme s'appelle Marie"
        // "rappelle-toi que mon code wifi est ABC123"
        // "memorise que mon anniversaire est le 12 mai"
        // "note que je travaille chez X"
        Rule(Regex("""(?:souviens[- ]?toi|rappelle[- ]?toi|memorise|memorize|note)\s+(?:que |qu')(.+)$""")) {
            MarvinIntent.RememberFact(it.groupValues[1].trim())
        },
        // "oublie X" / "efface ce que tu sais sur X"
        Rule(Regex("""(?:oublie|efface)\s+(?:ce que tu sais sur |que )?(.+)$""")) {
            MarvinIntent.ForgetFact(it.groupValues[1].trim())
        },
        // "qu'est-ce que tu sais sur moi" / "que sais-tu de moi"
        Rule(Regex("""(?:qu'est-ce que tu sais|que sais-tu)(?:\s+sur\s+moi)?""")) {
            MarvinIntent.ListMemory
        },

        // ---- Smart home (Home Assistant) ----
        // "allume la lampe du salon" / "éteins la lampe de la cuisine"
        // "allume la lampe du salon à 50 %"
        // "allume la prise du bureau"
        // "active la scène cinéma" / "lance la scène nuit"
        Rule(Regex("""(?:allume|active|mets?[- ]toi)\s+(?:la |les |le |l')?(?:lampe|lumière|lumiere)s?\s+(?:du |de la |de l'|d'|des )(.+?)\s+(?:à|a)\s+(\d{1,3})\s*(?:%|pourcent|pour cent)""")) {
            MarvinIntent.SmartLight(it.groupValues[1].trim(), on = true, brightness = it.groupValues[2].toIntOrNull())
        },
        Rule(Regex("""(?:allume|active)\s+(?:la |les |le |l')?(?:lampe|lumière|lumiere)s?\s+(?:du |de la |de l'|d'|des )(.+)""")) {
            MarvinIntent.SmartLight(it.groupValues[1].trim(), on = true)
        },
        Rule(Regex("""(?:éteins|eteins|éteint|coupe)\s+(?:la |les |le |l')?(?:lampe|lumière|lumiere)s?\s+(?:du |de la |de l'|d'|des )(.+)""")) {
            MarvinIntent.SmartLight(it.groupValues[1].trim(), on = false)
        },
        Rule(Regex("""(?:allume|active)\s+(?:la |le |l')?prise\s+(?:du |de la |de l'|d'|des )(.+)""")) {
            MarvinIntent.SmartSwitch(it.groupValues[1].trim(), on = true)
        },
        Rule(Regex("""(?:éteins|eteins|coupe)\s+(?:la |le |l')?prise\s+(?:du |de la |de l'|d'|des )(.+)""")) {
            MarvinIntent.SmartSwitch(it.groupValues[1].trim(), on = false)
        },
        Rule(Regex("""(?:active|lance|démarre|déclenche)\s+(?:la |le |l')?scène\s+(.+)""")) {
            MarvinIntent.SmartScene(it.groupValues[1].trim())
        },

        // ---- Vision / photo ----
        // "prends une photo et dis-moi ce que c'est"
        // "regarde ça" / "décris ce que tu vois"
        // "prends une photo et combien y'a t'il de pommes"
        Rule(Regex("""(?:prends? |fait |fais )(?:une |la )?(?:photo|image|capture)(?:\s+et\s+(.+))?""")) {
            val q = it.groupValues[1].trim()
            MarvinIntent.TakePhotoAndAnalyze(
                if (q.isBlank()) "Qu'est-ce que tu vois sur cette image ? Décris-la." else q
            )
        },
        Rule(Regex("""(?:décris|regarde|analyse|qu'est-ce que tu vois) ?(?:une |la |cette )?(?:photo|image|chose|ça|ceci)""")) {
            MarvinIntent.TakePhotoAndAnalyze()
        },

        // ---- Liste de courses ----
        // "ajoute du lait à la liste" / "ajoute 6 œufs à la liste de courses"
        // "note dans la liste : du pain"
        Rule(Regex("""(?:ajoute|note|mets|rajoute)\s+(.+?)\s+(?:à|a|sur|dans)\s+(?:la|ma)?\s*liste(?: de courses)?$""")) {
            MarvinIntent.ShoppingAdd(it.groupValues[1].trim())
        },
        Rule(Regex("""(?:lis|montre|donne|c'est quoi)(?:[- ]moi)?\s+(?:la|ma)\s+liste(?: de courses)?""")) {
            MarvinIntent.ShoppingRead
        },
        Rule(Regex("""(?:enlève|retire|supprime|efface)\s+(.+?)\s+(?:de|sur)\s+(?:la|ma)\s+liste(?: de courses)?""")) {
            MarvinIntent.ShoppingRemove(it.groupValues[1].trim())
        },
        Rule(Regex("""(?:vide|efface|supprime|annule)\s+(?:toute |entièrement )?(?:la|ma)\s+liste(?: de courses)?""")) {
            MarvinIntent.ShoppingClear
        },

        // ---- WhatsApp ----
        Rule(Regex("""(?:envoie|écris) (?:un )?(?:message |whatsapp )(?:whatsapp )?à ([\p{L}\s'-]+?) (?:pour (?:dire|lui dire) |disant |que )(.+)""")) {
            MarvinIntent.WhatsAppMessage(it.groupValues[1].trim(), it.groupValues[2].trim())
        },

        // ---- Appels ----
        // Ancré au début pour ne pas matcher "comment je m'appelle marvin"
        // (où "appelle" apparaît au milieu après "m'").
        Rule(Regex("""^(?:appelle|appel|téléphone à|téléphone a)\s+([\p{L}\s'-]+)""")) {
            MarvinIntent.CallContact(it.groupValues[1].trim())
        },

        // ---- Banque (lecture seule) ----
        Rule(Regex("""combien (?:il )?(?:me )?reste (?:d'argent )?sur (?:mon )?compte""")) {
            MarvinIntent.BankRead(BankKind.BOURSOBANK, BankRequest.BALANCE)
        },
        Rule(Regex("""(?:quel est |donne |montre |c'est quoi )?(?:mon )?solde (?:sur |de )?boursobank""")) {
            MarvinIntent.BankRead(BankKind.BOURSOBANK, BankRequest.BALANCE)
        },
        Rule(Regex("""(?:quel est |donne |montre |c'est quoi )?(?:mon )?solde (?:sur |de )?(?:banque pop|banque populaire)""")) {
            MarvinIntent.BankRead(BankKind.BANQUE_POP, BankRequest.BALANCE)
        },
        Rule(Regex("""(?:dernières |dernier )(?:opérations|transactions) boursobank""")) {
            MarvinIntent.BankRead(BankKind.BOURSOBANK, BankRequest.LAST_OPS)
        },
        Rule(Regex("""(?:dernières |dernier )(?:opérations|transactions) (?:banque pop|banque populaire)""")) {
            MarvinIntent.BankRead(BankKind.BANQUE_POP, BankRequest.LAST_OPS)
        },

        // ---- FamilyWall ----
        Rule(Regex("""(?:où (?:est|sont)|localisation de) (?:la famille|tout le monde|les enfants|maman|papa)""")) {
            MarvinIntent.FamilyWallShowLocations
        },
        Rule(Regex("""(?:ouvre |lance )familywall""")) { MarvinIntent.FamilyWallShowLocations },

        // ---- Ecovacs (aspirateur) ----
        Rule(Regex("""(?:lance|démarre|commence) (?:l'?)?aspirateur""")) {
            MarvinIntent.Ecovacs(EcovacsAction.START)
        },
        Rule(Regex("""(?:pause|arrête|stop) (?:l'?)?aspirateur""")) {
            MarvinIntent.Ecovacs(EcovacsAction.PAUSE)
        },
        Rule(Regex("""(?:renvoie|remets|envoie) (?:l'?)?aspirateur (?:à la base|au dock|charger)""")) {
            MarvinIntent.Ecovacs(EcovacsAction.DOCK)
        },

        // ---- Ouvrir une app par nom ----
        Rule(Regex("""ouvre (?:l'app(?:lication)? )?(spotify|whatsapp|waze|familywall|boursobank|banque pop(?:ulaire)?|ecovacs(?: home)?)""")) {
            val name = it.groupValues[1].trim()
            MarvinIntent.OpenApp(name)
        }
    )
}
