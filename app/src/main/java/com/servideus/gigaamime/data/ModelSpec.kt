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

/**
 * The downloadable model. The [id] is also the model's folder name under the models root;
 * the native core resolves the same path from the id.
 */
enum class GigaamModel(
    val id: String,
    val displayName: String,
    val artifacts: List<ModelArtifact>,
) {
    INT8(
        id = "gigaam-v3-e2e-rnnt-int8",
        displayName = "GigaAM v3 e2e-RNNT (int8)",
        artifacts = listOf(
            hubArtifact(
                fileName = "v3_e2e_rnnt_encoder.int8.onnx",
                sha256 = "4e0e076a6076cd110277e529b8ac8f32cd5297f7fbebad5341ae8ddb7d00817b",
                sizeBytes = 224_570_477,
            ),
            hubArtifact(
                fileName = "v3_e2e_rnnt_decoder.int8.onnx",
                sha256 = "89014e134865615b91e037157e46e389b1271e6072460efc010ea08e61e23146",
                sizeBytes = 1_159_170,
            ),
            hubArtifact(
                fileName = "v3_e2e_rnnt_joint.int8.onnx",
                sha256 = "ade116563dbf66e503b0994efab6b5861412743e52bf31c39fc3fffa3783d5d1",
                sizeBytes = 687_791,
            ),
            hubArtifact(
                fileName = "v3_e2e_rnnt_vocab.txt",
                sha256 = "39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7",
                sizeBytes = 13_354,
            ),
            hubArtifact(
                fileName = "v3_e2e_rnnt.yaml",
                sha256 = "d23270a1f327e365ebc4554b7ceee69ac12898e21092c18085151516aa58f91b",
                sizeBytes = 1_041,
            ),
        ),
    );

    val totalBytes: Long
        get() = artifacts.sumOf { it.sizeBytes }
}
