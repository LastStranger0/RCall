package org.adevelop.rcall.webrtc

import org.webrtc.EglBase

object Egl {
    val instance: EglBase by lazy { EglBase.create() }
}
