# Limites connues et fonctions non terminées — Aura 0.2

Ce document dit ce qui n'est **pas** fait ou **pas vérifié**, sans l'enjoliver.

## Non vérifié
* **Test réel contre Z.ai** : non exécuté (l'environnement de développement n'a pas accès à `api.z.ai` ni de clé).
  Le connecteur est testé contre un faux serveur HTTP local qui rejoue le dialecte documenté. Le bouton « Tester la connexion »
  de l'application fait une vraie requête : c'est lui qui valide la clé et le modèle sur l'appareil.
* **Identifiant du modèle `glm-4.7-flash`** : la page de prix Z.ai indique « GLM-4.7-Flash » gratuit ; l'identifiant d'API en minuscules
  n'est pas cité tel quel dans la documentation consultée. Il est modifiable dans Réglages et vérifié par le test de connexion.
* **Limites de débit du palier gratuit** : inconnues ; la limite 429 est gérée mais pas chiffrée.
* **Test sur appareil** (Galaxy A14, Android 15) : non réalisé. La CI compile, exécute les tests unitaires, le lint et produit un APK debug.
* `EncryptedSharedPreferences` ne peut pas être exercé sous Robolectric : le stockage chiffré n'est testé que par la logique (`AiSettings`) via un faux magasin.

## Limitations fonctionnelles
* **Consommation** : somme locale des compteurs `usage` renvoyés par l'API. Aucun solde de compte (pas de point d'accès public documenté chez Z.ai).
* **Recherche sémantique** : réellement sémantique seulement si les ressources MiniLM (`models/minilm-l6-v2-int8.tflite`, `bert_vocab.txt`)
  sont présentes ; sinon rappel lexical (indiqué dans la doc, pas dans l'interface).
* **URL de base personnalisée** : prise en charge par `AiSettings` (HTTPS imposé) mais sans écran dédié.
* **Fournisseur « personnalisé compatible OpenAI »** et tous les fournisseurs listés comme futurs : non implémentés.
* **Mode local** : moteur conservé, mais aucun modèle n'est fourni ni téléchargé ; la compatibilité est une estimation heuristique.
* **Confirmation d'action** : le flux « Autoriser » de la v0.1 est inchangé (il relance une requête) ; son refonte n'est pas dans le périmètre.
* **Thinking Z.ai** : le contenu de raisonnement est ignoré, jamais affiché.
