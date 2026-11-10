package com.jossephus.chuchu.service.terminal

import android.os.Trace

internal inline fun <T> traceSection(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
