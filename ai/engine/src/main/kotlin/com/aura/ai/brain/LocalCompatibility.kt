package com.aura.ai.brain

enum class LocalVerdict { OK, TIGHT, INSUFFICIENT, NO_MODEL }

data class LocalAssessment(val verdict: LocalVerdict, val message: String)

/**
 * Honest, heuristic check of whether this phone can run the on-device model. It is an estimate:
 * the real answer only comes from loading the model. It never claims local mode works without a model.
 */
object LocalCompatibility {
    private const val GB = 1024.0 * 1024.0 * 1024.0
    private const val RUNTIME_OVERHEAD_BYTES = 512L * 1024 * 1024 // KV cache, buffers, runtime

    fun assess(
        modelBytes: Long?,
        totalRamBytes: Long,
        availRamBytes: Long,
        supports64Bit: Boolean,
        freeStorageBytes: Long? = null,
    ): LocalAssessment {
        if (modelBytes == null || modelBytes <= 0) {
            return LocalAssessment(
                LocalVerdict.NO_MODEL,
                "Aucun modèle local installé : le mode local n'est pas utilisable. Importez un modèle (.bin / .task) ou utilisez le mode Cloud.",
            )
        }
        if (!supports64Bit) {
            return LocalAssessment(LocalVerdict.INSUFFICIENT, "Ce téléphone n'est pas 64 bits : le moteur local n'est pas pris en charge.")
        }
        if (freeStorageBytes != null && freeStorageBytes < modelBytes / 10) {
            return LocalAssessment(LocalVerdict.INSUFFICIENT, "Espace de stockage presque plein : libérez de la place avant d'utiliser le modèle local.")
        }
        val need = (modelBytes * 1.3).toLong() + RUNTIME_OVERHEAD_BYTES
        val summary = "RAM totale ${fmt(totalRamBytes)}, disponible ${fmt(availRamBytes)}, besoin estimé ${fmt(need)} pour un modèle de ${fmt(modelBytes)}."
        return when {
            totalRamBytes < (3.0 * GB).toLong() ->
                LocalAssessment(LocalVerdict.INSUFFICIENT, "Moins de 3 Go de RAM : le modèle local risque de ne pas se charger. $summary")
            availRamBytes >= need * 1.15 ->
                LocalAssessment(LocalVerdict.OK, "Compatibilité estimée : bonne. $summary")
            availRamBytes >= need * 0.8 ->
                LocalAssessment(LocalVerdict.TIGHT, "Compatibilité estimée : juste. Fermez d'autres applications ; Android peut arrêter l'appli si la mémoire manque. $summary")
            else ->
                LocalAssessment(LocalVerdict.INSUFFICIENT, "Mémoire disponible insuffisante pour ce modèle. Fermez d'autres applications ou utilisez le mode Cloud. $summary")
        }
    }

    private fun fmt(bytes: Long): String = String.format(java.util.Locale.FRANCE, "%.1f Go", bytes / GB)
}
