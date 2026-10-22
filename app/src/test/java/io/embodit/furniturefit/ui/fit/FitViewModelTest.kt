package io.embodit.furniturefit.ui.fit

import io.embodit.furniturefit.data.AnchorGateway
import io.embodit.furniturefit.data.AnchorOutcome
import io.embodit.furniturefit.data.AnchorRecord
import io.embodit.furniturefit.data.LayoutStore
import io.embodit.furniturefit.domain.AnchorPose
import io.embodit.furniturefit.domain.HeadPose
import io.embodit.furniturefit.domain.LengthUnit
import io.embodit.furniturefit.domain.PlaneSurface
import io.embodit.furniturefit.domain.Quat4
import io.embodit.furniturefit.domain.RoomModel
import io.embodit.furniturefit.domain.SavedLayout
import io.embodit.furniturefit.domain.Settings
import io.embodit.furniturefit.domain.Templates
import io.embodit.furniturefit.domain.Vec3
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FitViewModelTest {
    private class FakeAnchors(var fail: Boolean = false) : AnchorGateway {
        val stored = ArrayList<AnchorRecord>()

        override suspend fun create(position: Vec3, name: String): AnchorOutcome<AnchorRecord> {
            if (fail) return AnchorOutcome.Failed(-1, "fail")
            val r = AnchorRecord("anchor-${stored.size}", AnchorPose(position, Quat4.IDENTITY))
            stored += r
            return AnchorOutcome.Ok(r)
        }

        override suspend fun loadAll(): AnchorOutcome<List<AnchorRecord>> = AnchorOutcome.Ok(stored.toList())

        override suspend fun remove(anchorId: String): AnchorOutcome<Unit> {
            stored.removeAll { it.id == anchorId }
            return AnchorOutcome.Ok(Unit)
        }
    }

    private class FakeStore : LayoutStore {
        var layout: SavedLayout? = null
        var settings = Settings(unit = LengthUnit.CM, coachSeen = true)

        override fun loadLayout() = layout

        override fun saveLayout(layout: SavedLayout?) {
            this.layout = layout
        }

        override fun loadSettings() = settings

        override fun saveSettings(settings: Settings) {
            this.settings = settings
        }
    }

    private val floor = PlaneSurface("f", "FLOOR", listOf(Vec3(-3f, 0f, -5f), Vec3(3f, 0f, -5f), Vec3(3f, 0f, 1f), Vec3(-3f, 0f, 1f)))
    private val room = RoomModel.build(listOf(floor), emptyList(), null)
    private val head = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val anchors = FakeAnchors()
    private val store = FakeStore()

    private fun vm(): FitViewModel =
        FitViewModel(anchors, store, { head }, { null }, { scope.testScheduler.currentTime }, scope).also {
            it.onEvent(FitEvent.SceneReady)
            it.onEvent(FitEvent.RoomChanged(room))
        }

    @Test
    fun tilePlacesSelectsAndSaves() {
        val vm = vm()
        assertEquals("S3", vm.state.value.designState)
        vm.onEvent(FitEvent.TileClicked("sofa3"))
        val s = vm.state.value
        assertEquals(1, s.pieces.size)
        assertEquals(s.pieces[0].id, s.selected)
        assertEquals(-1.4f, s.pieces[0].center.z, 1e-3f)
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
        assertEquals(SaveState.SAVED, vm.state.value.save)
        assertEquals(1, store.layout!!.pieces.size)
    }

    @Test
    fun firstPressWithTheMenuOpenOnlyClosesIt() {
        val vm = vm()
        vm.onEvent(FitEvent.TileClicked("armchair"))
        vm.onEvent(FitEvent.MoreClicked)
        assertTrue(vm.state.value.menuOpen)
        vm.onEvent(FitEvent.EmptyTapped)
        assertFalse(vm.state.value.menuOpen)
        // the selection survived the press that closed the menu
        assertEquals(1, vm.state.value.selected)
        vm.onEvent(FitEvent.EmptyTapped)
        assertNull(vm.state.value.selected)
    }

    @Test
    fun keypadWritesOnlyOnOk() {
        val vm = vm()
        vm.onEvent(FitEvent.TileClicked("sofa2"))
        vm.onEvent(FitEvent.SizeClicked)
        vm.onEvent(FitEvent.SizingKey("1"))
        vm.onEvent(FitEvent.SizingKey("8"))
        // still 160 wide while typing
        assertEquals(1.6f, vm.state.value.pieces[0].dims.w, 1e-4f)
        vm.onEvent(FitEvent.SizingKey("0"))
        vm.onEvent(FitEvent.SizingKey("ok"))
        assertEquals(1.8f, vm.state.value.pieces[0].dims.w, 1e-4f)
        assertNull(vm.state.value.sizing)
    }

    @Test
    fun outOfRangeSizeCannotBeConfirmed() {
        val vm = vm()
        vm.onEvent(FitEvent.TileClicked("custom"))
        // the custom piece opens the keypad by itself
        assertTrue(vm.state.value.sizing != null)
        vm.onEvent(FitEvent.SizingKey("5"))
        assertFalse(vm.state.value.sizing!!.valid(LengthUnit.CM))
        vm.onEvent(FitEvent.SizingKey("ok"))
        assertTrue(vm.state.value.sizing != null)
    }

    @Test
    fun undoRestoresTheLastStep() {
        val vm = vm()
        vm.onEvent(FitEvent.TileClicked("desk"))
        vm.onEvent(FitEvent.RotateClicked)
        val turned = vm.state.value.pieces[0].yaw
        vm.onEvent(FitEvent.UndoClicked)
        assertTrue(vm.state.value.pieces[0].yaw != turned)
        vm.onEvent(FitEvent.UndoClicked)
        assertTrue(vm.state.value.pieces.isEmpty())
    }

    @Test
    fun twelvePiecesIsTheLimit() {
        val vm = vm()
        repeat(Templates.MAX_PIECES) { vm.onEvent(FitEvent.TileClicked("armchair")) }
        assertEquals("S18", vm.state.value.copy(selected = null).designState)
        vm.onEvent(FitEvent.TileClicked("armchair"))
        assertEquals(Templates.MAX_PIECES, vm.state.value.pieces.size)
        assertEquals(SnackKind.LIMIT, vm.state.value.snack?.kind)
    }

    @Test
    fun failedAnchorIsUnsavedWithRetry() {
        anchors.fail = true
        val vm = vm()
        vm.onEvent(FitEvent.TileClicked("armchair"))
        assertEquals(SaveState.UNSAVED, vm.state.value.save)
        assertEquals(SnackKind.UNSAVED, vm.state.value.snack?.kind)
        anchors.fail = false
        vm.onEvent(FitEvent.SnackActionClicked)
        assertEquals(SaveState.SAVED, vm.state.value.save)
    }

    @Test
    fun clearAllAsksThenRemovesEverything() {
        val vm = vm()
        vm.onEvent(FitEvent.TileClicked("armchair"))
        vm.onEvent(FitEvent.MoreClicked)
        vm.onEvent(FitEvent.ClearAllClicked)
        assertEquals("S14", vm.state.value.designState)
        vm.onEvent(FitEvent.ClearConfirmed)
        assertTrue(vm.state.value.pieces.isEmpty())
        assertTrue(anchors.stored.isEmpty())
        assertNull(store.layout)
    }

    @Test
    fun savedLayoutIsRestoredFromItsAnchor() {
        val first = vm()
        first.onEvent(FitEvent.TileClicked("bookcase"))
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
        val again = FitViewModel(anchors, store, { head }, { null }, { scope.testScheduler.currentTime }, scope)
        again.onEvent(FitEvent.SceneReady)
        again.onEvent(FitEvent.RoomChanged(room))
        scope.runCurrent()
        assertEquals(1, again.state.value.pieces.size)
        assertEquals(-1.4f, again.state.value.pieces[0].center.z, 1e-3f)
    }

    @Test
    fun missingAnchorTurnsElsewhereAfterTenSeconds() {
        store.layout = SavedLayout("gone", null, listOf(io.embodit.furniturefit.domain.SavedPiece(1, "desk", Templates.byId("desk").dims, Vec3.ZERO, 0f)), 2)
        val vm = FitViewModel(anchors, store, { head }, { null }, { scope.testScheduler.currentTime }, scope)
        vm.onEvent(FitEvent.SceneReady)
        vm.onEvent(FitEvent.RoomChanged(room))
        assertEquals("S1", vm.state.value.designState)
        scope.advanceTimeBy(11_000)
        scope.runCurrent()
        assertEquals("S16", vm.state.value.designState)
        assertFalse(vm.state.value.tilesEnabled)
    }
}
