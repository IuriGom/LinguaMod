package com.linguamod.app.ocr

import javax.inject.Inject

/**
 * Stage 9: OCR with backup-for-the-backup. ML Kit (best quality) is used when
 * Play Services is present; otherwise the bundled open-source Tesseract engine
 * takes over, so the feature works on GMS-free devices too.
 */
class FallbackOcrGateway @Inject constructor(
    private val mlKit: MlKitOcrGateway,
    private val tess: TessOcrGateway,
) : OcrGateway {

    /** READY when either engine can run; Tesseract is bundled, so in practice
     *  always READY — the NO_PLAY_SERVICES hide-path remains only for the
     *  (hypothetical) case where even the bundled engine failed to init. */
    override suspend fun availability(): OcrAvailability {
        if (mlKit.availability() == OcrAvailability.READY) return OcrAvailability.READY
        return tess.availability()
    }

    override suspend fun recognize(frame: OcrFrame?): OcrResult =
        if (mlKit.availability() == OcrAvailability.READY) {
            mlKit.recognize(frame)
        } else {
            tess.recognize(frame)
        }
}
