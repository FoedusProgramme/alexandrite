package org.unitedsoftware.alexandrite.discovery

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryTest {
    @TempDir
    lateinit var home: Path

    private fun write(relative: String, text: String) {
        val file = home.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    // Skills, generically.

    @Test
    fun `a flat and a categorised skills layout are both found, hidden entries and nested files are not`() {
        write("skills/alpha/SKILL.md", "---\nname: alpha\ndescription: \"Alpha skill\"\n---\nBody")
        write("skills/.cache/stray/SKILL.md", "---\nname: stray\n---\nBody")
        write("skills/category/beta/SKILL.md", "---\nname: beta\n---\nBody")
        write("skills/category/beta/scripts/helper/SKILL.md", "should not be found, inside a skill's own files")
        write("skills/no-skill-here/notes.txt", "not a skill")

        val found = findSkillDirectories(home.resolve("skills")).map { it.fileName.toString() }.sorted()

        assertEquals(listOf("alpha", "beta"), found)
    }

    @Test
    fun `front matter gives the name and description, the directory name is the fallback`() {
        write("skills/named/SKILL.md", "---\nname: real-name\ndescription: 'quoted'\n---\nBody")
        write("skills/unnamed/SKILL.md", "# No front matter\nJust a body.")

        val named = readSkillFrontMatter(home.resolve("skills/named"))!!
        val unnamed = readSkillFrontMatter(home.resolve("skills/unnamed"))!!

        assertEquals("real-name", named.name)
        assertEquals("quoted", named.description)
        assertEquals("unnamed", unnamed.name)
        assertNull(unnamed.description)
    }

    @Test
    fun `importing a skill copies its files under a free, sanitized name`() {
        write("skills/My Skill!/SKILL.md", "---\nname: My Skill!\n---\nBody")
        write("skills/My Skill!/references/notes.md", "extra file")
        val skill = readSkillFrontMatter(home.resolve("skills/My Skill!"))!!
        val target = home.resolve("alexandrite-skills")
        Files.createDirectories(target)

        val first = importSkill(skill, target, taken = emptySet())
        val second = importSkill(skill, target, taken = setOf(first!!))

        assertEquals("my-skill", first)
        assertEquals("my-skill-2", second)
        assertEquals(
            "---\nname: My Skill!\n---\nBody",
            Files.readString(target.resolve("my-skill/SKILL.md")).trim(),
        )
        assertEquals("extra file", Files.readString(target.resolve("my-skill/references/notes.md")))
    }

    @Test
    fun `a skill over the file or byte cap is not imported`() {
        write("skills/big/SKILL.md", "---\nname: big\n---\nBody")
        val skill = readSkillFrontMatter(home.resolve("skills/big"))!!
        val target = home.resolve("alexandrite-skills")

        assertNull(importSkill(skill, target, taken = emptySet(), maxTotalBytes = 1))
        assertNull(importSkill(skill, target, taken = emptySet(), maxFiles = 0))
        assertTrue(Files.notExists(target.resolve("big")))
    }

    // Hermes.

    @Test
    fun `hermes is absent when its home directory does not exist`() {
        assertNull(discoverHermes(home))
    }

    @Test
    fun `hermes reads an uncommented mcp_servers block but not the shipped commented example`() {
        write(
            ".hermes/config.yaml",
            """
            skills:
              creation_nudge_interval: 15

            # =========================
            # MCP (Model Context Protocol) Servers
            # =========================
            # mcp_servers:
            #   time:
            #     command: uvx
            #     args: ["mcp-server-time"]

            mcp_servers:
              filesystem:
                command: npx
                args: ["-y", "@modelcontextprotocol/server-filesystem", "/home/user"]
              notion:
                url: https://mcp.notion.com/mcp
              github:
                command: npx
                args: ["-y", "@modelcontextprotocol/server-github"]
                env:
                  GITHUB_PERSONAL_ACCESS_TOKEN: "ghp_fixture"

            tts:
              provider: "gemini"
            """.trimIndent(),
        )
        write(".hermes/skills/apple/apple-reminders/SKILL.md", "---\nname: apple-reminders\n---\nBody")

        val source = discoverHermes(home)!!

        assertEquals("hermes", source.id)
        assertEquals(1, source.skills.size)
        assertEquals("apple-reminders", source.skills.single().name)
        assertEquals(3, source.mcpServers.size)
        val filesystem = source.mcpServers.single { it.name == "filesystem" }
        assertEquals("npx", filesystem.command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-filesystem", "/home/user"), filesystem.args)
        val notion = source.mcpServers.single { it.name == "notion" }
        assertEquals("https://mcp.notion.com/mcp", notion.url)
        val github = source.mcpServers.single { it.name == "github" }
        assertEquals(mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "ghp_fixture"), github.env)
    }

    // Claude Code.

    @Test
    fun `claude code reads its skills, its own and settings mcpServers, and a project's mcp json`() {
        write(".claude/skills/caveman/SKILL.md", "---\nname: caveman\n---\nBody")
        write(
            ".claude.json",
            """{"mcpServers": {"runpod": {"type": "http", "url": "https://mcp.example.com"}}}""",
        )
        write(
            ".claude/settings.json",
            """{"mcpServers": {"local": {"command": "npx", "args": ["server"]}}}""",
        )
        val project = home.resolve("project")
        Files.createDirectories(project)
        Files.writeString(
            project.resolve(".mcp.json"),
            """{"mcpServers": {"team": {"command": "uvx", "args": ["mcp-server-time"],
                "env": {"TEAM_TOKEN": "fixture-token"}}}}""",
        )

        val withProject = discoverClaudeCode(home, project)!!
        val withoutProject = discoverClaudeCode(home)!!

        assertEquals("claude-code", withProject.id)
        assertEquals(listOf("caveman"), withProject.skills.map { it.name })
        assertEquals(setOf("runpod", "local", "team"), withProject.mcpServers.map { it.name }.toSet())
        assertEquals(setOf("runpod", "local"), withoutProject.mcpServers.map { it.name }.toSet())
        val team = withProject.mcpServers.single { it.name == "team" }
        assertEquals(mapOf("TEAM_TOKEN" to "fixture-token"), team.env)
        val runpod = withProject.mcpServers.single { it.name == "runpod" }
        assertEquals("https://mcp.example.com", runpod.url)
    }

    @Test
    fun `a server named in both claude json and settings json keeps the claude json reading`() {
        write(".claude.json", """{"mcpServers": {"shared": {"command": "first"}}}""")
        write(".claude/settings.json", """{"mcpServers": {"shared": {"command": "second"}}}""")

        val source = discoverClaudeCode(home)!!

        assertEquals("first", source.mcpServers.single { it.name == "shared" }.command)
    }

    // Codex.

    @Test
    fun `codex reads mcp_servers tables from its toml, ignoring other tables and bundled skills`() {
        write(
            ".codex/config.toml",
            """
            [desktop]
            theme = "dark"

            [projects."/Users/john/alexandrite"]
            trusted = true

            [mcp_servers]

            [mcp_servers.computer-use]
            command = "./Codex Computer Use.app/MacOS/SkyComputerUseClient"
            args = ["mcp"]
            cwd = "."
            enabled = false

            [mcp_servers.node_repl]
            args = []
            command = "/Applications/ChatGPT.app/Contents/Resources/cua_node/bin/node_repl"

            [mcp_servers.node_repl.env]
            NODE_REPL_NODE_PATH = "/Applications/ChatGPT.app/Contents/Resources/cua_node/bin/node"
            FIXTURE_TOKEN = "fixture-value"
            """.trimIndent(),
        )
        write(".codex/skills/.system/review-agent/SKILL.md", "---\nname: review-agent\n---\nBody")
        write(".codex/skills/my-skill/SKILL.md", "---\nname: my-skill\n---\nBody")

        val source = discoverCodex(home)!!

        assertEquals("codex", source.id)
        assertEquals(listOf("my-skill"), source.skills.map { it.name })
        assertEquals(setOf("computer-use", "node_repl"), source.mcpServers.map { it.name }.toSet())
        val computerUse = source.mcpServers.single { it.name == "computer-use" }
        assertEquals("./Codex Computer Use.app/MacOS/SkyComputerUseClient", computerUse.command)
        assertEquals(listOf("mcp"), computerUse.args)
        assertEquals(".", computerUse.cwd)
        val nodeRepl = source.mcpServers.single { it.name == "node_repl" }
        assertEquals(
            mapOf(
                "NODE_REPL_NODE_PATH" to "/Applications/ChatGPT.app/Contents/Resources/cua_node/bin/node",
                "FIXTURE_TOKEN" to "fixture-value",
            ),
            nodeRepl.env,
        )
    }

    // OpenClaw.

    @Test
    fun `openclaw is absent when its home directory does not exist`() {
        assertNull(discoverOpenClaw(home))
    }

    @Test
    fun `openclaw reads the nested mcp servers table and resolves its own var syntax in headers, never env`() {
        write(
            ".openclaw/openclaw.json",
            """{"mcp": {"servers": {"fetcher": {"command": "uvx", "args": ["mcp-server-fetch"],
                "url": "https://mcp.example.com",
                "headers": {"Authorization": "${'$'}{OPENCLAW_FIXTURE_VAR}"}}}},
                "mcpServers": {"legacy": {"url": "https://old.example.com"}}}""",
        )
        write(".openclaw/skills/sample/SKILL.md", "---\nname: sample\n---\nBody")

        val source = discoverOpenClaw(home)!!

        assertEquals("openclaw", source.id)
        assertEquals(listOf("sample"), source.skills.map { it.name })
        // The real schema nests servers under "mcp.servers" only: a top-level "mcpServers" (as a non-canonical
        // source once suggested) is not read.
        assertEquals(listOf("fetcher"), source.mcpServers.map { it.name })
        val fetcher = source.mcpServers.single()
        // The variable is not set in this process, so its literal reference is kept as is.
        assertEquals("\${OPENCLAW_FIXTURE_VAR}", fetcher.headers["Authorization"])
        assertEquals(emptyMap(), fetcher.env)
    }

    // discoverAll.

    @Test
    fun `discoverAll skips every source that is not set up, and reaches every one that is`() {
        write(".hermes/config.yaml", "skills:\n  creation_nudge_interval: 15\n")
        write(".codex/config.toml", "[desktop]\n")

        val found = discoverAll(home).map { it.id }

        assertEquals(listOf("hermes", "codex"), found)
    }

    @Test
    fun `discoverAll with no source set up is empty`() {
        assertEquals(emptyList(), discoverAll(home))
    }

    // Secret extraction from args.

    @Test
    fun `secretArgs finds a value right after a known flag, not a flag or an unflagged value`() {
        val server = DiscoveredMcpServer(
            name = "x",
            command = "run",
            args = listOf("--token", "fixture-secret", "--verbose", "--api-key", "--other", "plain"),
        )

        val found = secretArgs(server)

        assertEquals(mapOf(1 to "fixture-secret"), found)
        assertContains(server.args, "plain")
    }
}
