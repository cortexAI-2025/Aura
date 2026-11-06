# Rapport de sécurité — Aura 0.2

## Stockage des clés
* `SecureKeyValueStore` : `EncryptedSharedPreferences` (valeurs AES-256-GCM, noms AES-256-SIV), clé maître dans l'**Android Keystore**.
* Si le Keystore est inutilisable : clé conservée **en mémoire seulement** (jamais en clair sur disque) et bannière d'avertissement.
* `allowBackup=false` ; règles `data_extraction_rules` excluent fichiers, bases et préférences (sauvegarde cloud **et** transfert d'appareil).
* La clé n'est jamais réaffichée après enregistrement ; `ProviderConfig.toString()` ne l'imprime pas.

## Réseau
* HTTPS obligatoire (`EndpointPolicy`) : HTTP refusé, hôtes locaux/privés refusés hors tests, pas de redirections suivies
  (la clé n'est donc jamais renvoyée vers un autre hôte), `cleartextTrafficPermitted=false`.
* La clé n'est envoyée que dans l'en-tête `Authorization`, jamais dans l'URL.
* Aucune requête vers un serveur AIWORKPAY ; seuls Z.ai / OpenRouter (choisis par l'utilisateur) sont contactés.

## Journaux
* `Redactor` masque clés API, jetons Bearer, JWT, mots de passe, cartes (Luhn) et IBAN dans les messages d'erreur.
* Les contenus des messages et des réponses ne sont pas journalisés ; les erreurs ne journalisent que le type d'exception.
* Les corps d'erreur serveur sont bornés et expurgés avant affichage.

## Injection de prompt
* Le contenu externe (web, messages, écran, résultats d'outils, souvenirs) est assaini et encadré `<untrusted-data>` ; le prompt
  système interdit de suivre des instructions venant de ces blocs.
* **Taint tracking** : après lecture de contenu tiers (`MESSAGE_READ`, `WEB_SEARCH`, `WEB_BROWSE`, `SCREEN_READ`), toute action avec effet
  de bord (envoi, appel, écriture agenda, rappel…) exige une confirmation, même pour un contact pré-approuvé.
  Le calendrier et les contacts de l'utilisateur ne sont volontairement pas considérés comme tiers.
* Le modèle ne reçoit que les outils réellement disponibles ; un outil inexistant est refusé.
* L'effacement total de la mémoire n'est jamais exécutable depuis le chat.

## Vie privée
* Interrupteurs : utiliser la mémoire ; autoriser l'envoi de souvenirs / objectifs au fournisseur cloud ; résumé automatique (désactivé).
* L'écran Réglages affiche ce qui est envoyé, et le chat indique combien de souvenirs/objectifs sont partis avec chaque question.
* Mention affichée : « Une clé BYOK ne garantit pas à elle seule la confidentialité des données chez le fournisseur. »
* Effacement à la demande : conversations, mémoire, personnalisation, clés, ou tout.
* La mémoire refuse les secrets (clés API, mots de passe, cartes, IBAN).

## Limites connues
* `EncryptedSharedPreferences` est déprécié par Google mais reste supporté (`androidx.security:security-crypto`) ; migration future possible vers Tink/DataStore chiffré.
* Les heuristiques de détection de secrets et d'injection réduisent le risque mais ne l'éliminent pas.
* Aucun test sur appareil réel n'a été possible dans l'environnement de développement (voir `KNOWN_LIMITS_V0.2.md`).
