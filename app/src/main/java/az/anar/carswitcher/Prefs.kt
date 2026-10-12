package az.anar.carswitcher

import android.content.Context

/** Настройки и сохранённое состояние (позиция кнопки, список недавних приложений). */
object Prefs {
    private const val NAME = "car_switcher"
    private const val KEY_X = "btn_x"
    private const val KEY_Y = "btn_y"
    private const val KEY_SIZE = "btn_size"
    private const val KEY_RECENT = "recent_pkgs"

    const val SIZE_S = 64
    const val SIZE_M = 84
    const val SIZE_L = 110

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun buttonPos(c: Context): Pair<Int, Int> {
        val p = sp(c)
        return p.getInt(KEY_X, -1) to p.getInt(KEY_Y, -1)
    }

    fun saveButtonPos(c: Context, x: Int, y: Int) =
        sp(c).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()

    /** Размер кнопки в dp. */
    fun buttonSizeDp(c: Context): Int = sp(c).getInt(KEY_SIZE, SIZE_M)

    fun setButtonSizeDp(c: Context, dp: Int) = sp(c).edit().putInt(KEY_SIZE, dp).apply()

    fun recentPackages(c: Context): List<String> =
        (sp(c).getString(KEY_RECENT, "") ?: "").split('|').filter { it.isNotBlank() }

    fun saveRecentPackages(c: Context, list: List<String>) =
        sp(c).edit().putString(KEY_RECENT, list.joinToString("|")).apply()
}
