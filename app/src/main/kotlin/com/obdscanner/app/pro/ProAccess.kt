package com.obdscanner.app.pro

import com.obdscanner.app.data.AppSettings
import com.obdscanner.core.pro.FeatureAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Откуда в приложении берётся Pro.
 *
 * Нарочно отделено от того, **что** Pro даёт ([FeatureAccess] в :core):
 * правила доступа одни и те же везде, а способ оплаты зависит от страны и
 * магазина и будет меняться. Google Play не проводит платежи для российских
 * аккаунтов с 2022 года, на китайских магнитолах Google Play может не быть
 * вовсе, - значит, каналов со временем будет несколько, и каждый из них
 * должен подключаться сюда, не трогая ни один экран.
 */
class ProAccess(private val settings: AppSettings) {

    private val _access = MutableStateFlow(current())
    val access: StateFlow<FeatureAccess> = _access.asStateFlow()

    /**
     * Показать приложение так, как его увидит человек без Pro.
     *
     * Пока продавать негде, это единственный способ вообще увидеть бесплатную
     * версию - и разработчику, и тестам. Когда появится магазин, переключатель
     * уйдёт, а его место займёт настоящая покупка.
     */
    fun setPreviewWithoutPro(on: Boolean) {
        settings.previewWithoutPro = on
        _access.value = current()
    }

    val previewWithoutPro: Boolean get() = settings.previewWithoutPro

    private fun current(): FeatureAccess = when (ProSales.CHANNEL) {
        // Канала продажи ещё нет. Отбирать функции у людей, которым негде
        // заплатить, - худший возможный первый шаг: приложение станет хуже,
        // а денег всё равно не принесёт. Поэтому Pro включён у всех, кроме
        // случая, когда его намеренно выключили, чтобы посмотреть.
        ProChannel.NONE -> FeatureAccess(pro = !settings.previewWithoutPro)

        // Магазин подключён: Pro столько, за сколько заплатили. Сюда же будет
        // писать результат проверки покупки.
        ProChannel.GOOGLE_PLAY,
        ProChannel.RUSTORE,
        ProChannel.KEY -> FeatureAccess(pro = settings.proPurchased)
    }
}

/** Чем Pro можно оплатить. */
enum class ProChannel {
    /** Пока ничем: продажа не настроена, Pro открыт всем. */
    NONE,

    /** Разовая покупка в Google Play. Не работает для аккаунтов в России. */
    GOOGLE_PLAY,

    /** Покупка в RuStore - там рубли ходят. */
    RUSTORE,

    /** Ключ, введённый вручную: для магнитол и всего, где магазина нет. */
    KEY
}

/** Какой канал включён в этой сборке. Одна строка на всё приложение. */
object ProSales {
    val CHANNEL = ProChannel.NONE
}
