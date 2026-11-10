package com.jossephus.chuchu.ui.terminal

import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.Trace
import androidx.annotation.RequiresApi
import com.jossephus.chuchu.service.terminal.TerminalSnapshot

@RequiresApi(29)
internal class TerminalRowCache {
    private class Entry(
        val codepoints: IntArray,
        val fg: IntArray,
        val bg: IntArray,
        val flags: ByteArray,
        val extras: Array<IntArray?>?,
        val selFrom: Int,
        val selTo: Int,
        val node: RenderNode,
    ) {
        var lastUsedFrame = 0L
    }

    private val buckets = HashMap<Int, MutableList<Entry>>()
    private var entryCount = 0
    private val spareNodes = ArrayList<RenderNode>()
    private var spareLimit = 0
    private var styleKey: Any? = null
    private var frame = 0L
    private var misses = 0
    private var hits = 0
    private var rowHashes = IntArray(0)
    private var rowBlank = BooleanArray(0)
    private var previousHashes = HashSet<Int>()
    private var currentHashes = HashSet<Int>()
    private var layersThisFrame = false

    fun beginFrame(key: Any, maxEntries: Int, rows: Int) {
        spareLimit = maxEntries / 2
        if (key != styleKey || entryCount > maxEntries) {
            clear()
            styleKey = key
        }
        if (rowHashes.size != rows) {
            rowHashes = IntArray(rows)
            rowBlank = BooleanArray(rows)
        }
        frame++
        misses = 0
        hits = 0
        layersThisFrame = false
    }

    fun measureRow(row: Int, snapshot: TerminalSnapshot, rowStart: Int, cols: Int, selFrom: Int, selTo: Int) {
        val blank = selFrom < 0 && isBlankRow(snapshot, rowStart, cols)
        rowBlank[row] = blank
        rowHashes[row] = if (blank) 0 else rowHash(snapshot, rowStart, cols, selFrom, selTo)
    }

    fun shouldUseLayers(): Boolean {
        currentHashes.clear()
        var drawn = 0
        var seen = 0
        for (row in rowHashes.indices) {
            if (rowBlank[row]) continue
            drawn++
            val hash = rowHashes[row]
            if (hash in previousHashes) seen++
            currentHashes.add(hash)
        }
        val swap = previousHashes
        previousHashes = currentHashes
        currentHashes = swap
        val useLayers = drawn > 0 && seen * 2 >= drawn
        Trace.setCounter("term.rowLayers", if (useLayers) 1L else 0L)
        layersThisFrame = useLayers
        return useLayers
    }

    fun nodeFor(
        row: Int,
        snapshot: TerminalSnapshot,
        rowStart: Int,
        cols: Int,
        selFrom: Int,
        selTo: Int,
        width: Int,
        height: Int,
        padTop: Int,
        record: (Canvas) -> Unit,
    ): RenderNode? {
        if (rowBlank[row]) return null
        val bucket = buckets.getOrPut(rowHashes[row]) { ArrayList(1) }
        for (entry in bucket) {
            if (matches(entry, snapshot, rowStart, cols, selFrom, selTo)) {
                entry.lastUsedFrame = frame
                hits++
                return entry.node
            }
        }
        misses++
        val node = if (spareNodes.isEmpty()) RenderNode("termRow") else spareNodes.removeAt(spareNodes.size - 1)
        node.setPosition(0, 0, width, height + padTop * 2)
        node.setUseCompositingLayer(true, null)
        val canvas = node.beginRecording(width, height + padTop * 2)
        try {
            canvas.translate(0f, padTop.toFloat())
            record(canvas)
        } finally {
            node.endRecording()
        }
        val entry = newEntry(snapshot, rowStart, cols, selFrom, selTo, node)
        entry.lastUsedFrame = frame
        bucket.add(entry)
        entryCount++
        return node
    }

