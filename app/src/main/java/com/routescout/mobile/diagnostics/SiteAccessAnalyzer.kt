package com.routescout.mobile.diagnostics

data class SiteAccessAssessment(
    val filteringSuspected: Boolean,
    val summary: String
)

/**
 * A heuristic comparison against example.com. It cannot prove ISP filtering:
 * individual sites can be down, geo-restricted, or temporarily unreachable.
 */
object SiteAccessAnalyzer {
    fun analyze(checks: List<SiteConnectivityResult>): SiteAccessAssessment {
        if (checks.isEmpty()) {
            return SiteAccessAssessment(false, "Проверки сайтов ещё не выполнялись.")
        }

        val control = checks.firstOrNull { it.host.equals("example.com", true) }
        val foreign = checks.filterNot { it.host.equals("example.com", true) }
        val failed = foreign.filterNot { it.isReachable }
        val suspected = control?.isReachable == true && failed.size >= 2

        val summary = when {
            control == null ->
                "Контрольный сайт не проверен. Результат нельзя уверенно интерпретировать."
            control.isReachable && failed.isEmpty() ->
                "Все проверенные зарубежные сайты ответили. Явных проблем доступности нет."
            control.isReachable ->
                "Контрольный сайт доступен, но " + failed.size + " из " + foreign.size +
                    " зарубежных ресурсов не ответили. Возможны сетевые ограничения, DNS/TLS-сбой или проблема самих сайтов."
            else ->
                "Контрольный сайт тоже не ответил. Сначала проверь подключение, DNS и работу сети оператора."
        }
        return SiteAccessAssessment(suspected, summary)
    }
}
