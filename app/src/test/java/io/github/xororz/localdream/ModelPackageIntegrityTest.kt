package io.github.xororz.localdream

import io.github.xororz.localdream.data.Model
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelPackageIntegrityTest {

    private fun tmp(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "ldet_${tag}_${System.currentTimeMillis()}").apply { mkdirs() }

    private fun makeDir(parent: File, files: List<String>): File {
        val d = File(parent, "pkg").apply { mkdirs() }
        files.forEach { name -> File(d, name).writeText("x") }
        return d
    }

    private val qnnSet = listOf(
        "clip_v2.mnn", "unet.bin", "vae_encoder.bin", "vae_decoder.bin",
        "pos_emb.bin", "token_emb.bin",
    )
    private val mnnSet = listOf(
        "clip_v2.mnn.weight", "unet.mnn.weight", "vae_decoder.mnn.weight",
        "vae_encoder.mnn.weight", "pos_emb.bin", "token_emb.bin",
    )

    @Test fun qnnFlatLayoutPassesQnnCheck() {
        assertTrue(Model.hasCompleteSdPackage(makeDir(tmp("qnn"), qnnSet), qnn = true))
    }

    @Test fun mnnFlatLayoutPassesMnnCheck() {
        assertTrue(Model.hasCompleteSdPackage(makeDir(tmp("mnn"), mnnSet), qnn = false))
    }

    @Test fun missingUnetFails() {
        assertFalse(Model.hasCompleteSdPackage(makeDir(tmp("qnnm"), qnnSet - "unet.bin"), qnn = true))
        assertFalse(Model.hasCompleteSdPackage(makeDir(tmp("mnnm"), mnnSet - "unet.mnn.weight"), qnn = false))
    }

    @Test fun crossFamilyFails() {
        assertFalse(Model.hasCompleteSdPackage(makeDir(tmp("c1"), qnnSet), qnn = false))
        assertFalse(Model.hasCompleteSdPackage(makeDir(tmp("c2"), mnnSet), qnn = true))
    }
}
