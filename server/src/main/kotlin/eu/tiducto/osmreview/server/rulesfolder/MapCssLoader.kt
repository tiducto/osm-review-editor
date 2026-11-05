package eu.tiducto.osmreview.server.rulesfolder

import eu.tiducto.osmreview.shared.DiagnosticSeverity
import eu.tiducto.osmreview.shared.RuleDiagnostic
import eu.tiducto.osmreview.shared.RuleKind
import eu.tiducto.osmreview.shared.Severity
import eu.tiducto.osmreview.shared.mapcss.Diagnostic
import eu.tiducto.osmreview.shared.mapcss.MapCss
import eu.tiducto.osmreview.shared.mapcss.Stylesheet
import eu.tiducto.osmreview.shared.presets.SelectorProblem
import eu.tiducto.osmreview.shared.presets.SelectorValidator

object MapCssRules {
    val HOST_FUNCTIONS = setOf(
        "waylength", "areasize", "odd_segment", "network_island_size", "half_width", "connected_value", "inside", "outside",
        "is_right_hand_traffic",
    )

    fun parse(file: String, source: String): Pair<Stylesheet?, List<RuleDiagnostic>> {
        val r = MapCss.parse(source, file, HOST_FUNCTIONS)
        val diags = r.diagnostics.map(::toRule).toMutableList()
        r.stylesheet?.let { sheet -> diags += sheet.selfTest().map(::toRule) }
        return r.stylesheet to diags
    }

    fun toRule(d: Diagnostic) = RuleDiagnostic(d.file, d.line, d.column, severity(d.severity), d.message)

    fun severity(s: Severity): DiagnosticSeverity = when (s) {
        Severity.ERROR -> DiagnosticSeverity.ERROR
        Severity.WARNING -> DiagnosticSeverity.WARNING
        Severity.INFO -> DiagnosticSeverity.INFO
    }
}

class MapCssLoader(override val kind: RuleKind) : RuleLoader<Stylesheet> {
    init {
        require(kind == RuleKind.HIGHLIGHT || kind == RuleKind.CHECK) { "MapCSS files are highlights or checks" }
    }

    override fun load(file: String, source: String): RuleLoadResult<Stylesheet> {
        val (sheet, diags) = MapCssRules.parse(file, source)
        val out = diags.toMutableList()
        if (sheet != null) {
            if (kind == RuleKind.CHECK && !sheet.hasChecks) {
                out += RuleDiagnostic(file, 1, 1, DiagnosticSeverity.WARNING, "No throwError, throwWarning or throwOther rule: this check finds nothing")
            }
            if (kind == RuleKind.HIGHLIGHT && !sheet.hasStyles) {
                out += RuleDiagnostic(file, 1, 1, DiagnosticSeverity.WARNING, "No style rule: this highlight shows nothing")
            }
        }
        return RuleLoadResult(sheet, out)
    }
}

object MapCssSelectorValidator : SelectorValidator {
    override fun validate(selector: String): List<SelectorProblem> {
        val (_, diags) = MapCss.parseSelector(selector, MapCssRules.HOST_FUNCTIONS)
        return diags.map { SelectorProblem(offset(selector, it.line, it.column), MapCssRules.severity(it.severity), it.message) }
    }

    private fun offset(text: String, line: Int, column: Int): Int {
        var i = 0
        var l = 1
        while (l < line && i < text.length) {
            if (text[i] == '\n') l++
            i++
        }
        return (i + column - 1).coerceIn(0, text.length)
    }
}
