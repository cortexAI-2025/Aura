# Aura 0.2 — Cerveau IA multi-modèles BYOK

Périmètre : le cerveau IA uniquement. L'architecture modulaire de la v0.1 est conservée
(`ai:engine`, `agent:core`, `agent:memory`, `agent:actions`, `core:*`, `feature:*`).

## 1. Audit de départ (v0.1)

| Module | Constat | Décision |
|---|---|---|
| `ai:engine` | `LLMEngine` + `MediaPipeLLM` (Gemma local) + `OpenRouterLLM` codé en dur, `LLMSettings` mono-fournisseur | Conservé. Couche `provider/` ajoutée ; `OpenRouterLLM`/`LLMSettings` remplacés par `CloudLLMEngine`/`AiSettings` |
| `agent:core` | `AgentEngine` (ReAct JSON), service foreground, file de tâches | Conservé ; pipeline recentré (intention → contexte → requête → analyse → réponse → mémoire) |
| `agent:memory` | `VectorStore` : similarité cosinus sur embeddings, pas de contrôle utilisateur, embeddings « stub » non sémantiques mélangés aux vrais | Remplacé par `MemoryService` (logique pure) + `RoomMemoryStore` |
| `core:domain/data/database` | Room v1, une seule conversation | Room v2 + migration `1→2` |
| `feature:chat` | Chat + dialogue OpenRouter | Dialogue retiré (déplacé dans Réglages), conversations multiples |

## 2. Couches

```
feature:chat ─ feature:settings ─ feature:memory
        │               │               │
        └────────── agent:core ─────────┘
                  AgentEngine · PersonalizationStore · UserDataWiper · AuraAgentService
              agent:actions        agent:memory
        ActionGuard · ActionExecutor   MemoryService (hygiène, rappel hybride, export)
                       ai:engine
   provider/  AIProvider · ZaiProvider · OpenRouterProvider · ResilientChat · ProviderRegistry
   settings/  AiSettings · KeyValueStore          brain/  prompt · intention · fenêtre de contexte · collecte de réponse
   engine/    LLMEngine · CloudLLMEngine · MediaPipeLLM · RoutedLLMEngine · EmbeddingEngine
                       core:domain  ←  core:data  ←  core:database (Room v2)
```

Les paquets `provider/`, `settings/`, `brain/` et `agent/memory/logic/` n'importent aucune API Android :
ils sont testés sur JVM pur (≈ 200 tests) et dans la CI.

## 3. Fournisseurs (`AIProvider`)

Contrat unique : `streamChat`, `complete`, `testConnection`, `fallbackModels`, plus métadonnées
(`defaultBaseUrl`, `defaultModel`, `suggestedModels`, `capabilities`, `keyHint`, `dataNotice`).
Ajouter un fournisseur = une classe + une ligne dans `ProviderRegistry` (voir `AIEngineModule`).

* **Z.ai** (`ZaiProvider`) : `https://api.z.ai/api/paas/v4/chat/completions`, modèle par défaut `glm-4.7-flash`,
  clé « Standard » (une clé *Coding Plan* a un autre endpoint). `thinking` est envoyé explicitement
  (désactivé par défaut : plus rapide, moins de tokens). Flux SSE avec `reasoning_content` (jamais affiché),
  `usage` dans le dernier fragment, `finish_reason` (`stop`, `length`, `sensitive`, `model_context_window_exceeded`,
  `network_error`, `tool_calls`). Codes d'erreur métier traduits (1000/1001/1003 clé, 1113 solde — non réessayé,
  1302 limite, 1305 surcharge, 1211 modèle inconnu, 1301 filtre, 1261 contexte).
* **OpenRouter** : fonctionnalité v0.1 portée sur le même contrat (modèles gratuits + bascule automatique).
* **Fournisseurs futurs** (Qwen, DeepSeek, Kimi, MiniMax, Mistral, OpenAI, Claude, Gemini, Grok, Perplexity, personnalisé) :
  listés dans `ProviderRegistry.PLANNED` pour transparence, **non sélectionnables, aucun faux connecteur**.

## 4. Mémoire à trois niveaux

1. **Immédiate** : historique de la conversation courante, ajusté au budget de tokens (`ContextWindow`).
2. **Persistante** : souvenirs explicites (`Memory`) en Room. Ajout par « retiens que… », écran Mémoire, ou résumé
   automatique (désactivé par défaut). Consultation, recherche, correction, suppression, export JSON, réinitialisation.
3. **Sémantique** : rappel hybride (embeddings MiniLM si le modèle est présent + similarité lexicale). Les vecteurs ne sont
   comparés que dans le même espace (`embeddingKind`) : sans MiniLM, le rappel est lexical et l'application le dit
   (les embeddings « stub » ne sont jamais présentés comme sémantiques).

Hygiène (`MemoryPolicy`) : refus des secrets (clés API, mots de passe, cartes bancaires Luhn, IBAN, JWT) ; doublon
(similarité ≥ 0,85) fusionné ; souvenir proche mais différent (≥ 0,45) enregistré avec le drapeau « à vérifier ».

## 5. Orchestration (`AgentEngine`)

1. **Intention** (`IntentAnalyzer`, règles explicites, jamais sur la sortie d'un outil) : « retiens que », « oublie que »,
   « que sais-tu de moi ? » sont exécutés directement ; « efface toute ta mémoire » renvoie vers l'écran Mémoire.
2. **Contexte** : profil, souvenirs pertinents, objectifs actifs, règles, **uniquement les outils réellement présents**.
3. **Requête** : prompt système (`SystemPromptBuilder`) + historique + message, ajusté à la fenêtre du modèle.
4. **Analyse** : réponse en flux ; un appel d'outil passe par `ActionGuard` ; le contenu revenant d'un outil est encadré
   `<untrusted-data>` et traité comme donnée.
5. **Réponse** : réponse tronquée (`length`/coupure réseau) → continuation bornée ; vide → une relance ; filtrée /
   contexte dépassé → erreur explicite. Jamais d'invention de contenu.
6. **Mise à jour mémoire** : seulement si l'utilisateur l'a activée.

## 6. Données

Room v2 : table `conversations`, colonnes `conversation_id`, `provider_id`, `model`, `prompt_tokens`,
`completion_tokens`, `finish_reason` sur les messages ; `source`, `needs_review`, `related_id`, `updated_at`,
`embedding_kind` sur les souvenirs. `MIGRATION_1_2` conserve toutes les données v0.1 (conversation « historique »).
Seul `fallbackToDestructiveMigrationOnDowngrade` est autorisé.

## 7. Modes

* **Cloud BYOK** : le fournisseur sélectionné **et** une clé enregistrée sont nécessaires (`AiSettings.cloudActive`).
* **Local** : moteur MediaPipe conservé. `LocalCompatibility` estime la compatibilité (RAM, 64 bits, stockage) ; sans modèle
  importé le mode local est affiché « non utilisable ». Aucun téléchargement automatique.
* Changer de fournisseur ou de modèle est immédiat ; chaque fournisseur garde sa clé, son modèle et son URL.
