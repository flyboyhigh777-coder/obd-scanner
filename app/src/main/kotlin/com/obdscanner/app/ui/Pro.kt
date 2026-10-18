package com.obdscanner.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.obdscanner.app.R
import com.obdscanner.app.ui.theme.GaugeAmber
import com.obdscanner.app.ui.theme.Hint
import com.obdscanner.app.ui.theme.TextBright
import com.obdscanner.core.pro.ProFeature

/**
 * Как в приложении выглядит платное.
 *
 * Правило одно: закрытая возможность **видна и объяснена**, а не спрятана и
 * не погашена серым. Серая кнопка в машине читается как «сломалось», а
 * исчезнувший раздел - как «приложение не умеет», и человек идёт ставить
 * другое, вместо того чтобы заплатить. Поэтому раздел остаётся на месте, с
 * пометкой «PRO», и по нажатию честно рассказывает, что именно даёт Pro.
 */
@Composable
fun ProDialog(feature: ProFeature, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pro_close)) }
        },
        title = { Text(stringResource(R.string.pro_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Сначала - про ту самую возможность, за которой человек сюда
                // нажал. Общий список «что ещё в Pro» ниже: он убеждает, но
                // отвечает не на тот вопрос, который задали.
                Text(stringResource(proFeatureText(feature)), color = TextBright)
                Hint(stringResource(R.string.pro_what_is_included))
                ProFeature.entries.forEach { item ->
                    Text(
                        "• " + stringResource(proFeatureShort(item)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item == feature) TextBright else com.obdscanner.app.ui.theme.TextMuted
                    )
                }
                // Пока продавать негде - честно об этом сказать. Кнопка
                // «купить», которая ничего не делает, хуже её отсутствия.
                Hint(stringResource(R.string.pro_not_for_sale_yet), Modifier.padding(top = 6.dp))
            }
        }
    )
}

/** Пометка «PRO» в строке меню - там же, где обычно состояние раздела. */
@Composable
fun proBadge(): String = stringResource(R.string.pro_badge)

/** Цвет пометки: тот же, которым помечается «требует внимания», а не ошибка. */
val ProBadgeColor = GaugeAmber

/**
 * Кнопка платного действия.
 *
 * Выглядит как обычная, но ведёт в объяснение. Так человек видит, что
 * приложение это умеет, - и понимает, что именно он покупает.
 */
@Composable
fun ProButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        border = BorderStroke(1.dp, ProBadgeColor),
        shape = RoundedCornerShape(50)
    ) {
        Text("$text · ${stringResource(R.string.pro_badge)}", color = TextBright)
    }
}

private fun proFeatureText(feature: ProFeature): Int = when (feature) {
    ProFeature.REPORT -> R.string.pro_feature_report
    ProFeature.MODULE_SCAN -> R.string.pro_feature_modules
    ProFeature.CUSTOM_PIDS -> R.string.pro_feature_custom_pids
    ProFeature.TRIP -> R.string.pro_feature_trip
    ProFeature.PERFORMANCE -> R.string.pro_feature_performance
    ProFeature.COUNTERS -> R.string.pro_feature_counters
    ProFeature.BATTERY_TESTS -> R.string.pro_feature_battery
    ProFeature.DASHBOARD_FULL -> R.string.pro_feature_dashboard
    ProFeature.DTC_ARCHIVE -> R.string.pro_feature_archive
}

private fun proFeatureShort(feature: ProFeature): Int = when (feature) {
    ProFeature.REPORT -> R.string.pro_short_report
    ProFeature.MODULE_SCAN -> R.string.pro_short_modules
    ProFeature.CUSTOM_PIDS -> R.string.pro_short_custom_pids
    ProFeature.TRIP -> R.string.pro_short_trip
    ProFeature.PERFORMANCE -> R.string.pro_short_performance
    ProFeature.COUNTERS -> R.string.pro_short_counters
    ProFeature.BATTERY_TESTS -> R.string.pro_short_battery
    ProFeature.DASHBOARD_FULL -> R.string.pro_short_dashboard
    ProFeature.DTC_ARCHIVE -> R.string.pro_short_archive
}
