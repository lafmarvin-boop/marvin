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

        // ---- WhatsApp ----
        Rule(Regex("""(?:envoie|écris) (?:un )?(?:message |whatsapp )(?:whatsapp )?à ([\p{L}\s'-]+?) (?:pour (?:dire|lui dire) |disant |que )(.+)""")) {
            MarvinIntent.WhatsAppMessage(it.groupValues[1].trim(), it.groupValues[2].trim())
        },

        // ---- Appels ----
        Rule(Regex("""(?:appelle|appel|téléphone à|téléphone a) ([\p{L}\s'-]+)""")) {
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
