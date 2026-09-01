package com.sprich.app.input.composition

import android.view.inputmethod.InputConnection

/**
 * ImeWriter abstraction: Use composing spans for partials and one final commit.
 * Preserve selection and spacing rules across common editors.
 * Handle password fields, numeric fields, non-editable nodes, secure apps
 * and fields that reject composing text.
 */
interface ImeWriter {
    fun applyPartial(ic: InputConnection?, stable: String, unstable: String): Boolean
    fun commitFinal(ic: InputConnection?, text: String): Boolean
    fun finishIfActive(ic: InputConnection?)
}

class ImeWriterImpl(private val manager: CompositionManager = CompositionManager()) : ImeWriter {
    override fun applyPartial(ic: InputConnection?, stable: String, unstable: String): Boolean {
        if (ic == null) return false
        return manager.applyUpdate(ic, stable, unstable, false)
    }
    override fun commitFinal(ic: InputConnection?, text: String): Boolean {
        if (ic == null) return false
        return manager.applyUpdate(ic, text, "", true)
    }
    override fun finishIfActive(ic: InputConnection?) = manager.finishIfActive(ic)
    fun reset() = manager.reset()
}
