package com.nexanote.app.ai

/**
 * Acción estructurada que la IA pide ejecutar sobre el lienzo. El `AiViewModel`
 * las publica y `DocumentScreen` las delega al `DocumentViewModel`, que las
 * traduce a las inserciones FFI ya existentes (`add_text`, `add_formula`,
 * `add_graph`). El núcleo Rust sigue siendo la autoridad sobre la validez final.
 */
sealed interface AiCommand {

    /** Bloque de texto tipográfico. */
    data class InsertText(val text: String) : AiCommand

    /**
     * Fórmula matemática en dialecto LaTeX/ASCII que entiende el motor del núcleo
     * (fracciones, raíces, sumatorias, `\begin{pmatrix}...\end{pmatrix}`, ...).
     */
    data class InsertFormula(val latex: String) : AiCommand

    /** Gráfica de `y = f(x)` sobre el dominio `[xMin, xMax]`. */
    data class InsertGraph(
        val expression: String,
        val xMin: Double,
        val xMax: Double,
    ) : AiCommand
}
