package com.hklab.airuler.sound

import android.content.Context
import android.media.SoundPool
import com.hklab.airuler.R

class AirulerSoundPlayer(context: Context) {

    private val sp: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .build()

    private val idPass = sp.load(context, R.raw.pass, 1)
    private val idFail = sp.load(context, R.raw.fail, 1)
    private val idSuccess = sp.load(context, R.raw.success, 1)
    private val idError = sp.load(context, R.raw.error, 1)

    fun playPass() = play(idPass)
    fun playFail() = play(idFail)
    fun playSuccess() = play(idSuccess)
    fun playError() = play(idError)

    private fun play(id: Int) {
        sp.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        sp.release()
    }
}