    fun endFrame() {
        Trace.setCounter("term.rowMiss", misses.toLong())
        Trace.setCounter("term.rowHit", hits.toLong())
        val iterator = buckets.values.iterator()
        while (iterator.hasNext()) {
            val bucket = iterator.next()
            bucket.removeAll { entry ->
                val stale = !layersThisFrame || entry.lastUsedFrame < frame
                if (stale) {
                    recycle(entry.node)
                    entryCount--
                }
                stale
            }
            if (bucket.isEmpty()) iterator.remove()
        }
    }

    fun clear() {
        for (bucket in buckets.values) {
            for (entry in bucket) entry.node.discardDisplayList()
        }
        buckets.clear()
        entryCount = 0
        for (node in spareNodes) node.discardDisplayList()
        spareNodes.clear()
    }

    private fun recycle(node: RenderNode) {
        if (spareNodes.size < spareLimit) spareNodes.add(node) else node.discardDisplayList()
    }

    private fun isBlankRow(snapshot: TerminalSnapshot, rowStart: Int, cols: Int): Boolean {
        val defaultBg = snapshot.defaultBgArgb
        for (i in rowStart until rowStart + cols) {
            val cp = snapshot.codepoints[i]
            if (cp != 0 && cp != 32) return false
            if (snapshot.bgArgb[i] != defaultBg) return false
            if ((snapshot.flags[i].toInt() and TerminalSnapshot.CELL_FLAG_INVERSE) != 0) return false
        }
        return true
    }

    private fun rowHash(snapshot: TerminalSnapshot, rowStart: Int, cols: Int, selFrom: Int, selTo: Int): Int {
        var h = selFrom * 31 + selTo
        for (i in rowStart until rowStart + cols) {
            h = h * 31 + snapshot.codepoints[i]
            h = h * 31 + snapshot.fgArgb[i]
            h = h * 31 + snapshot.bgArgb[i]
            h = h * 31 + snapshot.flags[i]
        }
        if (snapshot.graphemeExtras.isNotEmpty()) {
            for (i in rowStart until rowStart + cols) {
                val extras = snapshot.graphemeExtras[i] ?: continue
                h = h * 31 + i - rowStart
                h = h * 31 + extras.contentHashCode()
            }
        }
        return h
    }

    private fun matches(
        entry: Entry,
        snapshot: TerminalSnapshot,
        rowStart: Int,
        cols: Int,
        selFrom: Int,
        selTo: Int,
    ): Boolean {
        if (entry.selFrom != selFrom || entry.selTo != selTo) return false
        if (entry.codepoints.size != cols) return false
        for (c in 0 until cols) {
            val i = rowStart + c
            if (entry.codepoints[c] != snapshot.codepoints[i]) return false
            if (entry.fg[c] != snapshot.fgArgb[i]) return false
            if (entry.bg[c] != snapshot.bgArgb[i]) return false
            if (entry.flags[c] != snapshot.flags[i]) return false
        }
        val stored = entry.extras
        if (snapshot.graphemeExtras.isEmpty()) return stored == null
        for (c in 0 until cols) {
            val current = snapshot.graphemeExtras[rowStart + c]
            val previous = stored?.get(c)
            if (current == null && previous == null) continue
            if (current == null || previous == null || !current.contentEquals(previous)) return false
        }
        return true
    }

    private fun newEntry(
        snapshot: TerminalSnapshot,
        rowStart: Int,
        cols: Int,
        selFrom: Int,
        selTo: Int,
        node: RenderNode,
    ): Entry {
        val end = rowStart + cols
        var extras: Array<IntArray?>? = null
        if (snapshot.graphemeExtras.isNotEmpty()) {
            for (c in 0 until cols) {
                val cell = snapshot.graphemeExtras[rowStart + c] ?: continue
                if (extras == null) extras = arrayOfNulls(cols)
                extras[c] = cell.copyOf()
            }
        }
        return Entry(
            codepoints = snapshot.codepoints.copyOfRange(rowStart, end),
            fg = snapshot.fgArgb.copyOfRange(rowStart, end),
            bg = snapshot.bgArgb.copyOfRange(rowStart, end),
            flags = snapshot.flags.copyOfRange(rowStart, end),
            extras = extras,
            selFrom = selFrom,
            selTo = selTo,
            node = node,
        )
    }
}
