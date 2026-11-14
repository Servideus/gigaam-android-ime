package com.servideus.gigaamime.data

data class ModelArtifact(
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

private const val MODEL_BASE_URL = "https://huggingface.co/istupakov/gigaam-v3-onnx/resolve/main/"

private fun hubArtifact(fileName: String, sha256: String, sizeBytes: Long) =
    ModelArtifact(fileName, MODEL_BASE_URL + fileName, sha256, sizeBytes)

private val VOCAB_ARTIFACT = hubArtifact(
    fileName = "v3_e2e_ctc_vocab.txt",
    sha256 = "142de7570b3de5b3035ce111a89c228e80e6085273731d944093ddf24fa539cd",
    sizeBytes = 2_007,
)

private val CONFIG_ARTIFACT = hubArtifact(
    fileName = "v3_e2e_ctc.yaml",
    sha256 = "e67eca3a311ad7c8813d36dff6b8eeba7ad3459fd811d6faea2a26535754a358",
    sizeBytes = 899,
)

/**
 * Downloadable models. The [id] is also the model's folder name under the models root;
 * the native core resolves the same path from the id.
 */
enum class GigaamModel(
    val id: String,
    val displayName: String,
    val qualityHint: String,
    val artifacts: List<ModelArtifact>,
) {
    INT8(
        id = "gigaam-v3-e2e-ctc-int8",
        displayName = "GigaAM v3 e2e-CTC (int8)",
        qualityHint = "faster, smaller",
        artifacts = listOf(
            hubArtifact(
                fileName = "v3_e2e_ctc.int8.onnx",
                sha256 = "2e3fcb7a7b66030336fd10c2fcfb033bd1dc7e1bf238fe5cfd83b1d0cfc9d28e",
                sizeBytes = 224_893_347,
            ),
            VOCAB_ARTIFACT,
            CONFIG_ARTIFACT,
        ),
    ),
    FULL(
        id = "gigaam-v3-e2e-ctc",
        displayName = "GigaAM v3 e2e-CTC (full)",
        qualityHint = "higher quality",
        artifacts = listOf(
            hubArtifact(
                fileName = "v3_e2e_ctc.onnx",
                sha256 = "377701bd33568f4733feec2db5b2dc12544fd09a5a5dfa69ccf55d161f84027a",
                sizeBytes = 885_950_079,
            ),
            VOCAB_ARTIFACT,
            CONFIG_ARTIFACT,
        ),
    );

    val totalBytes: Long
        get() = artifacts.sumOf { it.sizeBytes }

    companion object {
        fun fromId(id: String?): GigaamModel = entries.firstOrNull { it.id == id } ?: INT8
    }
}
