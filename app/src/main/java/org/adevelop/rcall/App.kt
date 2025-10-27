package org.adevelop.rcall

import android.app.Application
import org.adevelop.rcall.webrtc.RtcEnv

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