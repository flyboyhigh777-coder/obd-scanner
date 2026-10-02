package com.obdscanner.app

import android.app.Application
import com.obdscanner.core.elm.ObdText
import java.util.Locale

/**
 * Приложению нужно одно действие при старте: сообщить :core, на каком языке
 * отдавать свои тексты (названия параметров, расшифровки кодов ошибок, страны
 * по VIN). Эти строки живут в :core, потому что они часть словаря протокола и
 * покрыты тестами, а не элемент оформления - но язык выбирает всё равно
 * система.
 */
class ObdApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ObdText.russian = Locale.getDefault().language == "ru"
    }
}
