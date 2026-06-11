package dev.friptu.ideguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BashCommandParserTest {

    private val home = "/home/u"
    private val cwd = "/proj"

    private fun parse(cmd: String) = BashCommandParser.parse(cmd, cwd, home)

    @Test
    fun resolvesRelativeAgainstCwd() {
        assertEquals(
            listOf(FileAccess("/proj/src/a.ts", LockMode.READ)),
            parse("cat src/a.ts"),
        )
    }

    @Test
    fun expandsTilde() {
        assertEquals(
            listOf(FileAccess("/home/u/.zshrc", LockMode.READ)),
            parse("cat ~/.zshrc"),
        )
    }

    @Test
    fun normalizesDotDot() {
        assertEquals(
            listOf(FileAccess("/proj/a.ts", LockMode.READ)),
            parse("cat src/../a.ts"),
        )
    }

    @Test
    fun stripsQuotes() {
        assertEquals(
            listOf(FileAccess("/proj/a b.ts", LockMode.READ)),
            parse("""cat "a b.ts""""),
        )
    }

    @Test
    fun ignoresGlobsVarsAndFlags() {
        assertTrue(parse("cat *.ts").isEmpty())
        assertTrue(parse("cat \$FILE").isEmpty())
        assertTrue(parse("cat -n").isEmpty())
        assertTrue(parse("cat /dev/null").isEmpty())
    }

    @Test
    fun unknownCommandYieldsNothing() {
        assertTrue(parse("eslint src/a.ts").isEmpty())
        assertTrue(parse("rm src/a.ts").isEmpty())
    }

    @Test
    fun redirectIsWrite() {
        assertEquals(
            listOf(FileAccess("/proj/out.log", LockMode.WRITE)),
            parse("echo hi > out.log"),
        )
    }

    @Test
    fun appendRedirectIsWrite() {
        assertEquals(
            listOf(FileAccess("/proj/out.log", LockMode.WRITE)),
            parse("echo hi >> out.log"),
        )
    }

    @Test
    fun catIntoRedirectReadsAndWrites() {
        assertEquals(
            setOf(
                FileAccess("/proj/a.ts", LockMode.READ),
                FileAccess("/proj/b.ts", LockMode.WRITE),
            ),
            parse("cat a.ts > b.ts").toSet(),
        )
    }

    @Test
    fun grepFileIsReadPatternSkipped() {
        assertEquals(
            listOf(FileAccess("/proj/a.ts", LockMode.READ)),
            parse("grep TODO a.ts"),
        )
    }

    @Test
    fun headWithValueFlagSkipsCount() {
        assertEquals(
            listOf(FileAccess("/proj/a.ts", LockMode.READ)),
            parse("head -n 5 a.ts"),
        )
    }

    @Test
    fun teeIsWrite() {
        assertEquals(
            listOf(FileAccess("/proj/a.log", LockMode.WRITE)),
            parse("echo x | tee a.log"),
        )
    }

    @Test
    fun cpSrcReadDstWrite() {
        assertEquals(
            setOf(
                FileAccess("/proj/a.ts", LockMode.READ),
                FileAccess("/proj/b.ts", LockMode.WRITE),
            ),
            parse("cp a.ts b.ts").toSet(),
        )
    }

    @Test
    fun mvSrcReadDstWrite() {
        assertEquals(
            setOf(
                FileAccess("/proj/a.ts", LockMode.READ),
                FileAccess("/proj/b.ts", LockMode.WRITE),
            ),
            parse("mv a.ts b.ts").toSet(),
        )
    }

    @Test
    fun sedInPlaceIsWriteScriptSkipped() {
        assertEquals(
            listOf(FileAccess("/proj/a.ts", LockMode.WRITE)),
            parse("sed -i 's/x/y/' a.ts"),
        )
    }

    @Test
    fun sedWithoutInPlaceIsIgnored() {
        assertTrue(parse("sed 's/x/y/' a.ts").isEmpty())
    }

    @Test
    fun touchIsWrite() {
        assertEquals(
            listOf(FileAccess("/proj/a.ts", LockMode.WRITE)),
            parse("touch a.ts"),
        )
    }

    @Test
    fun truncateSkipsSizeFlag() {
        assertEquals(
            listOf(FileAccess("/proj/a.ts", LockMode.WRITE)),
            parse("truncate -s 0 a.ts"),
        )
    }

    @Test
    fun ddOfIsWriteIfIsRead() {
        assertEquals(
            listOf(
                FileAccess("/proj/in.img", LockMode.READ),
                FileAccess("/proj/out.img", LockMode.WRITE),
            ),
            parse("dd if=in.img of=out.img"),
        )
    }

    @Test
    fun writeWinsOverReadSamePath() {
        assertEquals(
            listOf(FileAccess("/proj/a.ts", LockMode.WRITE)),
            parse("cat a.ts > a.ts"),
        )
    }

    @Test
    fun grepRecursiveIsIgnored() {
        assertTrue(parse("grep -r TODO src").isEmpty())
        assertTrue(parse("grep -R TODO src").isEmpty())
    }

    @Test
    fun sedWithUnrelatedDashIFlagIsIgnored() {
        // -include is not in-place editing; must not produce a write lock
        assertTrue(parse("sed -include 's/x/y/' a.ts").isEmpty())
    }

    @Test
    fun sedInPlaceWithBackupSuffixIsWrite() {
        assertEquals(
            listOf(FileAccess("/proj/a.ts", LockMode.WRITE)),
            parse("sed -i.bak 's/x/y/' a.ts"),
        )
    }
}
