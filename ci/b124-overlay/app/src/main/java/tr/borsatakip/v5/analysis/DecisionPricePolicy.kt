package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Candle

/** Keeps decision math strictly on a validated closed history bar. */
object DecisionPricePolicy {
    fun historyBarPrice(candles: List<Candle>): Double? =
        OhlcvResampler.sanitize(candles).lastOrNull()?.close?.takeIf { it.isFinite() && it > 0.0 }
}
