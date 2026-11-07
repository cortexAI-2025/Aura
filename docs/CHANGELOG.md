# Changelog

## 0.2.0 — Cerveau IA multi-modèles BYOK
* Nouveau : interface `AIProvider`, connecteur **Z.ai (GLM-4.7-Flash)** réel avec flux, usage, erreurs/quotas, test de connexion.
* OpenRouter porté sur la même interface ; plus de code fournisseur en dur dans le moteur.
* Réglages IA : fournisseur, clé (chiffrée, supprimable), modèle, test de connexion, consommation, personnalisation, confidentialité, effacement.
* Mémoire à trois niveaux, écran Mémoire (liste, recherche, édition, suppression, export, réinitialisation), refus des secrets, doublons/contradictions.
* Conversations multiples (créer, renommer, supprimer), modèle actif et tokens affichés, avertissements (contexte tronqué, réponse coupée).
* Orchestration : intentions mémoire sans modèle, budget de contexte, continuation des réponses coupées, relance des réponses vides.
* Sécurité : taint tracking des actions, données non fiables encadrées, journaux expurgés, règles de sauvegarde renforcées.
* Base Room v2 avec migration depuis v0.1 (données conservées).
* Changement : le résumé automatique des échanges en mémoire est désormais **désactivé par défaut** (opt-in).
* Retiré : `OpenRouterLLM`, `LLMSettings`, `VectorStore`, ancien `PromptBuilder`.
