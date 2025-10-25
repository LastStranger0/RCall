package org.adevelop.rcall

import android.app.Application
import org.adevelop.rcall.webrtc.RtcEnv

/**
 * Кастомный класс Application для управления жизненным циклом приложения
 * и инициализации глобальных компонентов, таких как WebRTC.
 */
class App : Application() {

  override fun onCreate() {
    super.onCreate()
    RtcEnv.init(this)
  }

  override fun onTerminate() {
    super.onTerminate()
    RtcEnv.release()
  }
}