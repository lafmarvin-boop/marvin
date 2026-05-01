---
description: Reprend le contexte des sessions Claude Code précédentes dans ce projet
allowed-tools: Bash(ls:*), Bash(find:*), Bash(jq:*), Bash(wc:*), Read
---

# Reprise de session

L'utilisateur veut que tu te souviennes des conversations précédentes dans ce projet. Suis ces étapes:

## 1. Localiser les transcriptions des sessions passées

Les sessions Claude Code de ce projet sont stockées dans `~/.claude/projects/-home-user-marvin/` au format JSONL (un message par ligne).

Liste les fichiers de session triés par date de modification (plus récents en premier):

```bash
ls -lt ~/.claude/projects/-home-user-marvin/*.jsonl 2>/dev/null
```

## 2. Lire les sessions récentes

Pour chaque session récente (jusqu'à 3 plus récentes, en excluant la session courante si possible):

- Utilise l'outil Read pour lire le fichier JSONL
- Si le fichier est volumineux, lis-le par tranches
- Extrais les messages utilisateur (`type: "user"`) et les résumés de tâches
- Identifie:
  - Les questions/demandes de l'utilisateur
  - Les fichiers modifiés
  - Les décisions techniques prises
  - Les problèmes résolus ou en cours

## 3. Présenter un résumé structuré

Présente à l'utilisateur un résumé en français contenant:

- **Sessions retrouvées**: nombre et dates
- **Sujets principaux**: les thèmes abordés dans les sessions précédentes
- **Travail effectué**: résumé concis des modifications de code et tâches accomplies
- **En cours / à faire**: ce qui semblait incomplet ou planifié

Termine en demandant à l'utilisateur sur quoi il souhaite continuer.

## Notes importantes

- Si aucune session passée n'est trouvée, indique-le clairement
- Ne lis pas plus de 3 sessions sauf si l'utilisateur le demande
- Pour des sessions très longues, ne lis que les premiers et derniers messages pour gagner du contexte
