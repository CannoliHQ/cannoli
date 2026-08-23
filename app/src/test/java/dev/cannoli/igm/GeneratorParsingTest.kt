package dev.cannoli.igm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Proves the generators actually extract what we think, from source they were not tuned against.
 *
 * The regenerate-and-diff tests beside this one cannot do that. If a regex stops matching after an
 * upstream reformat, regenerating produces the same short table as the checked-in one and the diff
 * passes: both sides share the broken parser. Only the plausibility floors would notice, and only
 * if the loss were large. So the parsers are exercised here against a fixture instead, where a
 * regression shows up as a row that should have been emitted and was not.
 */
class GeneratorParsingTest {

    @get:Rule val tmp = TemporaryFolder()

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "scripts/ra-key-aliases.py").isFile }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text.trimIndent())
    }

    /** Runs a generator against a fake RetroArch tree. Scripts resolve source relative to themselves. */
    private fun runGenerator(script: String, buildFixture: (File) -> Unit): String {
        val fake = tmp.newFolder()
        buildFixture(File(fake, "retroarch"))
        File(fake, "scripts").mkdirs()
        File(repoRoot, "scripts/$script").copyTo(File(fake, "scripts/$script"))
        val p = ProcessBuilder("python3", "scripts/$script")
            .directory(fake).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    @Test
    fun `the alias generator pairs a menu name with its divergent config key`() {
        val out = runGenerator("ra-key-aliases.py") { ra ->
            write(File(ra, "configuration.c"), """
                SETTING_BOOL("stored_under_this",  &settings->bools.the_field, true, true, false);
                SETTING_UINT("same_name",          &settings->uints.agreeing_field, 0, true);
            """)
            write(File(ra, "settings/settings_def_fixture.h"), """
                S_BOOL(the_field, SOME_ENUM,
                      "shown_as_this",
                      true, SD_FLAG_NONE, 0, CMD_EVENT_NONE,
                      "Label", "Sublabel")
                S_UINT_EX(agreeing_field, OTHER_ENUM,
                      "same_name",
                      0, SD_FLAG_NONE, 0, CMD_EVENT_NONE, 0, 10, 1, 0, NULL, NULL,
                      "Label", "Sublabel")
            """)
        }

        assertTrue(
            "the divergent pair was not extracted:\n$out",
            out.contains("""{ "shown_as_this", "stored_under_this" },"""),
        )
        // A setting whose two names agree needs no alias, and emitting one would be noise.
        assertFalse("an agreeing pair was emitted as an alias:\n$out", out.contains("same_name"))
    }

    // The row that started all this: a field reached through offsetof rather than named directly.
    @Test
    fun `the alias generator handles an offsetof target`() {
        val out = runGenerator("ra-key-aliases.py") { ra ->
            write(File(ra, "configuration.c"), """
                SETTING_UINT("custom_viewport_width", &settings->video_vp_custom.width, 0, true);
            """)
            write(File(ra, "settings/settings_def_fixture.h"), """
                S_INT_AT(offsetof(settings_t, video_vp_custom.width), VIEWPORT_ENUM,
                      "video_viewport_custom_width",
                      0, SD_FLAG_NONE, 0, CMD_EVENT_NONE, 0, 9999, 1, 0, NULL, NULL,
                      "Label", "Sublabel")
            """)
        }

        assertTrue(
            "an offsetof-targeted row was not extracted:\n$out",
            out.contains("""{ "video_viewport_custom_width", "custom_viewport_width" },"""),
        )
    }

    @Test
    fun `the screen generator walks all four tables into one mapping`() {
        val out = runGenerator("ra-menu-screens.py") { ra ->
            write(File(ra, "msg_hash_lbl_str.h"), """
                #define MENU_ENUM_LABEL_FIXTURE_SETTINGS_STR "fixture_settings"
            """)
            write(File(ra, "menu/menu_displaylist.h"), """
                enum menu_displaylist_ctl_state
                {
                   DISPLAYLIST_NONE = 0,
                   DISPLAYLIST_FIXTURE_SETTINGS_LIST,
                };
            """)
            write(File(ra, "menu/cbs/menu_cbs_ok.c"), """
                static const ok_dl_map_t ok_dl_map[] = {
                   { MENU_ENUM_LABEL_FIXTURE_SETTINGS, ACTION_OK_DL_FIXTURE_SETTINGS_LIST },
                };
                static enum msg_hash_enums action_ok_dl_to_enum(unsigned lbl)
                {
                   switch (lbl)
                   {
                      case ACTION_OK_DL_FIXTURE_SETTINGS_LIST:
                         return MENU_ENUM_LABEL_DEFERRED_FIXTURE_SETTINGS_LIST;
                   }
                }
            """)
            write(File(ra, "menu/cbs/menu_cbs_deferred_push.c"), """
                GENERIC_DEFERRED_PUSH(deferred_push_fixture_settings_list, DISPLAYLIST_FIXTURE_SETTINGS_LIST)
                      {MENU_ENUM_LABEL_DEFERRED_FIXTURE_SETTINGS_LIST, deferred_push_fixture_settings_list},
            """)
        }

        assertTrue(
            "the four-table chain did not compose:\n$out",
            out.contains("""{ "fixture_settings", DISPLAYLIST_FIXTURE_SETTINGS_LIST },"""),
        )
    }

    // A constant this build does not declare cannot be named, or the generated table will not compile.
    @Test
    fun `the screen generator drops a screen whose displaylist constant is absent`() {
        val out = runGenerator("ra-menu-screens.py") { ra ->
            write(File(ra, "msg_hash_lbl_str.h"), """
                #define MENU_ENUM_LABEL_GHOST_SETTINGS_STR "ghost_settings"
            """)
            write(File(ra, "menu/menu_displaylist.h"), """
                enum menu_displaylist_ctl_state
                {
                   DISPLAYLIST_NONE = 0,
                };
            """)
            write(File(ra, "menu/cbs/menu_cbs_ok.c"), """
                static const ok_dl_map_t ok_dl_map[] = {
                   { MENU_ENUM_LABEL_GHOST_SETTINGS, ACTION_OK_DL_GHOST_SETTINGS_LIST },
                };
                static enum msg_hash_enums action_ok_dl_to_enum(unsigned lbl)
                {
                   switch (lbl)
                   {
                      case ACTION_OK_DL_GHOST_SETTINGS_LIST:
                         return MENU_ENUM_LABEL_DEFERRED_GHOST_SETTINGS_LIST;
                   }
                }
            """)
            write(File(ra, "menu/cbs/menu_cbs_deferred_push.c"), """
                GENERIC_DEFERRED_PUSH(deferred_push_ghost_settings_list, DISPLAYLIST_GHOST_SETTINGS_LIST)
                      {MENU_ENUM_LABEL_DEFERRED_GHOST_SETTINGS_LIST, deferred_push_ghost_settings_list},
            """)
        }

        assertFalse("a screen with no declared constant was emitted:\n$out", out.contains("ghost_settings"))
    }
}
