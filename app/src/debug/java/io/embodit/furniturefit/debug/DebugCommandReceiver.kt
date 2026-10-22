package io.embodit.furniturefit.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.furniturefit.platform.FitLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.furniturefit.DEBUG`);
 * see [DebugCommands] for the command list.
 *
 *   adb -s emulator-5564 shell "am broadcast -a io.embodit.furniturefit.DEBUG -p io.embodit.furniturefit --es cmd state --es s S5"
 *
 * Extras: `cmd` = command name, `s` = text argument, `a` = comma-separated numbers. For convenience
 * the text argument may also follow the name inside `cmd` (`--es cmd "state S5"`).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("cmd") ?: return
        val parts = raw.trim().split(Regex("\\s+"), limit = 2)
        val cmd = parts[0]
        val s = intent.getStringExtra("s") ?: parts.getOrNull(1)
        val a = intent.getStringExtra("a")
        FitLog.i("CMD recv '$cmd' s='$s' a='$a'")
        // broadcasts arrive on the main thread, which is where the ViewModel and the scene live
        DebugCommands.run(context.applicationContext, cmd, s, a)
    }
}
