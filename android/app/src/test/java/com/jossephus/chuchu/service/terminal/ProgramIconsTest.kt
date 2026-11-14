package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgramIconsTest {
    @Test
    fun matchesReportedAppNamesAndCommands() {
        assertEquals(R.drawable.ic_program_claude, ProgramIcons.forName("claude-code")?.drawable)
        assertEquals(R.drawable.ic_program_rust, ProgramIcons.forName("cargo")?.drawable)
        assertEquals(R.drawable.ic_program_neovim, ProgramIcons.forName("nvim")?.drawable)
        assertEquals(R.drawable.ic_program_opencode, ProgramIcons.forName("opencode")?.drawable)
        assertEquals(R.drawable.ic_program_pi, ProgramIcons.forName("pi")?.drawable)
    }

    @Test
    fun ignoresCaseSpacesAndVersionSuffixes() {
        assertEquals(R.drawable.ic_program_python, ProgramIcons.forName(" Python3.12 ")?.drawable)
        assertEquals(R.drawable.ic_program_claude, ProgramIcons.forName("Claude Code")?.drawable)
    }

    @Test
    fun unknownOrMissingNamesHaveNoIcon() {
        assertNull(ProgramIcons.forName("codex"))
        assertNull(ProgramIcons.forName(""))
        assertNull(ProgramIcons.forName(null))
    }
}
