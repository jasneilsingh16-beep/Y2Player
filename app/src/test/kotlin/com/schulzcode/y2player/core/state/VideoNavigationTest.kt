package com.schulzcode.y2player.core.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VideoNavigationTest {
    @Before fun clearRowCache() = ScreenContent.clearCachedRows()

    private val root = "/storage/sdcard1/Videos"
    private val show = "$root/Severance"
    private val topItems = listOf(
        VideoItem(VideoItemKind.FOLDER, "Severance", show, videoCount = 2),
        VideoItem(VideoItemKind.VIDEO, "Arrival", "$root/Arrival.mp4", sizeBytes = 400_000_000)
    )
    private val episodes = listOf(
        VideoItem(VideoItemKind.VIDEO, "Episode 1", "$show/Episode 1.mp4", resumeMs = 754_000),
        VideoItem(VideoItemKind.VIDEO, "Episode 2", "$show/Episode 2.mp4")
    )

    private fun select(state: AppState, index: Int) =
        state.copy(screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index))

    private fun openVideos(): AppState {
        val menu = AppState()
        val index = ScreenContent.rows(menu).indexOfFirst { (it as ScreenRow.Action).key == "videos" }
        val result = AppReducer.reduce(select(menu, index), AppAction.Confirm)
        assertEquals(Screen.Videos(), result.state.currentScreen)
        assertEquals(listOf(AppEffect.LoadVideos(null), AppEffect.RefreshVideoProgress), result.effects)
        return result.state
    }

    @Test fun `the main menu opens the Videos section and asks for its listing`() {
        openVideos()
    }

    @Test fun `while listing, the section is empty and Confirm waits`() {
        val state = openVideos()
        assertTrue(ScreenContent.rows(state).isEmpty())
        val result = AppReducer.reduce(state, AppAction.Confirm)
        assertEquals(Screen.Videos(), result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun `an empty listing lets Confirm go back`() {
        val state = AppReducer.reduce(openVideos(), AppAction.VideosLoaded(null, emptyList())).state
        assertEquals(Screen.MainMenu, AppReducer.reduce(state, AppAction.Confirm).state.currentScreen)
    }

    @Test fun `rows use Y2Player's list with folders, sizes and resume points`() {
        val loaded = AppReducer.reduce(openVideos(), AppAction.VideosLoaded(null, topItems)).state
        val rows = ScreenContent.rows(loaded).map { it as ScreenRow.Action }
        assertEquals(listOf("Severance", "Arrival"), rows.map { it.title })
        assertEquals("2 videos", rows[0].subtitle)
        assertEquals("400 MB", rows[1].subtitle)

        val inShow = AppReducer.reduce(loaded, AppAction.Confirm)
        assertEquals(Screen.Videos(show), inShow.state.currentScreen)
        assertEquals(listOf<AppEffect>(AppEffect.LoadVideos(show)), inShow.effects)
        assertEquals("Severance", ScreenContent.title(inShow.state))

        val episodesLoaded = AppReducer.reduce(inShow.state, AppAction.VideosLoaded(show, episodes)).state
        assertEquals("Resume at 12:34", ScreenContent.rows(episodesLoaded).first().subtitle)
    }

    @Test fun `choosing a video plays it with the rest of its folder`() {
        val inShow = AppReducer.reduce(
            AppReducer.reduce(
                AppReducer.reduce(openVideos(), AppAction.VideosLoaded(null, topItems)).state,
                AppAction.Confirm
            ).state,
            AppAction.VideosLoaded(show, episodes)
        ).state
        val result = AppReducer.reduce(select(inShow, 1), AppAction.Confirm)
        assertEquals(
            listOf<AppEffect>(AppEffect.PlayVideo("$show/Episode 2.mp4", episodes.map { it.path })),
            result.effects
        )
        assertEquals(Screen.Videos(show), result.state.currentScreen)
    }

    @Test fun `Back walks up the folders to the main menu`() {
        val inShow = AppReducer.reduce(
            AppReducer.reduce(openVideos(), AppAction.VideosLoaded(null, topItems)).state,
            AppAction.Confirm
        ).state
        val up = AppReducer.reduce(inShow, AppAction.Back).state
        assertEquals(Screen.Videos(), up.currentScreen)
        assertEquals(0, up.selectedIndex)
        assertEquals(Screen.MainMenu, AppReducer.reduce(up, AppAction.Back).state.currentScreen)
    }

    @Test fun `a refreshed listing keeps the selected row`() {
        val loaded = AppReducer.reduce(openVideos(), AppAction.VideosLoaded(null, topItems)).state
        val onArrival = select(loaded, 1)
        val refreshed = AppReducer.reduce(onArrival, AppAction.VideosLoaded(null, listOf(topItems[1]))).state
        assertEquals(0, refreshed.selectedIndex)
        assertEquals("Arrival", ScreenContent.rows(refreshed)[refreshed.selectedIndex].title)
    }
}
